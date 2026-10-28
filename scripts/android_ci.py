# SPDX-License-Identifier: MIT

"""Android CI change detection, report checks, packaging, and trusted artifact reuse."""

import argparse
import hashlib
import io
import json
import os
import platform
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from version import git

ROOT = Path(__file__).resolve().parents[1]
# Bump when the required checks or distributable format change incompatibly.
CONTRACT = 1
APK_NAMES = ("meshlight-android-debug.apk", "meshlight-android-release-unsigned.apk")
METADATA = "android-build-metadata.json"
CHECKSUMS = "ANDROID-SHA256SUMS"
FILES = {*APK_NAMES, METADATA, CHECKSUMS}


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def subtree(repo, revision):
    entries = git(repo, "ls-tree", revision, "--", "android-app").splitlines()
    if not entries:
        return None
    mode, kind, identity, *_ = entries[0].split()
    if mode != "040000" or kind != "tree":
        raise ValueError("android-app must be a Git tree")
    return identity


def source_info(repo, tree, ref, base=None, before=None):
    current = subtree(repo, tree)
    previous = None
    comparison = base or (before if ref.startswith("refs/heads/") else None)
    if comparison and comparison != "0" * 40:
        if not re.fullmatch(r"[0-9a-f]{40}", comparison):
            raise ValueError("Android comparison revision must be a full commit SHA")
        try:
            git(repo, "cat-file", "-e", f"{comparison}^{{commit}}")
        except ValueError:
            if base:
                raise
            comparison = None  # A force push can leave the old commit unavailable.
        else:
            previous = subtree(repo, comparison)
    else:
        comparison = None
    return {"tree": current, "changed": current != previous if comparison else None,
            "comparison_commit": comparison, "contract": CONTRACT}


def junit(directory):
    reports = sorted(Path(directory).rglob("TEST-*.xml"))
    cases = []
    for report in reports:
        root = ET.parse(report).getroot()
        for suite in root.iter("testsuite"):
            if any(int(suite.get(key, "0")) for key in ("failures", "errors", "skipped", "disabled")):
                raise ValueError(f"Failed or skipped Android tests: {report}")
        for case in root.iter("testcase"):
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise ValueError(f"Failed or skipped Android test: {report}: {case.get('name')}")
            cases.append((case.get("classname"), case.get("name")))
    if not cases:
        raise ValueError(f"Missing or empty Android JUnit reports: {directory}")
    if len(set(cases)) != len(cases):
        raise ValueError(f"Duplicate Android test results: {directory}")
    return {"status": "passed", "cases": len(cases)}


