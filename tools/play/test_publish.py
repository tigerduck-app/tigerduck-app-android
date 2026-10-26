"""Tests for publish.py, against a fake Google Play.

Nothing here signs in or reaches the network: `sign_in` hands back a FakePlay
that records every call and answers the way the Play Developer API does.

    python3 -m unittest discover -s tools/play
"""

import argparse
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import publish

PACKAGE = "org.example.app"
EDIT = "edit-1"
NOTES = [
    {"language": "zh-TW", "text": "• 修正", "source": "test"},
    {"language": "en-US", "text": "• Fixes", "source": "test"},
]


class FakePlay(publish.Play):
    """Records each call as (method, path, body); answers like Play would."""

    def __init__(self, existing=(), uploaded_codes=None, fail=None, tracks=()):
        super().__init__("token", PACKAGE)
        self.calls = []
        self.existing = list(existing)
        # bundle bytes -> the version code Play reads from them
        self.uploaded_codes = uploaded_codes or {}
        # (method, path) -> PlayError raised for that call
        self.fail = dict(fail or {})
        self.tracks = list(tracks)

    def call(self, method, url, *, json_body=None, data=None, content_type=None, timeout=120):
        upload = url.startswith(publish.UPLOAD_API)
        path = url.split(f"/{PACKAGE}/", 1)[1]
        self.calls.append((method, path, json_body))
        if (method, path) in self.fail:
            raise self.fail[(method, path)]
        if (method, path) == ("POST", "edits"):
            return {"id": EDIT}
        if (method, path) == ("GET", f"edits/{EDIT}/bundles"):
            return {"bundles": [{"versionCode": c} for c in self.existing]}
        if method == "POST" and upload:
            return {"versionCode": self.uploaded_codes[data]}
        if (method, path) == ("GET", f"edits/{EDIT}/tracks"):
            return {"tracks": [{"track": t} for t in self.tracks]}
        return {}

    def paths(self, method):
        return [path for m, path, _ in self.calls if m == method]

    def track_bodies(self):
        return {path.rsplit("/", 1)[1]: body for m, path, body in self.calls
                if m == "PUT" and "/tracks/" in path}


