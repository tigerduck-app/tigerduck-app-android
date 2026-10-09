"""Tests for release_notes.py, against a checkout built in a temporary folder.

    python3 -m unittest discover -s tools/github
"""

import json
import tempfile
import unittest
from pathlib import Path

import release_notes

REPO = "owner/app"


class ReleaseNotesTest(unittest.TestCase):

    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.write("app/build.gradle.kts", 'android {\n    versionCode = 29\n    versionName = "2.3.1"\n}\n')
        self.write("wear/build.gradle.kts", "android {\n    versionCode = 10029\n}\n")
        self.whatsnew({
            "zh-Hant": {"items": [{"title": "通知優化", "body": "…"}, {"title": "修正文字溢出", "body": "…"}]},
            "en": {"items": [{"title": "Better notifications", "body": "…"},
                             {"title": "Text overflow fixed", "body": "…"}]},
        })

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def whatsnew(self, entry, code="29"):
        self.write(str(release_notes.WHATSNEW), json.dumps({code: entry}, ensure_ascii=False))

    def render(self, template, tags=("v2.2.0", "v2.3.0")):
        return release_notes.render(template, self.root, REPO, list(tags))

    def test_the_repo_template_renders_for_the_version_in_the_code(self):
        template = (release_notes.REPO_ROOT / release_notes.TEMPLATE).read_text(encoding="utf-8")
        notes = self.render(template)

        self.assertNotIn("{{", notes)
        self.assertTrue(notes.startswith(
            "新功能 / What's new:\n"
            "- 通知優化\n  Better notifications\n"
            "- 修正文字溢出\n  Text overflow fixed\n\n"
        ))
        self.assertIn("- **手機：`versionCode` 29, `versionName` 2.3.1**\n"
                      "  **Phone: `versionCode` 29, `versionName` 2.3.1**", notes)
        self.assertIn("- **手錶：`versionCode` 10029, `versionName` 2.3.1**\n"
                      "  **Watch: `versionCode` 10029, `versionName` 2.3.1**", notes)
        self.assertIn("https://github.com/owner/app/compare/v2.3.0...v2.3.1", notes)

    def test_highlights_stand_in_for_items(self):
        self.whatsnew({"zh-Hant": {"highlights": ["一"]}, "en": {"highlights": ["One"]}})

        self.assertEqual("- 一\n  One", self.render("{{WHATS_NEW}}"))

    def test_spacing_inside_a_placeholder_is_allowed(self):
        self.assertEqual("2.3.1", self.render("{{ VERSION_NAME }}"))

    def test_the_compare_link_starts_at_the_highest_release_below_this_one(self):
        tags = ["v2.10.0", "v2.3.1", "v2.2.9", "v2.3.0", "v1.9.9", "v2.3.0-rc1", "vnext"]

        self.assertEqual("https://github.com/owner/app/compare/v2.3.0...v2.3.1",
                         self.render("{{COMPARE_URL}}", tags))

    def test_with_no_earlier_release_the_link_lists_the_commits(self):
        self.assertEqual("https://github.com/owner/app/commits/v2.3.1",
                         self.render("{{COMPARE_URL}}", tags=["v2.3.1", "v3.0.0"]))

    def test_a_version_with_no_whats_new_entry_is_refused(self):
        self.whatsnew({"zh-Hant": {"items": []}, "en": {"items": []}}, code="28")

        with self.assertRaisesRegex(release_notes.NotesError, "no entry for versionCode 29"):
            self.render("{{WHATS_NEW}}")

    def test_a_language_with_no_items_is_refused(self):
        self.whatsnew({"zh-Hant": {"items": [{"title": "一", "body": ""}]}, "en": {}})

        with self.assertRaisesRegex(release_notes.NotesError, "no en items"):
            self.render("{{WHATS_NEW}}")

    def test_languages_listing_different_numbers_of_items_are_refused(self):
        self.whatsnew({
            "zh-Hant": {"items": [{"title": "一", "body": ""}, {"title": "二", "body": ""}]},
            "en": {"items": [{"title": "One", "body": ""}]},
        })

        with self.assertRaisesRegex(release_notes.NotesError, "2 zh-Hant items but 1 en items"):
            self.render("{{WHATS_NEW}}")

    def test_a_placeholder_the_script_does_not_fill_is_refused(self):
        with self.assertRaisesRegex(release_notes.NotesError, r"\{\{PHONE_NAME\}\}"):
            self.render("{{PHONE_NAME}}")

    def test_a_gradle_file_with_no_version_is_refused(self):
        self.write("wear/build.gradle.kts", "android {}\n")

        with self.assertRaisesRegex(release_notes.NotesError, "No versionCode in wear"):
            self.render("{{WATCH_CODE}}")


if __name__ == "__main__":
    unittest.main()
