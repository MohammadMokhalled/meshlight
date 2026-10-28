# SPDX-License-Identifier: MIT

import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from android_ci import (APK_NAMES, CHECKSUMS, FILES, METADATA, complete, junit, package,
                        plan, reuse, run_device, source_info, stage_host, subtree, validate, verify_avd)
from android_fixtures import artifact, checksums
from ci import metadata, prepare
from version import git


class GitCase(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "--initial-branch=master")
        git(self.repo, "config", "user.name", "Test")
        git(self.repo, "config", "user.email", "ci@example.invalid")
        self.write("VERSION", "0.1.0")
        self.write("west.yml", "manifest: {}")
        self.write("firmware/products.json", '[{"id":"one","board":"test"}]')
        self.initial = self.commit()
        self.write("android-app/README.md", "Android")
        self.write("android-app/app.txt", "app")
        self.origin = self.commit()

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def commit(self):
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", "Test commit")
        return git(self.repo, "rev-parse", "HEAD")

    def info(self, before=None):
        return metadata(self.repo, "refs/heads/master", before=before)


class SourceTests(GitCase):
    def test_metadata_records_subtree_and_android_addition(self):
        info = self.info(self.initial)
        self.assertTrue(info["android"]["changed"])
        self.assertEqual(info["android"]["tree"], git(self.repo, "rev-parse", "HEAD:android-app"))

    def test_firmware_only_push_is_unchanged(self):
        self.write("firmware/source.c", "firmware")
        self.commit()
        self.assertFalse(self.info(self.origin)["android"]["changed"])

    def test_multi_commit_push_checks_entire_push_not_only_last_commit(self):
        self.write("android-app/README.md", "Updated docs")
        self.commit()
        self.write("firmware/source.c", "firmware")
        self.commit()
        self.assertTrue(self.info(self.origin)["android"]["changed"])

    def test_deletion_and_rename_change_android_tree(self):
        (self.repo / "android-app/app.txt").unlink()
        after_delete = self.commit()
        self.assertTrue(self.info(self.origin)["android"]["changed"])
        (self.repo / "android-app/README.md").rename(self.repo / "android-app/RENAMED.md")
        self.commit()
        self.assertTrue(self.info(after_delete)["android"]["changed"])

    def test_missing_push_history_new_branch_and_tag_allow_reuse_lookup(self):
        tree = git(self.repo, "rev-parse", "HEAD^{tree}")
        for before in (None, "0" * 40, "f" * 40):
            with self.subTest(before=before):
                self.assertIsNone(source_info(self.repo, tree, "refs/heads/master", before=before)["changed"])
        self.assertIsNone(source_info(self.repo, tree, "refs/tags/v0.1.0", before=self.initial)["changed"])

    def test_pr_uses_prepared_merge_tree_instead_of_head_or_moving_refs(self):
        git(self.repo, "checkout", "-b", "feature")
        self.write("android-app/README.md", "PR docs")
        head = self.commit()
        git(self.repo, "checkout", "master")
        self.write("android-app/from-base.txt", "base change")
        base = self.commit()
        merged = prepare(self.repo, base, head)
        info = metadata(self.repo, "refs/heads/master", base=base, head=head, pr_number=7)
        self.assertTrue(info["android"]["changed"])
        self.assertEqual(info["android"]["tree"], subtree(self.repo, merged))
        self.assertNotEqual(info["android"]["tree"], subtree(self.repo, head))

    def test_unchanged_pr_does_not_count_android_changes_already_on_base(self):
        git(self.repo, "checkout", "-b", "feature")
        self.write("other.txt", "PR")
        head = self.commit()
        git(self.repo, "checkout", "master")
        self.write("android-app/base.txt", "base")
        base = self.commit()
        merged = prepare(self.repo, base, head)
        self.assertFalse(source_info(self.repo, merged, "refs/heads/master", base=base)["changed"])

    def test_missing_pr_base_is_fatal_and_bad_sha_is_rejected(self):
        tree = git(self.repo, "rev-parse", "HEAD^{tree}")
        for base in ("f" * 40, "master"):
            with self.assertRaises(ValueError):
                source_info(self.repo, tree, "refs/heads/master", base=base)


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / "apks"
        self.details = artifact(self.directory)

    def update(self, **values):
        self.details.update(values)
        (self.directory / METADATA).write_text(json.dumps(self.details), encoding="utf-8")
        checksums(self.directory)

    def test_complete_artifact_retains_app_versions_and_provenance(self):
        self.assertEqual(validate(self.directory, "d" * 40, repository="owner/repo", require_push=True), self.details)

    def test_missing_empty_corrupt_or_unexpected_payload_is_rejected(self):
        for mode in ("missing", "empty", "corrupt", "extra"):
            with self.subTest(mode=mode):
                artifact(self.directory)
                path = self.directory / APK_NAMES[0]
                if mode == "missing":
                    path.unlink()
                elif mode == "extra":
                    (self.directory / "extra").write_text("extra")
                else:
                    path.write_bytes(b"" if mode == "empty" else b"corrupt")
                with self.assertRaises(ValueError):
                    validate(self.directory, "d" * 40)
                if mode == "extra":
                    (self.directory / "extra").unlink()

    def test_duplicate_or_incomplete_checksums_are_rejected(self):
        path = self.directory / CHECKSUMS
        original = path.read_text()
        for content in (original + original, original.splitlines()[0] + "\n"):
            path.write_text(content)
            with self.assertRaises(ValueError):
                validate(self.directory, "d" * 40)

    def test_contract_source_and_repository_mismatches_are_rejected(self):
        for update in ({"contract": 0}, {"android_tree": "e" * 40}, {"repository": "other/repo"}):
            with self.subTest(update=update):
                self.details = artifact(self.directory, **update)
                with self.assertRaises(ValueError):
                    validate(self.directory, "d" * 40, repository="owner/repo")

    def test_reuse_rejects_pr_provenance_and_missing_test_or_toolchain_evidence(self):
        for update in ({"event": "pull_request"}, {"pr_number": 1}, {"tests": {}},
                       {"run_id": None}, {"variants": {}}, {"toolchain": {}}):
            with self.subTest(update=update):
                self.details = artifact(self.directory, **update)
                with self.assertRaises(ValueError):
                    validate(self.directory, "d" * 40, require_push=True)

    def test_package_combines_host_and_device_evidence_without_version_override(self):
        host = self.root / "host"
        host.mkdir()
        for name in APK_NAMES:
            (host / name).write_bytes((self.directory / name).read_bytes())
        (host / "host-validation.json").write_text(json.dumps({
            "tests": {key: self.details["tests"][key] for key in ("unit", "screenshots")},
            "variants": self.details["variants"], "toolchain": self.details["toolchain"]["host"]}))
        device = self.root / "device.json"
        device.write_text(json.dumps({"tests": {"instrumentation": self.details["tests"]["instrumentation"]},
                                      "toolchain": self.details["toolchain"]["device"]}))
        info = {"android": {"tree": "d" * 40}, "source_commit": "a" * 40, "tree": "b" * 40}
        env = {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_EVENT_NAME": "push", "GITHUB_RUN_ID": "456", "GITHUB_RUN_ATTEMPT": "2"}
        with patch.dict(os.environ, env):
            result = package(info, host, device, self.root / "packaged")
        self.assertEqual(result["variants"], self.details["variants"])
        self.assertEqual(result["run_id"], "456")
        self.assertEqual(set(result["tests"]), {"unit", "screenshots", "instrumentation"})


class ReuseTests(GitCase):
    def setUp(self):
        super().setUp()
        self.write("firmware/new.c", "new firmware")
        self.current = self.commit()
        self.data = self.info(self.origin)
        self.directory = self.root / "original"
        self.details = artifact(self.directory, tree=self.data["android"]["tree"], source=self.origin,
                                source_tree=git(self.repo, "rev-parse", f"{self.origin}^{{tree}}"))
        self.output = self.root / "reused"
        self.calls = []

    def run_info(self, identity=10, **overrides):
        result = {"id": identity, "event": "push", "status": "completed", "conclusion": "success",
                  "head_sha": self.current, "repository": {"full_name": "owner/repo"}}
        result.update(overrides)
        return result

    def archive(self, extra=False):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for path in self.directory.iterdir():
                archive.writestr(path.name, path.read_bytes())
            if extra:
                archive.writestr("../escape", b"invalid")
        return stream.getvalue()

    def api(self, endpoint):
        self.calls.append(endpoint)
        if "/workflows/" in endpoint:
            return {"workflow_runs": [self.run_info()]}
        return {"artifacts": [{"id": 50, "name": "android-apks", "expired": False}]}

    def test_matching_ancestor_artifact_is_copied_byte_for_byte(self):
        with patch("android_ci.github_json", side_effect=self.api), patch("android_ci.github_download", return_value=self.archive()):
            self.assertEqual(reuse(self.repo, self.data, self.output, "owner/repo"), self.details)
        for name in FILES:
            self.assertEqual((self.output / name).read_bytes(), (self.directory / name).read_bytes())
        self.assertEqual(self.details["source_commit"], self.origin)
        self.assertNotEqual(self.origin, self.current)

    def test_run_and_artifact_pagination(self):
        def api(endpoint):
            self.calls.append(endpoint)
            second = "page=2" in endpoint
            if "/workflows/" in endpoint:
                runs = [self.run_info()] if second else [self.run_info(event="pull_request")] * 100
                return {"workflow_runs": runs}
            items = [{"id": 50, "name": "android-apks", "expired": False}] if second else [
                {"id": 1, "name": "logs", "expired": False}] * 100
            return {"artifacts": items}
        with patch("android_ci.github_json", side_effect=api), patch("android_ci.github_download", return_value=self.archive()):
            self.assertIsNotNone(reuse(self.repo, self.data, self.output, "owner/repo"))
        self.assertEqual(sum("page=2" in call for call in self.calls), 2)

    def test_untrusted_failed_in_progress_and_nonancestor_runs_are_rejected(self):
        git(self.repo, "checkout", "-b", "unrelated", self.origin)
        self.write("unrelated", "other")
        unrelated = self.commit()
        git(self.repo, "checkout", "master")
        runs = [self.run_info(event="pull_request"), self.run_info(conclusion="failure"),
                self.run_info(status="in_progress"), self.run_info(repository={"full_name": "fork/repo"}),
                self.run_info(head_sha=unrelated), self.run_info(head_sha=self.initial)]
        with patch("android_ci.github_json", return_value={"workflow_runs": runs}), patch("android_ci.github_download") as download:
            self.assertIsNone(reuse(self.repo, self.data, self.output, "owner/repo"))
        download.assert_not_called()

    def test_expired_and_missing_artifacts_are_rejected(self):
        for artifacts in ([], [{"id": 50, "name": "android-apks", "expired": True}]):
            with patch("android_ci.github_json", side_effect=[{"workflow_runs": [self.run_info()]}, {"artifacts": artifacts}]), patch("android_ci.github_download") as download:
                self.assertIsNone(reuse(self.repo, self.data, self.output, "owner/repo"))
            download.assert_not_called()

    def test_corrupt_checksums_and_artifact_digest_are_rejected(self):
        (self.directory / APK_NAMES[0]).write_bytes(b"corrupt")
        with patch("android_ci.github_json", side_effect=self.api), patch("android_ci.github_download", return_value=self.archive()):
            self.assertIsNone(reuse(self.repo, self.data, self.output, "owner/repo"))
        artifact_info = {"id": 50, "name": "android-apks", "expired": False, "digest": "sha256:" + "0" * 64}
        with patch("android_ci.github_json", side_effect=[{"workflow_runs": [self.run_info()]}, {"artifacts": [artifact_info]}]), patch("android_ci.github_download", return_value=self.archive()):
            self.assertIsNone(reuse(self.repo, self.data, self.output, "owner/repo"))

    def test_corrupt_wrong_tree_contract_pr_or_unsafe_archives_are_rejected(self):
        original = self.archive()
        payloads = [b"invalid zip", self.archive(extra=True)]
        for updates in ({"contract": 0}, {"android_tree": "e" * 40}, {"event": "pull_request"}):
            artifact(self.directory, tree=self.data["android"]["tree"], source=self.origin,
                     source_tree=git(self.repo, "rev-parse", f"{self.origin}^{{tree}}"), **updates)
            payloads.append(self.archive())
        artifact(self.directory, tree=self.data["android"]["tree"], source=self.initial,
                 source_tree=git(self.repo, "rev-parse", f"{self.initial}^{{tree}}"))
        payloads.append(self.archive())
        for payload in payloads:
            with self.subTest(payload_size=len(payload)), patch("android_ci.github_json", side_effect=self.api), patch("android_ci.github_download", return_value=payload):
                self.assertIsNone(reuse(self.repo, self.data, self.output, "owner/repo"))
        with patch("android_ci.github_json", side_effect=self.api), patch("android_ci.github_download", return_value=original):
            self.assertIsNotNone(reuse(self.repo, self.data, self.output, "owner/repo"))

    def test_changed_android_always_builds_and_unchanged_pr_skips_lookup(self):
        with patch("android_ci.reuse") as lookup:
            self.data["pr_number"] = 7
            self.assertEqual(plan(self.repo, self.data, self.output, "owner/repo"), "skip")
            self.data["android"]["changed"] = True
            self.assertEqual(plan(self.repo, self.data, self.output, "owner/repo"), "build")
            self.data["pr_number"] = None
            self.assertEqual(plan(self.repo, self.data, self.output, "owner/repo"), "build")
        lookup.assert_not_called()

    def test_unchanged_push_reuses_or_builds_on_missing_or_unavailable_artifacts(self):
        for result, expected in ((None, "build"), (self.details, "reuse")):
            with patch("android_ci.reuse", return_value=result):
                self.assertEqual(plan(self.repo, self.data, self.output, "owner/repo"), expected)
        with patch("android_ci.reuse", side_effect=subprocess.CalledProcessError(1, "gh")):
            self.assertEqual(plan(self.repo, self.data, self.output, "owner/repo"), "build")


class ReportAndGateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_junit_rejects_missing_empty_failed_skipped_and_duplicate_results(self):
        with self.assertRaises(ValueError):
            junit(self.root)
        for body in ('', '<testcase name="one"><failure/></testcase>',
                     '<testcase name="one"><skipped/></testcase>',
                     '<testcase name="one"/><testcase name="one"/>'):
            (self.root / "TEST-suite.xml").write_text(f'<testsuite>{body}</testsuite>')
            with self.assertRaises(ValueError):
                junit(self.root)
        (self.root / "TEST-suite.xml").write_text('<testsuites><testsuite tests="1"><testcase classname="suite" name="one"/></testsuite></testsuites>')
        self.assertEqual(junit(self.root), {"status": "passed", "cases": 1})

    def test_stage_host_checks_lint_coverage_reports_and_both_apk_variants(self):
        build = self.root / "app/build"
        for suite in ("testDebugUnitTest", "validateDebugScreenshotTest"):
            directory = build / f"test-results/{suite}"
            directory.mkdir(parents=True)
            (directory / "TEST-suite.xml").write_text('<testsuite><testcase classname="suite" name="one"/></testsuite>')
        reports = build / "reports"
        reports.mkdir()
        lint = reports / "lint-results-debug.xml"
        lint.write_text('<issues><issue severity="Warning"/></issues>')
        coverage = reports / "jacoco/jacocoDebugReport/jacocoDebugReport.xml"
        coverage.parent.mkdir(parents=True)
        coverage.write_text('<report/>')
        for variant in ("debug", "release"):
            directory = build / f"outputs/apk/{variant}"
            directory.mkdir(parents=True)
            (directory / "app.apk").write_bytes(b"apk")
            (directory / "output-metadata.json").write_text(json.dumps({"applicationId": "test.app", "elements": [
                {"outputFile": "app.apk", "versionName": "1.0", "versionCode": 1}]}))
        output = self.root / "staged"
        with patch("android_ci.toolchain", return_value={"java": "25"}):
            stage_host(self.root, output)
            self.assertTrue(all((output / name).is_file() for name in APK_NAMES))
            lint.write_text('<issues><issue severity="Error"/></issues>')
            with self.assertRaisesRegex(ValueError, "lint"):
                stage_host(self.root, output)
            lint.write_text('<issues/>')
            coverage.unlink()
            with self.assertRaisesRegex(ValueError, "coverage"):
                stage_host(self.root, output)

    def test_device_failure_still_collects_logs(self):
        def command(args, **kwargs):
            if args[0] == "bash":
                raise subprocess.CalledProcessError(1, args)
        with patch("android_ci.subprocess.run", side_effect=command) as run, self.assertRaises(subprocess.CalledProcessError):
            run_device(self.root, self.root / "logs")
        self.assertEqual(run.call_args.args[0], ["adb", "logcat", "-d"])
        self.assertTrue((self.root / "logs/android-logcat.log").exists())

    def test_zero_exit_without_executed_instrumentation_is_rejected_and_logged(self):
        with patch("android_ci.subprocess.run") as run, self.assertRaisesRegex(ValueError, "Missing or empty"):
            run_device(self.root, self.root / "logs")
        self.assertEqual(run.call_args.args[0], ["adb", "logcat", "-d"])
        self.assertTrue((self.root / "logs/android-device.log").exists())

    def test_api_37_avd_requires_correct_target_and_image_before_boot(self):
        config = self.root / "meshlight-api37.avd/config.ini"
        config.parent.mkdir()
        config.write_text("image.sysdir.1=system-images/android-37.0/google_apis/x86_64/\n")
        identity = self.root / "meshlight-api37.ini"
        output = self.root / "avd.json"
        with patch.dict(os.environ, {"ANDROID_HOME": str(self.root / "sdk")}):
            identity.write_text("target=android-0\n")
            with self.assertRaisesRegex(ValueError, "Incorrect Android AVD"):
                verify_avd(self.root, "meshlight-api37", output)
            self.assertEqual(json.loads(output.read_text())["target"], "android-0")
            identity.write_text("target=android-37.0\n")
            self.assertEqual(verify_avd(self.root, "meshlight-api37", output)["target"], "android-37.0")
            config.write_text("image.sysdir.1=system-images/android-36/google_apis/x86_64/\n")
            with self.assertRaisesRegex(ValueError, "Incorrect Android AVD"):
                verify_avd(self.root, "meshlight-api37", output)

    def jobs(self, mode):
        results = {name: {"result": "success"} for name in ("metadata", "build", "tests", "android-plan", "android-host", "android-device", "android-artifacts")}
        results["android-plan"]["outputs"] = {"mode": mode}
        if mode != "build":
            results["android-host"]["result"] = results["android-device"]["result"] = "skipped"
        if mode == "skip":
            results["android-artifacts"]["result"] = "skipped"
        return results

    def test_gate_accepts_only_expected_build_reuse_and_pr_skip_results(self):
        for mode in ("build", "reuse", "skip"):
            complete(self.jobs(mode), is_pr=mode == "skip")
        with self.assertRaises(ValueError):
            complete(self.jobs("skip"))
        for job in self.jobs("build"):
            results = self.jobs("build")
            results[job]["result"] = "cancelled"
            with self.subTest(job=job), self.assertRaises(ValueError):
                complete(results)
        for mode in ("build", "reuse", "skip"):
            results = self.jobs(mode)
            results["android-host"]["result"] = "skipped" if mode == "build" else "failure"
            with self.assertRaises(ValueError):
                complete(results, is_pr=mode != "reuse")


if __name__ == "__main__":
    unittest.main()
