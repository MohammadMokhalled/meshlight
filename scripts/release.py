# SPDX-License-Identifier: MIT

"""Publish complete, validated final releases; never overwrite published assets."""

import argparse
import hashlib
import json
import os
import subprocess
import zipfile
from pathlib import Path
from urllib.parse import quote

from version import FINAL, git
from android_ci import FILES as ANDROID_FILES, validate as validate_android
from ci import firmware_outputs


def verify_workflow_identity(repo, info, sha, ref):
    # Peel annotated tags as well as ordinary commit SHAs from the event.
    commit = git(repo, "rev-parse", f"{sha}^{{commit}}")
    if (commit != info["source_commit"] or git(repo, "rev-parse", "HEAD") != commit
            or git(repo, "rev-parse", "HEAD^{tree}") != info["tree"]
            or ref != info["ref"]):
        raise ValueError("Workflow source/ref differs from release metadata")


def gh(*args, missing_ok=False):
    result = subprocess.run(["gh", *args], text=True, capture_output=True, check=False)
    if result.returncode:
        if missing_ok and "HTTP 404" in result.stderr:
            return None
        raise ValueError(f"gh {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout


def validate_packages(directory, info):
    directory = Path(directory)
    expected = {f"meshlight-{p['id']}-{info['version']}.zip" for p in info["products"]}
    if not expected or {p.name for p in directory.glob("*.zip")} != expected:
        raise ValueError("Release must contain exactly one package for every product")
    paths = [directory / name for name in sorted(expected)]
    for path in paths:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise ValueError(f"Duplicate entries in {path.name}")
            details = json.loads(archive.read("build-metadata.json"))
            product = details["product"]
            if product not in info["products"] or path.name != f"meshlight-{product['id']}-{info['version']}.zip":
                raise ValueError(f"Wrong product in {path.name}")
            for key in ("version", "channel", "source_commit", "tree", "toolchain_image", "manifest_sha256"):
                if details.get(key) != info.get(key):
                    raise ValueError(f"Mismatched {key} in {path.name}")
            stem = path.stem
            payload_names = {f"{stem}{suffix}" for suffix in firmware_outputs(product)} | {
                "build-metadata.json", "LICENSE"
            }
            if set(names) != payload_names | {"SHA256SUMS"}:
                raise ValueError(f"Unexpected package contents in {path.name}")
            checksums = {}
            for line in archive.read("SHA256SUMS").decode().splitlines():
                digest, filename = line.split("  ", 1)
                if filename in checksums:
                    raise ValueError("Duplicate checksum entry")
                checksums[filename] = digest
            if set(checksums) != payload_names:
                raise ValueError(f"Incomplete checksums in {path.name}")
            for filename, digest in checksums.items():
                if hashlib.sha256(archive.read(filename)).hexdigest() != digest:
                    raise ValueError(f"Checksum mismatch in {path.name}: {filename}")
    return paths


def publish(repository, info, directory, android_directory):
    tag = info["ref"].removeprefix("refs/tags/")
    if info["channel"] != "final" or not FINAL.fullmatch(tag) or tag[1:] != info["version"]:
        raise ValueError("Only final vx.y.z tag builds can publish releases")
    packages = validate_packages(directory, info)
    validate_android(android_directory, info["android"]["tree"], repository=repository, require_push=True)
    android_paths = [Path(android_directory) / name for name in sorted(ANDROID_FILES)]
    ref = json.loads(gh("api", f"repos/{repository}/git/ref/tags/{quote(tag, safe='')}"))["object"]
    for _ in range(10):
        if ref["type"] != "tag":
            break
        ref = json.loads(gh("api", f"repos/{repository}/git/tags/{ref['sha']}"))["object"]
    if ref["type"] != "commit" or ref["sha"] != info["source_commit"]:
        raise ValueError("Remote release tag has moved or does not match the built source")

    metadata_path = Path(directory) / "build-metadata.json"
    metadata_path.write_text(json.dumps(info, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    checksum_path = Path(directory) / "SHA256SUMS"
    checksum_path.write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
        for path in [*packages, *android_paths, metadata_path]
    ), encoding="utf-8")
    assets = [*packages, *android_paths, metadata_path, checksum_path]
    expected_names = {path.name for path in assets}
    endpoint = f"repos/{repository}/releases/tags/{quote(tag, safe='')}"
    existing = gh("api", endpoint, missing_ok=True)
    release = json.loads(existing) if existing else None
    if release and not release["draft"]:
        if {asset["name"] for asset in release["assets"]} != expected_names:
            raise ValueError("Published release has missing or unexpected assets; it will not be modified")
        published = json.loads(gh("release", "download", tag, "--repo", repository,
                                  "--pattern", "build-metadata.json", "--output", "-"))
        for key in ("version", "source_commit", "tree", "products", "android"):
            if published.get(key) != info.get(key):
                raise ValueError(f"Published release {key} differs; it will not be modified")
        print(f"Release {tag} is already published and complete; no assets changed")
        return
    if release:
        if not {asset["name"] for asset in release["assets"]}.issubset(expected_names):
            raise ValueError("Draft contains unexpected assets; refusing to publish")
    else:
        gh("release", "create", tag, "--repo", repository, "--verify-tag", "--draft",
           "--title", info["version"], "--generate-notes")
    gh("release", "upload", tag, "--repo", repository, "--clobber", *map(str, assets))
    uploaded = json.loads(gh("api", endpoint))
    if not uploaded["draft"] or {asset["name"] for asset in uploaded["assets"]} != expected_names:
        raise ValueError("Draft upload is incomplete or draft state changed; release was not published")
    gh("release", "edit", tag, "--repo", repository, "--draft=false")
    print(f"Published {tag} with {len(packages)} firmware packages and both Android APKs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--packages", type=Path, required=True)
    parser.add_argument("--android", type=Path, required=True)
    args = parser.parse_args()
    try:
        info = json.loads(args.metadata.read_text(encoding="utf-8"))
        verify_workflow_identity(Path(__file__).resolve().parents[1], info,
                                 os.environ["GITHUB_SHA"], os.environ["GITHUB_REF"])
        validate_android(args.android, info["android"]["tree"], Path(__file__).resolve().parents[1],
                         os.environ["GITHUB_REPOSITORY"], require_push=True)
        publish(os.environ["GITHUB_REPOSITORY"], info, args.packages, args.android)
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Release error: {error}\n")


if __name__ == "__main__":
    main()
