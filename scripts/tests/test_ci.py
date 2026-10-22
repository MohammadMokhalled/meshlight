# SPDX-License-Identifier: MIT

import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ci import IMAGE, ROOT, package, products, verify_twister, write_json
from release import publish, validate_packages


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.info = {
            "version": "0.1.0", "channel": "final", "ref": "refs/tags/v0.1.0",
            "source_commit": "a" * 40, "tree": "b" * 40,
            "products": [{"id": "one", "board": "board/one"},
                         {"id": "two", "board": "board/two"}],
            "toolchain_image": IMAGE, "manifest_sha256": "c" * 64,
        }
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "generated").mkdir()
        (self.build / "generated/meshlight_version.h").write_text(
            '#define MESHLIGHT_VERSION_STRING "0.1.0"\n'
            '#define MESHLIGHT_GIT_SHA "' + "a" * 40 + '"\n')
        for ext in ("hex", "bin", "elf"):
            (self.build / f"zephyr/zephyr.{ext}").write_bytes(b"firmware\0" + b"0.1.0\0" + b"a" * 40 + b"\0")
        self.output = self.root / "packages"

    def packages(self):
        return [package(self.build, self.output, p["id"], self.info) for p in self.info["products"]]

    def test_complete_product_packages_and_checksums(self):
        paths = self.packages()
        self.assertEqual(validate_packages(self.output, self.info), paths)
        with zipfile.ZipFile(paths[0]) as archive:
            details = json.loads(archive.read("build-metadata.json"))
            self.assertEqual(details["version"], "0.1.0")
            self.assertEqual(archive.read("LICENSE"), (ROOT / "LICENSE").read_bytes())
            for line in archive.read("SHA256SUMS").decode().splitlines():
                digest, name = line.split("  ", 1)
                self.assertEqual(digest, hashlib.sha256(archive.read(name)).hexdigest())

    def test_missing_or_empty_license_fails_packaging(self):
        with patch("ci.ROOT", self.root):
            with self.assertRaisesRegex(ValueError, "Missing or empty project license"):
                self.packages()
            (self.root / "LICENSE").write_bytes(b"")
            with self.assertRaisesRegex(ValueError, "Missing or empty project license"):
                self.packages()

    def test_missing_license_blocks_publication(self):
        archive = self.packages()[0]
        with zipfile.ZipFile(archive) as bundle:
            payload = {name: bundle.read(name) for name in bundle.namelist() if name != "LICENSE"}
        payload["SHA256SUMS"] = b"".join(
            line for line in payload["SHA256SUMS"].splitlines(keepends=True)
            if not line.rstrip().endswith(b"  LICENSE")
        )
        with zipfile.ZipFile(archive, "w") as bundle:
            for name, data in payload.items():
                bundle.writestr(name, data)
        with patch("release.gh") as mock, self.assertRaisesRegex(ValueError, "package contents"):
            publish("owner/repo", self.info, self.output)
        mock.assert_not_called()

    def test_missing_firmware_fails_packaging(self):
        (self.build / "zephyr/zephyr.bin").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or empty"):
            self.packages()

    def test_header_or_linked_version_mismatch_fails(self):
        (self.build / "zephyr/zephyr.elf").write_bytes(b"different firmware")
        with self.assertRaisesRegex(ValueError, "ELF"):
            self.packages()
        (self.build / "generated/meshlight_version.h").write_text("wrong")
        with self.assertRaisesRegex(ValueError, "header"):
            self.packages()

    def test_version_prefix_is_not_a_matching_linked_version(self):
        (self.build / "zephyr/zephyr.elf").write_bytes(b"0.1.00\0" + b"a" * 40 + b"\0")
        with self.assertRaisesRegex(ValueError, "ELF"):
            self.packages()

    def test_missing_product_blocks_publication(self):
        self.packages()[1].unlink()
        with patch("release.gh") as mock, self.assertRaisesRegex(ValueError, "every product"):
            publish("owner/repo", self.info, self.output)
        mock.assert_not_called()

    def test_corrupt_payload_blocks_publication(self):
        archive = self.packages()[0]
        with zipfile.ZipFile(archive) as bundle:
            payload = {name: bundle.read(name) for name in bundle.namelist()}
        payload[next(n for n in payload if n.endswith(".bin"))] = b"corrupted"
        with zipfile.ZipFile(archive, "w") as bundle:
            for name, data in payload.items():
                bundle.writestr(name, data)
        with self.assertRaisesRegex(ValueError, "Checksum"):
            validate_packages(self.output, self.info)

    def test_product_inventory_is_dynamic_and_rejects_duplicates(self):
        write_json(self.root / "firmware/products.json", self.info["products"])
        self.assertEqual(products(self.root), self.info["products"])
        write_json(self.root / "firmware/products.json", [self.info["products"][0]] * 2)
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            products(self.root)

    def fake_github(self, initial=None, incomplete_upload=False, wrong_tag=False):
        state = {"release": initial, "commands": []}

        def call(*args, **kwargs):
            state["commands"].append(args)
            if args[0] == "api" and "/git/ref/" in args[1]:
                return json.dumps({"object": {"type": "commit", "sha": "f" * 40 if wrong_tag else "a" * 40}})
            if args[0] == "api":
                return json.dumps(state["release"]) if state["release"] else None
            if args[:2] == ("release", "create"):
                state["release"] = {"draft": True, "assets": []}
            elif args[:2] == ("release", "upload"):
                paths = args[args.index("--clobber") + 1:]
                state["release"]["assets"] = [{"name": Path(p).name} for p in paths]
                if incomplete_upload:
                    state["release"]["assets"].pop()
            elif args[:2] == ("release", "download"):
                return json.dumps(self.info)
            return ""
        return state, call

    def test_publish_only_after_complete_draft_upload(self):
        self.packages()
        state, fake = self.fake_github()
        with patch("release.gh", side_effect=fake):
            publish("owner/repo", self.info, self.output)
        commands = [c[:2] for c in state["commands"]]
        self.assertLess(commands.index(("release", "create")), commands.index(("release", "upload")))
        self.assertLess(commands.index(("release", "upload")), commands.index(("release", "edit")))

    def test_failed_upload_does_not_publish(self):
        self.packages()
        state, fake = self.fake_github(incomplete_upload=True)
        with patch("release.gh", side_effect=fake), self.assertRaisesRegex(ValueError, "incomplete"):
            publish("owner/repo", self.info, self.output)
        self.assertFalse(any(c[:2] == ("release", "edit") for c in state["commands"]))

    def test_draft_retry_resumes_without_recreating(self):
        self.packages()
        state, fake = self.fake_github({"draft": True, "assets": []})
        with patch("release.gh", side_effect=fake):
            publish("owner/repo", self.info, self.output)
        self.assertFalse(any(c[:2] == ("release", "create") for c in state["commands"]))

    def test_published_retry_never_writes_assets(self):
        paths = self.packages()
        names = [p.name for p in paths] + ["build-metadata.json", "SHA256SUMS"]
        state, fake = self.fake_github({"draft": False, "assets": [{"name": n} for n in names]})
        with patch("release.gh", side_effect=fake):
            publish("owner/repo", self.info, self.output)
        self.assertFalse(any(c[:2] in (("release", "upload"), ("release", "edit"),
                                      ("release", "create")) for c in state["commands"]))

    def test_incomplete_published_release_is_not_modified(self):
        self.packages()
        state, fake = self.fake_github({"draft": False, "assets": []})
        with patch("release.gh", side_effect=fake), self.assertRaisesRegex(ValueError, "will not be modified"):
            publish("owner/repo", self.info, self.output)
        self.assertFalse(any(c[:2] == ("release", "upload") for c in state["commands"]))

    def test_moved_tag_and_prerelease_cannot_publish(self):
        self.packages()
        _, fake = self.fake_github(wrong_tag=True)
        with patch("release.gh", side_effect=fake), self.assertRaisesRegex(ValueError, "Remote release tag"):
            publish("owner/repo", self.info, self.output)
        self.info["channel"] = "alpha"
        with patch("release.gh") as mock, self.assertRaisesRegex(ValueError, "Only final"):
            publish("owner/repo", self.info, self.output)
        mock.assert_not_called()


class TwisterReportTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.plan = self.root / "plan.json"
        self.report = self.root / "report.json"
        self.suite = {"name": "lighting.test", "status": "passed",
                      "testcases": [{"identifier": "lighting.case", "status": "passed"}]}
        write_json(self.plan, {"testsuites": [self.suite]})

    def verify(self, suites):
        write_json(self.report, {"testsuites": suites})
        verify_twister(self.report, self.plan)

    def test_executed_tests_pass(self):
        self.verify([self.suite])

    def test_missing_or_duplicate_scenario_fails(self):
        for suites in ([], [self.suite, self.suite]):
            with self.assertRaises(ValueError):
                self.verify(suites)

    def test_skipped_failed_or_missing_cases_fail(self):
        for status in ("skipped", "failed", "filtered", None):
            self.suite["testcases"][0]["status"] = status
            with self.subTest(status=status), self.assertRaises(ValueError):
                self.verify([self.suite])
        self.suite["testcases"] = []
        with self.assertRaises(ValueError):
            self.verify([self.suite])

    def test_missing_case_in_successful_scenario_fails(self):
        self.suite["testcases"][0]["identifier"] = "other.case"
        with self.assertRaisesRegex(ValueError, "test cases"):
            self.verify([self.suite])

    def test_duplicate_case_in_successful_scenario_fails(self):
        self.suite["testcases"] *= 2
        with self.assertRaisesRegex(ValueError, "test cases"):
            self.verify([self.suite])


if __name__ == "__main__":
    unittest.main()
