#!/usr/bin/env python3
"""Upload the phone and watch bundles to Google Play and send them for review.

Talks to the Google Play Developer API (v3) directly, with nothing but the
standard library and the `openssl` binary, so the release job installs no
packages. Everything lands in one edit, committed once at the end: either every
selected track is updated or none is.

Tracks: the phone goes to `internal` / `production`, the watch to
`wear:internal` / `wear:production`. Play has required Wear OS releases to use
the dedicated form-factor tracks since September 2023.

Release notes come from `app/src/main/assets/whatsnew.json`, the entry for the
phone versionCode, unless a hand-written
`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` exists, which
wins (F-Droid reads changelogs from the same path). Play caps each language at
500 characters, so the notes are checked before anything is uploaded.

Safe to re-run: a bundle whose version code Play already has is not uploaded
again, only assigned to the tracks.

Subcommands:
  notes     print the release notes and check their length; no network
  check     sign in and list the app's tracks, changing nothing
  publish   upload, assign to tracks and commit

`check` and `publish` read the service-account key from the
PLAY_SERVICE_ACCOUNT_JSON environment variable, or from --key-file.
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WHATSNEW_PATH = ROOT / "app" / "src" / "main" / "assets" / "whatsnew.json"
CHANGELOGS_DIR = ROOT / "fastlane" / "metadata" / "android"

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"

NOTES_LIMIT = 500

# Play listing locale -> whatsnew.json language key, and the separator that
# joins an item's title to its body in that language.
NOTE_LOCALES = {
    "zh-TW": ("zh-Hant", "："),
    "en-US": ("en", ": "),
}

# (artifact, audience) -> Play track.
TRACKS = {
    ("phone", "internal"): "internal",
    ("phone", "public"): "production",
    ("watch", "internal"): "wear:internal",
    ("watch", "public"): "wear:production",
}


def fail(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


# --- Release notes ---------------------------------------------------------


def whatsnew_text(entry: dict, separator: str) -> str:
    if entry.get("items"):
        return "\n".join(
            f"• {item['title']}{separator}{item['body']}" for item in entry["items"]
        )
    if entry.get("highlights"):
        return "\n".join(f"• {line}" for line in entry["highlights"])
    return ""


def release_notes(version_code: int) -> list:
    whatsnew = json.loads(WHATSNEW_PATH.read_text(encoding="utf-8"))
    version_entry = whatsnew.get(str(version_code), {})
    notes = []
    problems = []
    for locale, (language, separator) in NOTE_LOCALES.items():
        override = CHANGELOGS_DIR / locale / "changelogs" / f"{version_code}.txt"
        if override.exists():
            text = override.read_text(encoding="utf-8").strip()
            source = str(override.relative_to(ROOT))
        else:
            text = whatsnew_text(version_entry.get(language, {}), separator)
            source = f"{WHATSNEW_PATH.relative_to(ROOT)} [{version_code}][{language}]"
        if not text:
            problems.append(f"No {locale} release notes: nothing in {source}.")
        elif len(text) > NOTES_LIMIT:
            problems.append(
                f"{locale} release notes are {len(text)} characters, over Play's "
                f"{NOTES_LIMIT}. Shorten {source}, or write "
                f"{override.relative_to(ROOT)} to use instead."
            )
        notes.append({"language": locale, "text": text, "source": source})
    if problems:
        for problem in problems:
            print(f"::error::{problem}", file=sys.stderr)
        sys.exit(1)
    return notes


def print_notes(notes: list) -> None:
    for note in notes:
        print(f"--- {note['language']} ({len(note['text'])} characters, from {note['source']})")
        print(note["text"])


# --- Google Play Developer API --------------------------------------------


def b64url(data: bytes) -> bytes:
    return base64.urlsafe_b64encode(data).rstrip(b"=")


def access_token(service_account: dict) -> str:
    """Trade a service-account JWT, signed with openssl, for an access token."""
    token_uri = service_account.get("token_uri", "https://oauth2.googleapis.com/token")
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": service_account["client_email"],
        "scope": SCOPE,
        "aud": token_uri,
        "iat": now,
        "exp": now + 3600,
    }).encode())
    signing_input = header + b"." + claims
    with tempfile.TemporaryDirectory() as scratch:
        key_path = Path(scratch) / "key.pem"
        key_path.touch(mode=0o600)
        key_path.write_text(service_account["private_key"])
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(key_path)],
            input=signing_input, capture_output=True, check=True,
        ).stdout
    assertion = (signing_input + b"." + b64url(signature)).decode()
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode()
    request = urllib.request.Request(token_uri, data=body, method="POST")
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)["access_token"]


class PlayError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(f"HTTP {status}: {message}")
        self.status = status
        self.message = message


class Play:
    def __init__(self, token: str, package: str):
        self.token = token
        self.package = package

    def call(self, method: str, url: str, *, json_body=None, data=None,
             content_type=None, timeout=120) -> dict:
        headers = {"Authorization": f"Bearer {self.token}"}
        if json_body is not None:
            data = json.dumps(json_body).encode()
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                payload = response.read()
        except urllib.error.HTTPError as error:
            raw = error.read().decode(errors="replace")
            try:
                message = json.loads(raw)["error"]["message"]
            except (ValueError, KeyError, TypeError):
                message = raw
            raise PlayError(error.code, message) from None
        return json.loads(payload) if payload else {}

    def url(self, path: str, base: str = API) -> str:
        return f"{base}/{self.package}/{path}"


def sign_in(args: argparse.Namespace) -> "Play":
    if args.key_file:
        raw_account = Path(args.key_file).expanduser().read_text()
    else:
        raw_account = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw_account:
        fail("No service-account key: set PLAY_SERVICE_ACCOUNT_JSON or pass --key-file.")
    try:
        account = json.loads(raw_account)
    except ValueError:
        fail("The service-account key is not JSON. PLAY_SERVICE_ACCOUNT_JSON takes the key "
             "file's contents as they are, not base64-encoded.")
    try:
        token = access_token(account)
    except urllib.error.HTTPError as error:
        fail(f"Google refused the service-account key ({error.code}): "
             f"{error.read().decode(errors='replace')}. Was the key deleted or the account disabled?")
    return Play(token, args.package)


def check(args: argparse.Namespace) -> None:
    """Prove the key works and show what Play has, through an edit that is thrown away."""
    play = sign_in(args)
    try:
        edit_id = play.call("POST", play.url("edits"), json_body={})["id"]
    except PlayError as error:
        hint = {
            401: "The key was not accepted.",
            403: "The service account has no access to this app. Invite its email in "
                 "Play Console > Users and permissions, with permissions on this app "
                 "(new permissions can take a while to apply).",
            404: "Play does not know this package, or the account cannot see it.",
        }.get(error.status, "")
        fail(f"Google Play: {error}. {hint}".strip())
    try:
        tracks = play.call("GET", play.url(f"edits/{edit_id}/tracks")).get("tracks", [])
        bundles = play.call("GET", play.url(f"edits/{edit_id}/bundles")).get("bundles", [])
    finally:
        play.call("DELETE", play.url(f"edits/{edit_id}"))
    print(f"Signed in; {args.package} is reachable.\n\nTracks:")
    for track in tracks:
        releases = track.get("releases", [])
        summary = "; ".join(
            f"{r.get('name', '?')} {','.join(r.get('versionCodes', []))} {r.get('status', '')}"
            + (f" {r['userFraction']:.0%}" if "userFraction" in r else "")
            for r in releases
        ) or "no releases"
        print(f"  {track['track']}: {summary}")
    missing = [t for t in TRACKS.values() if t not in {x["track"] for x in tracks}]
    if missing:
        print("\nNot listed by Play: " + ", ".join(missing)
              + ". A release to these will fail until they exist in Play Console.")
    codes = sorted(int(b["versionCode"]) for b in bundles)
    print(f"\nNewest bundle version codes on Play: {', '.join(map(str, codes[-6:])) or 'none'}")


def publish(args: argparse.Namespace) -> None:
    targets = [tuple(t.split(":", 1)) for t in args.targets.split(",") if t]
    for target in targets:
        if target not in TRACKS:
            fail(f"Unknown target {':'.join(target)}; expected one of "
                 + ", ".join(f"{a}:{b}" for a, b in TRACKS))
    if not targets:
        print("No Play targets selected; nothing to upload.")
        return
    if not 0 < args.rollout <= 1:
        fail(f"--rollout must be in (0, 1], got {args.rollout}")

    notes = release_notes(args.phone_code)
    print_notes(notes)
    play_notes = [{"language": n["language"], "text": n["text"]} for n in notes]

    bundles = {
        "phone": (Path(args.phone_aab), args.phone_code),
        "watch": (Path(args.watch_aab), args.watch_code),
    }
    needed = sorted({artifact for artifact, _ in targets})
    for artifact in needed:
        path, _ = bundles[artifact]
        if not path.is_file():
            fail(f"{artifact} bundle not found at {path}")

    print("\nPlan:")
    for artifact, audience in targets:
        _, code = bundles[artifact]
        status = "completed" if audience == "internal" or args.rollout >= 1 else "inProgress"
        share = "" if status == "completed" else f" to {args.rollout:.0%} of users"
        print(f"  {artifact} {code} -> {TRACKS[(artifact, audience)]} ({status}{share})")
    if args.dry_run:
        print("\nDry run: nothing sent to Play.")
        return

    play = sign_in(args)

    edit_id = play.call("POST", play.url("edits"), json_body={})["id"]
    committed = False
    try:
        existing = {
            int(b["versionCode"])
            for b in play.call("GET", play.url(f"edits/{edit_id}/bundles")).get("bundles", [])
        }
        for artifact in needed:
            path, code = bundles[artifact]
            if code in existing:
                print(f"{artifact}: Play already has version code {code}; not uploading it again.")
                continue
            print(f"{artifact}: uploading {path.name} ({path.stat().st_size // 1024} KiB)...")
            uploaded = play.call(
                "POST",
                play.url(f"edits/{edit_id}/bundles", UPLOAD_API) + "?uploadType=media",
                data=path.read_bytes(),
                content_type="application/octet-stream",
                timeout=900,
            )
            if int(uploaded["versionCode"]) != code:
                fail(f"{artifact}: uploaded bundle has version code {uploaded['versionCode']}, "
                     f"expected {code}. Wrong file?")

        for artifact, audience in targets:
            _, code = bundles[artifact]
            track = TRACKS[(artifact, audience)]
            release = {
                "name": args.version_name,
                "versionCodes": [str(code)],
                "releaseNotes": play_notes,
            }
            if audience == "internal" or args.rollout >= 1:
                release["status"] = "completed"
            else:
                release["status"] = "inProgress"
                release["userFraction"] = args.rollout
            # Encoded: a bare "wear:production" at the end of the path reads
            # as a custom method (`tracks/wear` + `:production`), the way
            # `edits/{id}:commit` does.
            track_path = urllib.parse.quote(track, safe="")
            try:
                play.call("PUT", play.url(f"edits/{edit_id}/tracks/{track_path}"),
                          json_body={"track": track, "releases": [release]})
            except PlayError as error:
                known = play.call("GET", play.url(f"edits/{edit_id}/tracks")).get("tracks", [])
                fail(f"Could not update track {track}: {error}. Tracks Play reports: "
                     + ", ".join(t["track"] for t in known))
            print(f"{track}: {release['status']}")

        commit_url = play.url(f"edits/{edit_id}:commit")
        try:
            play.call("POST", commit_url)
            print("\nCommitted and sent for review.")
        except PlayError as error:
            # Play refuses to send some edits for review on its own (after a
            # rejection, or with other changes pending in the Console). The
            # edit can still be saved; the Console then holds it for a click.
            if "changesNotSentForReview" not in error.message:
                raise
            play.call("POST", commit_url + "?changesNotSentForReview=true")
            print("::warning::Play would not send this release for review automatically. "
                  "It is saved: open Play Console > Publishing overview and click "
                  "'Send changes for review'.")
        committed = True
    except PlayError as error:
        fail(f"Google Play: {error}")
    finally:
        if not committed:
            try:
                play.call("DELETE", play.url(f"edits/{edit_id}"))
            except PlayError:
                pass


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    notes = sub.add_parser("notes", help="print and check the release notes")
    notes.add_argument("--phone-code", type=int, required=True)

    chk = sub.add_parser("check", help="sign in and list the app's tracks, changing nothing")
    chk.add_argument("--package", default="org.ntust.app.tigerduck")
    chk.add_argument("--key-file", help="service-account JSON key; default: $PLAY_SERVICE_ACCOUNT_JSON")

    pub = sub.add_parser("publish", help="upload, assign to tracks and commit")
    pub.add_argument("--package", required=True)
    pub.add_argument("--key-file", help="service-account JSON key; default: $PLAY_SERVICE_ACCOUNT_JSON")
    pub.add_argument("--version-name", required=True)
    pub.add_argument("--phone-code", type=int, required=True)
    pub.add_argument("--watch-code", type=int, required=True)
    pub.add_argument("--phone-aab", required=True)
    pub.add_argument("--watch-aab", required=True)
    pub.add_argument("--targets", required=True,
                     help="comma-separated artifact:audience, e.g. phone:internal,watch:public")
    pub.add_argument("--rollout", type=float, default=0.1,
                     help="share of users a production release reaches; 1 releases to everyone")
    pub.add_argument("--dry-run", action="store_true", help="print the plan, send nothing")

    args = parser.parse_args()
    if args.command == "notes":
        print_notes(release_notes(args.phone_code))
    elif args.command == "check":
        check(args)
    else:
        publish(args)


if __name__ == "__main__":
    main()
