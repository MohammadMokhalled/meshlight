# SPDX-License-Identifier: MIT

"""Small synthetic Android CI artifacts; no SDK, emulator, or GitHub required."""

import hashlib
import json
from pathlib import Path

from android_ci import APK_NAMES, CHECKSUMS, CONTRACT, METADATA


def checksums(directory):
    directory = Path(directory)
    (directory / CHECKSUMS).write_text("".join(
        f"{hashlib.sha256((directory / name).read_bytes()).hexdigest()}  {name}\n"
        for name in sorted({*APK_NAMES, METADATA})), encoding="utf-8")


def artifact(directory, tree="d" * 40, source="a" * 40, source_tree="b" * 40, **overrides):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    runtime = {key: "test" for key in ("os", "java", "gradle", "agp", "sdk", "build_tools")}
    details = {"contract": CONTRACT, "android_tree": tree, "source_commit": source, "tree": source_tree,
               "base_commit": None, "pr_number": None, "event": "push", "repository": "owner/repo",
               "run_id": "123", "run_attempt": "1",
               "tests": {key: {"status": "passed", "cases": 1} for key in ("unit", "screenshots", "instrumentation")},
               "variants": {key: {"application_id": "test.app", "version_code": 1, "version_name": "1.0"}
                            for key in ("debug", "release")},
               "toolchain": {"host": runtime, "device": runtime}}
    details.update(overrides)
    for name in APK_NAMES:
        (directory / name).write_bytes(b"synthetic apk " + name.encode())
    (directory / METADATA).write_text(json.dumps(details), encoding="utf-8")
    checksums(directory)
    return details
