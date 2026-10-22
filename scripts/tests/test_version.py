import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ci import prepare
from release import verify_workflow_identity
from version import git, resolve


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        git(self.repo, "init", "--initial-branch=master")
        git(self.repo, "config", "user.name", "Test")
        git(self.repo, "config", "user.email", "test@example.invalid")
        (self.repo / "VERSION").write_text("0.1.0\n")
        self.serial = 0
        self.commit()

    def commit(self, filename=None, text=None):
        self.serial += 1
        (self.repo / (filename or f"change-{self.serial}")).write_text(text or str(self.serial))
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", f"Commit {self.serial}")
        return git(self.repo, "rev-parse", "HEAD")

    def version(self, ref="master", **kwargs):
        return resolve(self.repo, ref=ref, **kwargs)["version"]

    def test_initial_and_successive_commits(self):
        self.assertEqual(self.version(), "0.1.0-alpha.1")
        self.commit()
        self.assertEqual(self.version(), "0.1.0-alpha.2")
        self.assertEqual(self.version(), self.version())

    def test_release_branch_uses_its_exact_version(self):
        git(self.repo, "checkout", "-b", "release/0.1.0")
        self.assertEqual(self.version("release/0.1.0"), "0.1.0-beta.1")
        self.commit()
        self.assertEqual(self.version("release/0.1.0"), "0.1.0-beta.2")

    def test_minor_cycle_resets_and_beta_counts_from_previous_release(self):
        git(self.repo, "tag", "v0.1.0")
        self.commit()
        self.assertEqual(self.version(), "0.2.0-alpha.1")
        self.commit()
        self.assertEqual(self.version("release/0.2.0"), "0.2.0-beta.2")
        git(self.repo, "tag", "v0.2.0")
        self.commit()
        self.assertEqual(self.version(), "0.3.0-alpha.1")

    def test_patch_tag_does_not_reset_master_cycle(self):
        git(self.repo, "tag", "v0.1.0")
        self.commit()
        git(self.repo, "tag", "v0.1.1")
        self.commit()
        self.assertEqual(self.version(), "0.2.0-alpha.2")
        self.assertEqual(self.version("release/0.1.2"), "0.1.2-beta.1")

    def test_bootstrap_minor_uses_an_existing_preceding_release(self):
        git(self.repo, "tag", "v0.0.1")
        self.commit()
        self.assertEqual(self.version(), "0.1.0-alpha.1")

    def test_maintenance_branch_backmerge_preserves_alpha_progress(self):
        git(self.repo, "tag", "v0.1.0")
        git(self.repo, "checkout", "-b", "release/0.1.1")
        self.commit()
        git(self.repo, "tag", "v0.1.1")
        git(self.repo, "checkout", "master")
        self.commit()
        git(self.repo, "merge", "--no-ff", "release/0.1.1", "-m", "Backmerge")
        self.assertEqual(self.version(), "0.2.0-alpha.3")

    def test_lightweight_and_annotated_final_tags(self):
        git(self.repo, "tag", "v0.1.0")
        self.assertEqual(self.version("refs/tags/v0.1.0"), "0.1.0")
        self.commit()
        git(self.repo, "tag", "-a", "v0.2.0", "-m", "Release")
        self.assertEqual(self.version("refs/tags/v0.2.0"), "0.2.0")

    def test_tagging_does_not_change_branch_version(self):
        alpha = self.version()
        beta = self.version("release/0.1.0")
        git(self.repo, "tag", "v0.1.0")
        self.assertEqual(self.version(), alpha)
        self.assertEqual(self.version("release/0.1.0"), beta)
        self.assertEqual(self.version("refs/tags/v0.1.0"), "0.1.0")

    def test_unrelated_tags_and_branches_do_not_change_master(self):
        git(self.repo, "checkout", "-b", "release/9.0.0")
        self.commit()
        git(self.repo, "tag", "v9.0.0")
        git(self.repo, "checkout", "master")
        self.assertEqual(self.version(), "0.1.0-alpha.1")
        git(self.repo, "tag", "v99.0.0-alpha.1")
        git(self.repo, "tag", "unrelated")
        self.assertEqual(self.version(), "0.1.0-alpha.1")

    def test_strict_branch_and_tag_validation(self):
        for ref in ("release/1.2", "release/01.2.3", "release/1.2.3-more",
                    "refs/tags/v1.2.3-beta.1", "refs/tags/v01.2.3"):
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                self.version(ref, ci=True)
        with self.assertRaises(ValueError):
            self.version("feature/test", ci=True)

    def test_tag_must_point_to_requested_commit(self):
        git(self.repo, "tag", "v0.1.0")
        self.commit()
        with self.assertRaises(ValueError):
            self.version("refs/tags/v0.1.0")

    def test_publication_peels_annotated_event_tag_and_checks_tree(self):
        git(self.repo, "tag", "-a", "v0.1.0", "-m", "Release")
        info = {"source_commit": git(self.repo, "rev-parse", "HEAD"),
                "tree": git(self.repo, "rev-parse", "HEAD^{tree}"),
                "ref": "refs/tags/v0.1.0"}
        tag_object = git(self.repo, "rev-parse", "v0.1.0")
        verify_workflow_identity(self.repo, info, tag_object, info["ref"])
        with self.assertRaisesRegex(ValueError, "Workflow source/ref"):
            verify_workflow_identity(self.repo, info, tag_object, "refs/heads/master")
        info["tree"] = "f" * 40
        with self.assertRaisesRegex(ValueError, "Workflow source/ref"):
            verify_workflow_identity(self.repo, info, tag_object, info["ref"])

    def test_shallow_history_is_rejected(self):
        self.commit()
        with tempfile.TemporaryDirectory() as shallow:
            subprocess.run(["git", "clone", "--depth=1", self.repo.as_uri(), shallow],
                           capture_output=True, check=True)
            with self.assertRaisesRegex(ValueError, "Full Git history"):
                resolve(shallow, "master", ci=True)

    def test_local_feature_and_detached_builds(self):
        self.assertEqual(self.version("feature/test"), "0.1.0-dev.1+g" +
                         git(self.repo, "rev-parse", "--short=12", "HEAD"))
        git(self.repo, "checkout", "--detach")
        self.assertEqual(resolve(self.repo)["channel"], "dev")
        git(self.repo, "tag", "v0.1.0")
        self.assertEqual(resolve(self.repo)["version"], "0.1.0")

    def test_exact_pr_merge_includes_both_sides(self):
        git(self.repo, "checkout", "-b", "feature/test")
        head = self.commit("pr-file", "PR")
        git(self.repo, "checkout", "master")
        base = self.commit("master-file", "master")
        # No remote or stored credentials are needed: checkout fetched both parents.
        tree = prepare(self.repo, base, head)
        self.assertEqual((self.repo / "pr-file").read_text(), "PR")
        self.assertEqual((self.repo / "master-file").read_text(), "master")
        self.assertEqual(git(self.repo, "rev-parse", "HEAD"), base)
        self.assertEqual(tree, git(self.repo, "write-tree"))
        self.assertEqual(self.version(commit=base, pr_number=42, pr_head=head),
                         f"0.1.0-alpha.3.pr.42.g{head[:12]}")

    def test_merge_conflict_fails(self):
        self.commit("shared", "initial\n")
        git(self.repo, "checkout", "-b", "feature/test")
        head = self.commit("shared", "PR\n")
        git(self.repo, "checkout", "master")
        base = self.commit("shared", "master\n")
        with self.assertRaises(ValueError):
            prepare(self.repo, base, head)


if __name__ == "__main__":
    unittest.main()
