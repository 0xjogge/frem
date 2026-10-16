import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "release-notes.mjs"


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=os.environ.get("RUNNER_TEMP", "/tmp/opencode"))
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "-b", "main")
        self.commit("feat: initial companion")
        self.git("tag", "0.1-release")

    def git(self, *args):
        return subprocess.check_output(
            [
                "git", "-c", "user.name=Frem Contributor",
                "-c", "user.email=123+contributor@users.noreply.github.com", *args,
            ],
            cwd=self.repo,
            text=True,
        )

    def commit(self, message):
        with (self.repo / "changes.txt").open("a") as output:
            output.write(message + "\n")
        self.git("add", "changes.txt")
        self.git("commit", "-m", message)

    def notes(self, *args):
        subprocess.run(["node", str(SCRIPT), *args], cwd=self.repo, check=True, capture_output=True)
        return (self.repo / "build/release-notes.md").read_text()

    def test_first_release_includes_initial_commit_and_contributor(self):
        notes = self.notes("0.1-release", "HEAD")
        self.assertIn("## 0.1-release", notes)
        self.assertIn("🚀 Enhancements", notes)
        self.assertIn("Initial companion", notes)
        self.assertIn("[@contributor](https://github.com/contributor)", notes)
        self.assertNotIn("noreply.github.com", notes)

    def test_two_part_tag_comparison_groups_changes_and_links_prs(self):
        self.commit("fix(health): retry imports (#12)")
        self.commit("feat!: change data format")
        notes = self.notes("1.0-release", "HEAD", "0.1-release")
        self.assertIn("compare/0.1-release...1.0-release", notes)
        self.assertIn("🩹 Fixes", notes)
        self.assertIn("pull/12", notes)
        self.assertIn("Breaking Changes", notes)
        self.assertNotIn("Initial companion", notes)
