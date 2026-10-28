# SPDX-License-Identifier: MIT

"""Shared CI preparation, product inventory, packaging, and test-report checks."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import zipfile
from pathlib import Path

from version import git, resolve
from android_ci import source_info

ROOT = Path(__file__).resolve().parents[1]
IMAGE = ("ghcr.io/nrfconnect/sdk-nrf-toolchain@sha256:"
         "f24d8932ff081ebcd8da9c248f4449bdabe461c0620a7a4ac9e95eb577ba2276")


def products(repo):
    items = json.loads((Path(repo) / "firmware/products.json").read_text(encoding="utf-8"))
    if not isinstance(items, list) or not items:
        raise ValueError("Product inventory must be a nonempty list")
    identifiers = set()
    for item in items:
        if (not isinstance(item, dict) or not {"id", "board"} <= set(item)
                or set(item) - {"id", "board", "sysbuild", "capacity_overlay"}):
            raise ValueError("Each product must have id and board, with optional sysbuild/capacity_overlay")
        if "sysbuild" in item and not isinstance(item["sysbuild"], bool):
            raise ValueError("Product sysbuild must be a boolean")
        if "capacity_overlay" in item and (not isinstance(item["capacity_overlay"], str)
                or not re.fullmatch(r"[a-z0-9_]+\.overlay", item["capacity_overlay"])):
            raise ValueError("Product capacity_overlay must be an overlay filename")
        if not isinstance(item["id"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", item["id"]):
            raise ValueError("Invalid product id")
        if item["id"] in identifiers:
            raise ValueError(f"Duplicate product: {item['id']}")
        if not isinstance(item["board"], str) or not re.fullmatch(r"[a-zA-Z0-9_./-]+", item["board"]):
            raise ValueError("Invalid board target")
        identifiers.add(item["id"])
    return items


def prepare(repo, base=None, head=None):
    """Reconstruct the event's merge without depending on moving branch refs."""
    if bool(base) != bool(head):
        raise ValueError("Both base and head are required for PR preparation")
    if base:
        for sha in (base, head):
            if not re.fullmatch(r"[0-9a-f]{40}", sha):
                raise ValueError("PR revisions must be full commit SHAs")
            # Full checkout of the event's merge SHA fetches both parents too.
            # Do not fetch moving PR refs or require persisted private-repo credentials.
            git(repo, "cat-file", "-e", f"{sha}^{{commit}}")
        git(repo, "checkout", "--detach", base)
        git(repo, "-c", "user.name=Meshlight CI", "-c", "user.email=ci@meshlight.invalid",
            "merge", "--no-ff", "--no-commit", head)
    return git(repo, "write-tree") if base else git(repo, "rev-parse", "HEAD^{tree}")


def metadata(repo, ref, base=None, head=None, pr_number=None, before=None):
    version = resolve(repo, "refs/heads/master" if base else ref,
                      base or "HEAD", ci=True, pr_number=pr_number, pr_head=head)
    tree = git(repo, "write-tree") if base else git(repo, "rev-parse", "HEAD^{tree}")
    result = dict(version, products=products(repo), tree=tree,
                  android=source_info(repo, tree, ref, base, before),
                  source_commit=head or version["commit"], base_commit=base,
                  pr_number=pr_number, toolchain_image=IMAGE,
                  sdk="nRF Connect SDK 3.3.0",
                  manifest_sha256=hashlib.sha256((Path(repo) / "west.yml").read_bytes()).hexdigest(),
                  run_id=os.environ.get("GITHUB_RUN_ID"),
                  run_attempt=os.environ.get("GITHUB_RUN_ATTEMPT"))
    return result


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def firmware_outputs(product):
    """Archive suffixes and build-relative paths, including both nRF5340 cores."""
    app = Path("firmware") if product.get("sysbuild", False) else Path()
    outputs = {f".{ext}": app / "zephyr" / f"zephyr.{ext}"
               for ext in ("hex", "bin", "elf")}
    if product.get("sysbuild", False):
        outputs.update({f"-cpunet.{ext}": Path("ipc_radio/zephyr") / f"zephyr.{ext}"
                        for ext in ("hex", "bin", "elf")})
    return outputs


