#!/usr/bin/env python3
"""Generate the app's localized strings from the app-translation submodule.

Run by the Gradle build (`generateAppTranslationStrings` in `gradle/app-translation.gradle.kts`)
on every build of the app and the watch:

1. The submodule's own generator writes `values*/strings.xml` from
   `app-translation/source/*.json` straight into `--res-dir`, a generated
   resource directory under `build/`. Nothing is written into the source tree:
   neither this repo nor the submodule checks Android's strings in.
2. With `--locales-config`, `xml/locales_config.xml` is written beside them from
   the `values*` directories, so the system language picker (Android 13+) sees
   every shipped locale. Only the phone app declares one.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Iterable, List, Optional, Set

ROOT = Path(__file__).resolve().parents[2]
APP_TRANSLATION_DIR = ROOT / "app-translation"
CONFIG_PATH = APP_TRANSLATION_DIR / "config" / "locales.json"
GENERATOR = APP_TRANSLATION_DIR / "tools" / "localization" / "generate_localizations.py"

# The oldest Python the strings are generated with, and CI tests: macOS's own python3.
MINIMUM_PYTHON = (3, 9)


def load_locale_config() -> dict:
    with CONFIG_PATH.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def run_canonical_generator(res_dir: Path) -> None:
    subprocess.check_call(
        [sys.executable, str(GENERATOR), "--platform", "android", "--output-dir", str(res_dir)]
    )


def discover_android_values_locales(res_dir: Path, default_dir_locale: str) -> Set[str]:
    """Return BCP-47 tags represented by values* dirs under [res_dir]."""
    tags: Set[str] = set()
    for path in res_dir.glob("*/strings.xml"):
        dir_name = path.parent.name
        if dir_name == "values":
            tags.add(default_dir_locale)
            continue
        if not dir_name.startswith("values-"):
            continue

        qualifier = dir_name[len("values-"):]
        if qualifier.startswith("b+"):
            tags.add(qualifier[len("b+"):].replace("+", "-"))
            continue

        # values-en, values-en-rGB, values-yue-rHK
        parts = qualifier.split("-r", 1)
        if len(parts) == 1:
            tags.add(parts[0])
        else:
            lang, region = parts
            tags.add(f"{lang}-{region}")

    return tags


def render_locale_config(locales: Iterable[str]) -> str:
    lines = [
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
        "<!-- Generated from app-translation/source/*.json at build time. -->",
        "<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">",
    ]
    for locale in sorted(locales):
        lines.append(f"    <locale android:name=\"{locale}\" />")
    lines.append("</locale-config>")
    lines.append("")
    return "\n".join(lines)


def write_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # LF on every platform, as the generator writes: the same source builds the same bytes.
    # Through open(newline=), which Python 3.9 has too.
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)


def main(argv: Optional[List[str]] = None) -> int:
    if sys.version_info < MINIMUM_PYTHON:
        print(
            "The app's strings need Python %d.%d or newer to generate; this is %s."
            % (MINIMUM_PYTHON + (".".join(str(part) for part in sys.version_info[:3]),)),
            file=sys.stderr,
        )
        return 1
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--res-dir", type=Path, required=True, help="Resource directory to write values*/strings.xml into")
    parser.add_argument("--locales-config", action="store_true", help="Also write xml/locales_config.xml")
    args = parser.parse_args(argv)

    try:
        run_canonical_generator(args.res_dir)
        cfg = load_locale_config()
        default_dir_locale = cfg.get("android", {}).get("defaultDirLocale", "zh-Hant")
        tags = discover_android_values_locales(args.res_dir, default_dir_locale)
        if args.locales_config:
            write_file(args.res_dir / "xml" / "locales_config.xml", render_locale_config(tags))
    except subprocess.CalledProcessError as error:
        print(f"Canonical generator failed: {error}", file=sys.stderr)
        return error.returncode or 1
    except Exception as error:
        print(f"Localization sync failed: {error}", file=sys.stderr)
        return 1

    print(f"Localization sync complete: {len(tags)} Android locale tags emitted.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
