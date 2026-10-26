#!/usr/bin/env python3
"""Write the GitHub Release notes for the version in the code.

Fills in `.github/release-notes-template.md`, a Markdown file with these
placeholders:

  {{WHATS_NEW}}     the version's What's New items from whatsnew.json, one per
                    line as "- <zh-Hant title>", with "  <en title>" under it
  {{VERSION_NAME}}  versionName, e.g. 2.3.1
  {{PHONE_CODE}}    the phone versionCode
  {{WATCH_CODE}}    the watch versionCode
  {{COMPARE_URL}}   the GitHub compare link from the previous release's tag

Everything else in the template, the notes on upgrading and on the F-Droid
build among it, is copied as it stands: edit it there.

The version comes from `app/build.gradle.kts` and `wear/build.gradle.kts`, and
the previous release is the highest `vX.Y.Z` tag below it, so the notes match
whatever commit is checked out. Fails, naming the cause, when the What's New
entry is missing, its two languages list different numbers of items, or the
template names a placeholder this script does not fill.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path(".github") / "release-notes-template.md"
WHATSNEW = Path("app") / "src" / "main" / "assets" / "whatsnew.json"

VERSION_CODE = re.compile(r"^\s*versionCode\s*=\s*(\d+)", re.MULTILINE)
VERSION_NAME = re.compile(r'^\s*versionName\s*=\s*"([^"]+)"', re.MULTILINE)
RELEASE_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
PLACEHOLDER = re.compile(r"\{\{\s*([A-Z_]+)\s*\}\}")


class NotesError(Exception):
    pass


def read_versions(root: Path) -> dict:
    """versionName and the phone and watch versionCode, from the Gradle files."""
    def first(pattern, field, path):
        match = pattern.search((root / path).read_text(encoding="utf-8"))
        if not match:
            raise NotesError(f"No {field} in {path}.")
        return match.group(1)

    app, wear = Path("app") / "build.gradle.kts", Path("wear") / "build.gradle.kts"
    return {
        "name": first(VERSION_NAME, "versionName", app),
        "phone_code": int(first(VERSION_CODE, "versionCode", app)),
        "watch_code": int(first(VERSION_CODE, "versionCode", wear)),
    }


def titles(language_entry: dict) -> list:
    if language_entry.get("items"):
        return [item["title"] for item in language_entry["items"]]
    return list(language_entry.get("highlights", []))


def whats_new(root: Path, phone_code: int) -> str:
    entry = json.loads((root / WHATSNEW).read_text(encoding="utf-8")).get(str(phone_code))
    if not entry:
        raise NotesError(f"{WHATSNEW} has no entry for versionCode {phone_code}.")
    zh, en = titles(entry.get("zh-Hant", {})), titles(entry.get("en", {}))
    if not zh or not en:
        missing = "zh-Hant" if not zh else "en"
        raise NotesError(f"{WHATSNEW} [{phone_code}] has no {missing} items.")
    if len(zh) != len(en):
        raise NotesError(
            f"{WHATSNEW} [{phone_code}] lists {len(zh)} zh-Hant items but {len(en)} en items; "
            "the release notes pair them line by line."
        )
    return "\n".join(f"- {zh_title}\n  {en_title}" for zh_title, en_title in zip(zh, en))


def previous_tag(tags: list, version_name: str):
    """The highest vX.Y.Z tag below [version_name], or None."""
    current = RELEASE_TAG.match(f"v{version_name}")
    if not current:
        return None
    current = tuple(map(int, current.groups()))
    older = [
        (tuple(map(int, match.groups())), tag)
        for tag in tags
        if (match := RELEASE_TAG.match(tag)) and tuple(map(int, match.groups())) < current
    ]
    return max(older)[1] if older else None


def git_tags(root: Path) -> list:
    return subprocess.run(
        ["git", "-C", str(root), "tag", "--list", "v*"],
        capture_output=True, text=True, check=True,
    ).stdout.split()


def render(template: str, root: Path, repo: str, tags: list) -> str:
    versions = read_versions(root)
    tag = f"v{versions['name']}"
    previous = previous_tag(tags, versions["name"])
    compare = (f"https://github.com/{repo}/compare/{previous}...{tag}" if previous
               else f"https://github.com/{repo}/commits/{tag}")
    values = {
        "WHATS_NEW": whats_new(root, versions["phone_code"]),
        "VERSION_NAME": versions["name"],
        "PHONE_CODE": str(versions["phone_code"]),
        "WATCH_CODE": str(versions["watch_code"]),
        "COMPARE_URL": compare,
    }
    unknown = sorted({name for name in PLACEHOLDER.findall(template) if name not in values})
    if unknown:
        raise NotesError(
            "The template names placeholders this script does not fill: "
            + ", ".join("{{" + name + "}}" for name in unknown)
            + ". Known: " + ", ".join("{{" + name + "}}" for name in values) + "."
        )
    return PLACEHOLDER.sub(lambda match: values[match.group(1)], template)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--repo", required=True, help="owner/name, for the compare link")
    parser.add_argument("--root", type=Path, default=REPO_ROOT,
                        help="checkout to read the version, What's New and tags from; default: this repo")
    parser.add_argument("--template", type=Path,
                        help=f"default: <root>/{TEMPLATE}")
    parser.add_argument("--output", type=Path, help="file to write; default: standard output")
    args = parser.parse_args()

    template_path = args.template or args.root / TEMPLATE
    try:
        notes = render(template_path.read_text(encoding="utf-8"), args.root, args.repo,
                       git_tags(args.root))
    except NotesError as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
    if args.output:
        args.output.write_text(notes, encoding="utf-8")
    else:
        sys.stdout.write(notes)


if __name__ == "__main__":
    main()