class PublishTest(unittest.TestCase):

    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.phone_aab = Path(scratch.name) / "phone.aab"
        self.watch_aab = Path(scratch.name) / "watch.aab"
        self.phone_aab.write_bytes(b"phone bundle")
        self.watch_aab.write_bytes(b"watch bundle")
        notes = mock.patch.object(publish, "release_notes", return_value=NOTES)
        notes.start()
        self.addCleanup(notes.stop)

    def fake(self, **kwargs):
        kwargs.setdefault("uploaded_codes", {b"phone bundle": 29, b"watch bundle": 10029})
        return FakePlay(**kwargs)

    def publish(self, play, targets="phone:internal,phone:public,watch:internal,watch:public",
                rollout=0.1, dry_run=False):
        args = argparse.Namespace(
            package=PACKAGE, key_file=None, version_name="2.3.1",
            phone_code=29, watch_code=10029,
            phone_aab=str(self.phone_aab), watch_aab=str(self.watch_aab),
            targets=targets, rollout=rollout, dry_run=dry_run,
        )
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(publish, "sign_in", return_value=play) as sign_in, \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                publish.publish(args)
            finally:
                self.signed_in = sign_in.called
                self.out, self.err = out.getvalue(), err.getvalue()

    def assert_exits(self, play, **kwargs):
        with self.assertRaises(SystemExit) as raised:
            self.publish(play, **kwargs)
        self.assertEqual(1, raised.exception.code)

    # --- what is sent ---------------------------------------------------

    def test_every_target_lands_in_one_edit_committed_once(self):
        play = self.fake()
        self.publish(play)

        self.assertEqual(["edits"], [p for p in play.paths("POST") if p == "edits"])
        self.assertEqual(["edits/edit-1:commit"], [p for p in play.paths("POST") if "commit" in p])
        self.assertEqual(
            {"internal", "production", "wear%3Ainternal", "wear%3Aproduction"},
            set(play.track_bodies()),
        )
        self.assertEqual([], play.paths("DELETE"))

    def test_internal_goes_to_everyone_and_production_starts_staged(self):
        play = self.fake()
        self.publish(play, rollout=0.1)
        bodies = play.track_bodies()

        for track, code in [("internal", "29"), ("wear%3Ainternal", "10029")]:
            release = bodies[track]["releases"][0]
            self.assertEqual("completed", release["status"], track)
            self.assertNotIn("userFraction", release, track)
            self.assertEqual([code], release["versionCodes"], track)
        for track, code in [("production", "29"), ("wear%3Aproduction", "10029")]:
            release = bodies[track]["releases"][0]
            self.assertEqual("inProgress", release["status"], track)
            self.assertEqual(0.1, release["userFraction"], track)
            self.assertEqual([code], release["versionCodes"], track)

    def test_a_full_rollout_completes_production(self):
        play = self.fake()
        self.publish(play, rollout=1.0)
        release = play.track_bodies()["production"]["releases"][0]

        self.assertEqual("completed", release["status"])
        self.assertNotIn("userFraction", release)

    def test_each_release_carries_its_name_and_notes(self):
        play = self.fake()
        self.publish(play)

        for track, body in play.track_bodies().items():
            release = body["releases"][0]
            self.assertEqual("2.3.1", release["name"], track)
            self.assertEqual(
                [{"language": "zh-TW", "text": "• 修正"}, {"language": "en-US", "text": "• Fixes"}],
                release["releaseNotes"], track,
            )

    def test_the_wear_track_name_is_sent_whole_in_the_body(self):
        play = self.fake()
        self.publish(play, targets="watch:public")

        self.assertEqual("wear:production", play.track_bodies()["wear%3Aproduction"]["track"])

    def test_only_the_ticked_targets_are_uploaded_and_assigned(self):
        play = self.fake()
        self.publish(play, targets="phone:internal")

        uploads = [p for p in play.paths("POST") if "uploadType=media" in p]
        self.assertEqual(1, len(uploads))
        self.assertEqual({"internal"}, set(play.track_bodies()))

    def test_a_bundle_play_already_has_is_assigned_but_not_uploaded_again(self):
        play = self.fake(existing=[29])
        self.publish(play)

        uploads = [p for p in play.paths("POST") if "uploadType=media" in p]
        self.assertEqual(1, len(uploads))  # the watch bundle only
        self.assertIn("phone: Play already has version code 29", self.out)
        self.assertEqual(4, len(play.track_bodies()))

    # --- what stops it --------------------------------------------------

    def test_a_bundle_with_the_wrong_version_code_stops_before_any_track(self):
        play = self.fake(uploaded_codes={b"phone bundle": 28, b"watch bundle": 10029})
        self.assert_exits(play)

        self.assertEqual({}, play.track_bodies())
        self.assertEqual([], [p for p in play.paths("POST") if "commit" in p])
        self.assertEqual([f"edits/{EDIT}"], play.paths("DELETE"))

    def test_a_refused_track_lists_the_tracks_play_has_and_discards_the_edit(self):
        play = self.fake(
            fail={("PUT", f"edits/{EDIT}/tracks/wear%3Aproduction"):
                  publish.PlayError(400, "Track not found")},
            tracks=["internal", "production"],
        )
        self.assert_exits(play, targets="watch:public")

        self.assertIn("Tracks Play reports: internal, production", self.err)
        self.assertEqual([f"edits/{EDIT}"], play.paths("DELETE"))

    def test_a_commit_play_wont_send_for_review_is_saved_for_a_click(self):
        play = self.fake(fail={
            ("POST", f"edits/{EDIT}:commit"): publish.PlayError(
                400, "Changes cannot be sent for review automatically. Please set the query "
                     "parameter changesNotSentForReview to true."),
        })
        self.publish(play)

        commits = [p for p in play.paths("POST") if "commit" in p]
        self.assertEqual(
            [f"edits/{EDIT}:commit", f"edits/{EDIT}:commit?changesNotSentForReview=true"], commits)
        self.assertIn("::warning::", self.out)
        self.assertEqual([], play.paths("DELETE"))

    def test_any_other_commit_failure_is_not_retried_and_discards_the_edit(self):
        play = self.fake(fail={
            ("POST", f"edits/{EDIT}:commit"): publish.PlayError(403, "The caller does not have permission"),
        })
        self.assert_exits(play)

        self.assertEqual([f"edits/{EDIT}:commit"], [p for p in play.paths("POST") if "commit" in p])
        self.assertEqual([f"edits/{EDIT}"], play.paths("DELETE"))

    def test_a_dry_run_signs_in_to_nothing(self):
        play = self.fake()
        self.publish(play, dry_run=True)

        self.assertFalse(self.signed_in)
        self.assertEqual([], play.calls)
        self.assertIn("production (inProgress to 10% of users)", self.out)

    def test_no_targets_signs_in_to_nothing(self):
        play = self.fake()
        self.publish(play, targets="")

        self.assertFalse(self.signed_in)
        self.assertEqual([], play.calls)

    def test_an_unknown_target_is_refused_before_signing_in(self):
        play = self.fake()
        self.assert_exits(play, targets="phone:beta")

        self.assertFalse(self.signed_in)

    def test_a_rollout_outside_zero_to_one_is_refused_before_signing_in(self):
        for rollout in (0, -0.1, 10):
            play = self.fake()
            self.assert_exits(play, rollout=rollout)
            self.assertFalse(self.signed_in, rollout)

    def test_a_missing_bundle_is_refused_before_signing_in(self):
        self.watch_aab.unlink()
        play = self.fake()
        self.assert_exits(play, targets="watch:internal")

        self.assertFalse(self.signed_in)