def toolchain(app):
    versions = (app / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    wrapper = (app / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    runtime = subprocess.run([str(Path(os.environ["JAVA_HOME"]) / "bin/java"), "-version"],
                             capture_output=True, text=True, check=True)
    return {"os": platform.platform(), "java": (runtime.stderr + runtime.stdout).strip(),
            "gradle": re.search(r"gradle-([\d.]+)-bin", wrapper).group(1),
            "agp": re.search(r'^agp\s*=\s*"([^"]+)"', versions, re.MULTILINE).group(1),
            "sdk": "android-37.0", "build_tools": "36.0.0"}


def stage_host(app, output):
    app, output = Path(app), Path(output)
    tests = {"unit": junit(app / "app/build/test-results/testDebugUnitTest"),
             "screenshots": junit(app / "app/build/test-results/validateDebugScreenshotTest")}
    lint = ET.parse(app / "app/build/reports/lint-results-debug.xml").getroot()
    if any(issue.get("severity") in {"Error", "Fatal"} for issue in lint.iter("issue")):
        raise ValueError("Android lint reported errors")
    coverage = app / "app/build/reports/jacoco/jacocoDebugReport/jacocoDebugReport.xml"
    if not coverage.is_file() or not coverage.stat().st_size:
        raise ValueError("Missing Android coverage report")
    variants = {}
    output.mkdir(parents=True, exist_ok=True)
    for variant, name in zip(("debug", "release"), APK_NAMES):
        directory = app / f"app/build/outputs/apk/{variant}"
        details = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
        elements = details["elements"]
        if len(elements) != 1:
            raise ValueError("Expected one Android APK per variant")
        entry = elements[0]
        # Do not allow output metadata to select files outside the build directory.
        filename = entry["outputFile"]
        if Path(filename).name != filename or "/" in filename or "\\" in filename:
            raise ValueError("Invalid APK output filename")
        source = directory / filename
        if not source.is_file() or not source.stat().st_size:
            raise ValueError(f"Missing or empty APK: {source}")
        shutil.copyfile(source, output / name)
        variants[variant] = {"application_id": details["applicationId"],
                             "version_name": entry["versionName"], "version_code": entry["versionCode"]}
    write_json(output / "host-validation.json",
               {"tests": tests, "variants": variants, "toolchain": toolchain(app)})


def stage_device(app, output):
    app = Path(app)
    write_json(output, {"tests": {"instrumentation": junit(app / "app/build/outputs/androidTest-results/connected/debug")},
                        "toolchain": dict(toolchain(app), emulator={"api": "37.0", "target": "google_apis", "arch": "x86_64"})})


def verify_avd(directory, name, output):
    """Reject old avdmanager's silent android-0 target before booting the image."""
    directory = Path(directory)
    def properties(path):
        return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                    if "=" in line and not line.lstrip().startswith("#"))
    identity = properties(directory / f"{name}.ini")
    config = properties(directory / f"{name}.avd/config.ini")
    details = {"target": identity.get("target"), "image": config.get("image.sysdir.1"),
               "command_line_tools": str(Path(os.environ["ANDROID_HOME"]) / "cmdline-tools/latest")}
    write_json(output, details)
    if details["target"] != "android-37.0" or (
            details["image"] or "").replace("\\", "/").rstrip("/") != "system-images/android-37.0/google_apis/x86_64":
        raise ValueError(f"Incorrect Android AVD metadata; check command-line tools selection: {details}")
    return details


def run_device(app, output):
    app, output = Path(app).resolve(), Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run(["adb", "logcat", "-c"], check=True)
    try:
        with (output / "android-device.log").open("w", encoding="utf-8") as log:
            subprocess.run(["bash", "./gradlew", ":app:connectedDebugAndroidTest",
                            "--no-configuration-cache", "--console=plain"], cwd=app,
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        # Some AGP device setup failures still return exit code zero. Validate
        # actual execution while the emulator is alive so diagnostics survive.
        junit(app / "app/build/outputs/androidTest-results/connected/debug")
    finally:
        with (output / "android-logcat.log").open("w", encoding="utf-8") as log:
            subprocess.run(["adb", "logcat", "-d"], stdout=log, stderr=subprocess.STDOUT, check=False)
        print((output / "android-device.log").read_text(encoding="utf-8", errors="replace"))


def package(info, host, device, output):
    host, output = Path(host), Path(output)
    host_info = json.loads((host / "host-validation.json").read_text(encoding="utf-8"))
    device_info = json.loads(Path(device).read_text(encoding="utf-8"))
    details = {"contract": CONTRACT, "android_tree": info["android"]["tree"],
               "source_commit": info["source_commit"], "tree": info["tree"],
               "base_commit": info.get("base_commit"), "pr_number": info.get("pr_number"),
               "repository": os.environ["GITHUB_REPOSITORY"], "event": os.environ["GITHUB_EVENT_NAME"],
               "run_id": os.environ["GITHUB_RUN_ID"], "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"],
               "variants": host_info["variants"], "tests": dict(host_info["tests"], **device_info["tests"]),
               "toolchain": {"host": host_info["toolchain"], "device": device_info["toolchain"]}}
    output.mkdir(parents=True, exist_ok=True)
    for name in APK_NAMES:
        shutil.copyfile(host / name, output / name)
    write_json(output / METADATA, details)
    (output / CHECKSUMS).write_text("".join(
        f"{hashlib.sha256((output / name).read_bytes()).hexdigest()}  {name}\n"
        for name in sorted({*APK_NAMES, METADATA})), encoding="utf-8")
    validate(output, info["android"]["tree"])
    return details


def validate(directory, expected_tree, repo=None, repository=None, require_push=False):
    directory = Path(directory)
    if not expected_tree or {path.name for path in directory.iterdir()} != FILES:
        raise ValueError("Android artifact must contain exactly both APKs, metadata, and checksums")
    checksums = {}
    for line in (directory / CHECKSUMS).read_text(encoding="utf-8").splitlines():
        digest, name = line.split("  ", 1)
        if name in checksums or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("Invalid or duplicate Android checksum")
        checksums[name] = digest
    if set(checksums) != {*APK_NAMES, METADATA}:
        raise ValueError("Incomplete Android checksums")
    for name, digest in checksums.items():
        path = directory / name
        if not path.is_file() or not path.stat().st_size or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError(f"Android checksum mismatch or empty file: {name}")
    details = json.loads((directory / METADATA).read_text(encoding="utf-8"))
    if not isinstance(details, dict) or details.get("contract") != CONTRACT or details.get("android_tree") != expected_tree:
        raise ValueError("Android source tree or artifact contract mismatch")
    if not isinstance(details.get("tests"), dict) or set(details["tests"]) != {"unit", "screenshots", "instrumentation"} or any(
            not isinstance(result, dict) or result.get("status") != "passed" or type(result.get("cases")) is not int or result["cases"] <= 0
            for result in details["tests"].values()):
        raise ValueError("Android artifact lacks passing test evidence")
    if (not re.fullmatch(r"[0-9a-f]{40}", details.get("source_commit", ""))
            or not re.fullmatch(r"[0-9a-f]{40}", details.get("tree", ""))
            or not str(details.get("run_id", "")).isdigit()
            or not str(details.get("run_attempt", "")).isdigit()
            or not details.get("repository")):
        raise ValueError("Android artifact lacks original build provenance")
    if not isinstance(details.get("variants"), dict) or set(details["variants"]) != {"debug", "release"} or any(
            not isinstance(variant, dict) or not variant.get("application_id") or not isinstance(variant.get("version_name"), str)
            or type(variant.get("version_code")) is not int for variant in details["variants"].values()):
        raise ValueError("Android artifact lacks app versions")
    if not isinstance(details.get("toolchain"), dict) or set(details["toolchain"]) != {"host", "device"} or any(
            not isinstance(runtime, dict) or not all(runtime.get(key) for key in ("os", "java", "gradle", "agp", "sdk", "build_tools"))
            for runtime in details["toolchain"].values()):
        raise ValueError("Android artifact lacks toolchain identity")
    if repository and details["repository"] != repository:
        raise ValueError("Android artifact belongs to another repository")
    if require_push and (details.get("event") != "push" or details.get("pr_number") is not None
                         or details.get("base_commit") is not None):
        raise ValueError("Android reuse/release requires a push build")
    if repo:
        git(repo, "merge-base", "--is-ancestor", details["source_commit"], "HEAD")
        if (subtree(repo, details["source_commit"]) != expected_tree
                or git(repo, "rev-parse", f"{details['source_commit']}^{{tree}}") != details["tree"]):
            raise ValueError("Android provenance differs from its original Git source")
    return details


def github_json(endpoint):
    result = subprocess.run(["gh", "api", endpoint], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def github_download(endpoint):
    return subprocess.run(["gh", "api", endpoint], capture_output=True, check=True).stdout


def pages(endpoint, key):
    page = 1
    while True:
        separator = "&" if "?" in endpoint else "?"
        items = github_json(f"{endpoint}{separator}per_page=100&page={page}")[key]
        yield from items
        if len(items) < 100:
            return
        page += 1


def reuse(repo, info, output, repository):
    """Only successful push CI runs can supply artifacts, never PR runs or caches."""
    prefix = f"repos/{repository}/actions"
    runs = pages(f"{prefix}/workflows/ci.yml/runs?event=push&status=success", "workflow_runs")
    for run in runs:
        if (run.get("event") != "push" or run.get("status") != "completed" or run.get("conclusion") != "success"
                or run.get("repository", {}).get("full_name", "").lower() != repository.lower()
                or str(run["id"]) == os.environ.get("GITHUB_RUN_ID")):
            continue
        try:
            sha = run["head_sha"]
            if not re.fullmatch(r"[0-9a-f]{40}", sha):
                continue
            git(repo, "merge-base", "--is-ancestor", sha, info["source_commit"])
            if subtree(repo, sha) != info["android"]["tree"]:
                continue
            for artifact in pages(f"{prefix}/runs/{run['id']}/artifacts", "artifacts"):
                if artifact["name"] != "android-apks" or artifact.get("expired", True):
                    continue
                payload = github_download(f"{prefix}/artifacts/{artifact['id']}/zip")
                digest = artifact.get("digest")
                if digest and digest != "sha256:" + hashlib.sha256(payload).hexdigest():
                    raise ValueError("Downloaded Android artifact digest mismatch")
                with tempfile.TemporaryDirectory() as temporary:
                    destination = Path(temporary)
                    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
                        if len(archive.namelist()) != len(FILES) or set(archive.namelist()) != FILES:
                            raise ValueError("Unexpected Android archive contents")
                        for name in FILES:
                            (destination / name).write_bytes(archive.read(name))
                    details = validate(destination, info["android"]["tree"], repo, repository, require_push=True)
                    Path(output).mkdir(parents=True, exist_ok=True)
                    for name in FILES:
                        shutil.copyfile(destination / name, Path(output) / name)
                print(f"Reusing Android APKs from run {run['id']}; original build {details['run_id']}")
                return details
        except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
            print(f"Ignoring unavailable or invalid Android artifact from run {run['id']}: {error}")
    return None


def plan(repo, info, output, repository):
    if not info["android"]["tree"]:
        raise ValueError("Android source directory is missing")
    if info.get("pr_number") is not None:
        if type(info["android"]["changed"]) is not bool:
            raise ValueError("PR Android change comparison is missing")
        return "build" if info["android"]["changed"] else "skip"
    if info["android"]["changed"]:
        return "build"
    try:
        if reuse(repo, info, output, repository):
            return "reuse"
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Android artifact discovery unavailable; building and testing instead: {error}")
    return "build"


def complete(jobs, is_pr=False):
    mode = jobs["android-plan"].get("outputs", {}).get("mode")
    expected = {"metadata": "success", "build": "success", "tests": "success", "android-plan": "success",
                "android-host": "success" if mode == "build" else "skipped",
                "android-device": "success" if mode == "build" else "skipped",
                "android-artifacts": "skipped" if mode == "skip" else "success"}
    if mode not in {"build", "reuse", "skip"} or (mode == "skip" and not is_pr) or (mode == "reuse" and is_pr):
        raise ValueError("Missing or invalid Android validation mode")
    if set(jobs) != set(expected) or any(jobs[name]["result"] != result for name, result in expected.items()):
        raise ValueError(f"Validation jobs did not complete as required for Android mode {mode}: {jobs}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("plan", "package", "verify"):
        command = commands.add_parser(name)
        command.add_argument("--metadata", type=Path, required=True)
        command.add_argument("--output", type=Path, required=True)
        if name == "package":
            command.add_argument("--host", type=Path, required=True)
            command.add_argument("--device", type=Path, required=True)
    for name in ("stage-host", "stage-device", "run-device"):
        command = commands.add_parser(name)
        command.add_argument("--app", type=Path, required=True)
        command.add_argument("--output", type=Path, required=True)
    avd = commands.add_parser("verify-avd")
    avd.add_argument("--name", required=True)
    avd.add_argument("--output", type=Path, required=True)
    commands.add_parser("complete")
    args = parser.parse_args()
    try:
        if args.command in {"plan", "package", "verify"}:
            info = json.loads(args.metadata.read_text(encoding="utf-8"))
        if args.command == "plan":
            mode = plan(args.repo, info, args.output, os.environ["GITHUB_REPOSITORY"])
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                output.write(f"mode={mode}\n")
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
                summary.write(f"Android: **{mode}**; source tree `{info['android']['tree']}`.\n")
                if mode == "reuse":
                    original = json.loads((args.output / METADATA).read_text(encoding="utf-8"))
                    summary.write(f"Original build: {original['repository']}/actions/runs/{original['run_id']} "
                                  f"(attempt {original['run_attempt']}).\n")
        elif args.command == "stage-host":
            stage_host(args.app, args.output)
        elif args.command == "stage-device":
            stage_device(args.app, args.output)
        elif args.command == "run-device":
            run_device(args.app, args.output)
        elif args.command == "verify-avd":
            verify_avd(os.environ["ANDROID_AVD_HOME"], args.name, args.output)
        elif args.command == "package":
            package(info, args.host, args.device, args.output)
        elif args.command == "verify":
            validate(args.output, info["android"]["tree"], args.repo if info.get("pr_number") is None else None,
                     os.environ["GITHUB_REPOSITORY"], require_push=info.get("pr_number") is None)
        elif args.command == "complete":
            complete(json.loads(os.environ["RESULTS"]), is_pr=os.environ["IS_PR"] == "true")
    except (ValueError, KeyError, TypeError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Android CI error: {error}\n")


if __name__ == "__main__":
    main()