def package(build_dir, output_dir, product, info, dependencies=None):
    selected = next((p for p in info["products"] if p["id"] == product), None)
    if selected is None:
        raise ValueError(f"Unknown product {product!r}")
    name = f"meshlight-{product}-{info['version']}"
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    license_path = ROOT / "LICENSE"
    if not license_path.is_file() or not license_path.stat().st_size:
        raise ValueError("Missing or empty project license")
    payload = {"LICENSE": license_path.read_bytes()}
    for suffix, relative_path in firmware_outputs(selected).items():
        source = Path(build_dir) / relative_path
        if not source.is_file() or not source.stat().st_size:
            raise ValueError(f"Missing or empty firmware output: {source}")
        payload[f"{name}{suffix}"] = source.read_bytes()
    app_dir = Path(build_dir) / "firmware" if selected.get("sysbuild", False) else Path(build_dir)
    header = (app_dir / "generated/meshlight_version.h").read_text(encoding="utf-8")
    if f'"{info["version"]}"' not in header or f'"{info["source_commit"]}"' not in header:
        raise ValueError("Generated firmware header differs from workflow metadata")
    # Ensure the linked firmware contains the identity, not just a generated header.
    for identity in (info["version"], info["source_commit"]):
        if (identity + "\0").encode() not in payload[f"{name}.elf"]:
            raise ValueError("Firmware ELF does not contain its advertised version/source identity")
    details = dict(info, product=selected,
                   run_id=os.environ.get("GITHUB_RUN_ID", info.get("run_id")),
                   run_attempt=os.environ.get("GITHUB_RUN_ATTEMPT", info.get("run_attempt")))
    if dependencies:
        details["dependencies"] = json.loads(Path(dependencies).read_text(encoding="utf-8"))
    payload["build-metadata.json"] = (json.dumps(details, indent=2, sort_keys=True) + "\n").encode()
    payload["SHA256SUMS"] = "".join(
        f"{hashlib.sha256(data).hexdigest()}  {filename}\n"
        for filename, data in sorted(payload.items())
    ).encode()
    archive = output_dir / f"{name}.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for filename, data in sorted(payload.items()):
            bundle.writestr(filename, data)
    return archive


def verify_twister(report, expected):
    data = json.loads(Path(report).read_text(encoding="utf-8"))
    planned = json.loads(Path(expected).read_text(encoding="utf-8"))
    expected_names = {s["name"] for s in planned["testsuites"]}
    suites = data.get("testsuites", [])
    names = [s["name"] for s in suites]
    if not expected_names or set(names) != expected_names or len(names) != len(expected_names):
        raise ValueError(f"Missing, extra, or duplicate Twister scenarios: {names}")
    total = 0
    for suite in suites:
        cases = suite.get("testcases", [])
        if suite.get("status") != "passed" or not cases:
            raise ValueError(f"Scenario did not execute successfully: {suite['name']}")
        if any(case.get("status") != "passed" for case in cases):
            raise ValueError(f"Skipped or failed test in {suite['name']}")
        planned_suite = next(s for s in planned["testsuites"] if s["name"] == suite["name"])
        planned_cases = {case["identifier"] for case in planned_suite.get("testcases", [])}
        actual_cases = {case["identifier"] for case in cases}
        if len(actual_cases) != len(cases) or (planned_cases and planned_cases != actual_cases):
            raise ValueError(f"Missing or unexpected test cases in {suite['name']}")
        total += len(cases)
    print(f"Passed: {len(suites)} scenarios, {total} test cases")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    prep.add_argument("--base")
    prep.add_argument("--head")
    prep.add_argument("--expected", type=Path)
    meta = commands.add_parser("metadata")
    meta.add_argument("--ref", required=True)
    meta.add_argument("--base")
    meta.add_argument("--head")
    meta.add_argument("--pr-number", type=int)
    meta.add_argument("--before")
    meta.add_argument("--output", type=Path, required=True)
    pack = commands.add_parser("package")
    pack.add_argument("--metadata", type=Path, required=True)
    pack.add_argument("--product", required=True)
    pack.add_argument("--build-dir", type=Path, required=True)
    pack.add_argument("--output-dir", type=Path, required=True)
    pack.add_argument("--dependencies", type=Path)
    verify = commands.add_parser("verify-tests")
    verify.add_argument("--report", type=Path, required=True)
    verify.add_argument("--expected", type=Path, required=True)
    deps = commands.add_parser("dependencies")
    deps.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            tree = prepare(args.repo, args.base, args.head)
            if args.expected and tree != json.loads(args.expected.read_text())["tree"]:
                raise ValueError("Prepared source tree differs from the metadata job")
            print(f"Prepared source tree: {tree}")
        elif args.command == "metadata":
            result = metadata(args.repo, args.ref, args.base, args.head, args.pr_number, args.before)
            write_json(args.output, result)
            if "GITHUB_OUTPUT" in os.environ:
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                    for key, value in {"version": result["version"], "channel": result["channel"],
                                       "matrix": json.dumps({"include": result["products"]}),
                                       "image": IMAGE}.items():
                        output.write(f"{key}={value}\n")
            print(json.dumps(result, indent=2))
        elif args.command == "package":
            info = json.loads(args.metadata.read_text(encoding="utf-8"))
            print(package(args.build_dir, args.output_dir, args.product, info, args.dependencies))
        elif args.command == "verify-tests":
            verify_twister(args.report, args.expected)
        elif args.command == "dependencies":
            result = subprocess.run(["west", "list", "-f", "{name} {revision} {sha}"],
                                    text=True, capture_output=True, check=True)
            write_json(args.output, [dict(zip(("name", "revision", "sha"), line.split()))
                                     for line in result.stdout.splitlines()])
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"CI error: {error}\n")


if __name__ == "__main__":
    main()