class ReleaseNotesTest(unittest.TestCase):

    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.whatsnew = self.root / "whatsnew.json"
        self.changelogs = self.root / "changelogs"
        for name, value in [("ROOT", self.root), ("WHATSNEW_PATH", self.whatsnew),
                            ("CHANGELOGS_DIR", self.changelogs)]:
            patch = mock.patch.object(publish, name, value)
            patch.start()
            self.addCleanup(patch.stop)

    def write_whatsnew(self, entry):
        self.whatsnew.write_text(json.dumps({"29": entry}), encoding="utf-8")

    def notes(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            try:
                return {n["language"]: n["text"] for n in publish.release_notes(29)}
            finally:
                self.err = err.getvalue()

    def test_items_join_title_and_body_with_each_languages_separator(self):
        self.write_whatsnew({
            "zh-Hant": {"items": [{"title": "通知", "body": "更清楚。"}]},
            "en": {"items": [{"title": "Notifications", "body": "Clearer."}]},
        })

        self.assertEqual({"zh-TW": "• 通知：更清楚。", "en-US": "• Notifications: Clearer."},
                         self.notes())

    def test_highlights_are_used_when_there_are_no_items(self):
        self.write_whatsnew({"zh-Hant": {"highlights": ["一", "二"]}, "en": {"highlights": ["One"]}})

        self.assertEqual({"zh-TW": "• 一\n• 二", "en-US": "• One"}, self.notes())

    def test_a_changelog_file_wins_over_whats_new(self):
        self.write_whatsnew({
            "zh-Hant": {"highlights": ["應用程式內"]},
            "en": {"highlights": ["In the app"]},
        })
        override = self.changelogs / "en-US" / "changelogs" / "29.txt"
        override.parent.mkdir(parents=True)
        override.write_text("For Play only\n", encoding="utf-8")

        self.assertEqual({"zh-TW": "• 應用程式內", "en-US": "For Play only"}, self.notes())

    def test_notes_over_plays_limit_stop_the_release(self):
        self.write_whatsnew({
            "zh-Hant": {"highlights": ["短"]},
            "en": {"highlights": ["x" * publish.NOTES_LIMIT]},
        })

        with self.assertRaises(SystemExit):
            self.notes()
        self.assertIn("en-US release notes are 502 characters", self.err)

    def test_a_language_with_no_notes_stops_the_release(self):
        self.write_whatsnew({"en": {"highlights": ["Only English"]}})

        with self.assertRaises(SystemExit):
            self.notes()
        self.assertIn("No zh-TW release notes", self.err)


if __name__ == "__main__":
    unittest.main()
