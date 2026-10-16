from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release import parse_version, previous_release


def published(tag, *, draft=False, prerelease=False):
    return {"tagName": tag, "isDraft": draft, "isPrerelease": prerelease}


class ReleaseTest(unittest.TestCase):
    def test_feature_and_patch_versions_increase_android_version_code(self):
        tags = ["0.1-release", "0.2-release", "0.9999-release", "1.0-release", "1.1-release"]
        versions = [parse_version(tag) for tag in tags]
        codes = [feature * 10_000 + patch for feature, patch in versions]
        self.assertEqual(codes, [1, 2, 9999, 10000, 10001])

    def test_rejects_ambiguous_tags_and_android_overflow(self):
        for tag in (
            "v0.1-release", "0.1.0-release", "01.1-release", "0.01-release", "0.1-beta",
            "0.0-release", "0.10000-release", "210000.1-release", "9999999999.0-release",
            "0.1-release\n", "0.1-release;false",
        ):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                parse_version(tag)

    def test_first_release_uses_entire_history(self):
        self.assertEqual(previous_release("0.1-release", []), "")

    def test_previous_release_uses_numeric_order_and_ignores_drafts(self):
        releases = [
            published("0.9-release"), published("0.10-release"),
            published("1.0-release", draft=True), published("1.1-release", prerelease=True),
            published("future-version-scheme"),
        ]
        self.assertEqual(previous_release("0.11-release", releases), "0.10-release")

    def test_rejects_republishing_or_downgrading(self):
        for tag in ("0.9-release", "0.10-release"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                previous_release(tag, [published("0.10-release")])
