#!/usr/bin/env python3
"""Refresh Android localization artifacts.

Steps:
1. Invoke the canonical generator in the localization submodule to (re)produce
   `localization/generated/{android,apple}/...` from the grouped source JSON.
2. Generate Android's `locales_config.xml` from the resulting values-* dirs
   so the system language picker (Android 13+) sees every shipped locale.

Strings.xml generation itself lives in the localization submodule
(`tools/localization/generate_localizations.py`); the Gradle copy step picks
those up from `localization/generated/android/`. This script does not write
strings.xml itself.
"""

import json
import subprocess
import sys
from pathlib import Path
from typing import Iterable, Set


ROOT = Path(__file__).resolve().parents[2]
LOCALIZATION_DIR = ROOT / "localization"
CONFIG_PATH = LOCALIZATION_DIR / "config" / "locales.json"
GENERATED_ANDROID_DIR = LOCALIZATION_DIR / "generated" / "android"
LOCALES_CONFIG_PATH = ROOT / "app" / "src" / "main" / "res" / "xml" / "locales_config.xml"


def load_locale_config() -> dict:
    with CONFIG_PATH.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def run_canonical_generator() -> None:
    script = LOCALIZATION_DIR / "tools" / "localization" / "generate_localizations.py"
    subprocess.check_call([sys.executable, str(script)])


def discover_android_values_locales(default_dir_locale: str) -> Set[str]:
    """Return BCP-47 tags represented by values* dirs under generated/android."""
    tags: Set[str] = set()
    for path in GENERATED_ANDROID_DIR.glob("*/strings.xml"):
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
        "<!-- Generated from localization/source/*.json. Do not edit directly. -->",
        "<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">",
    ]
    for locale in sorted(locales):
        lines.append(f"    <locale android:name=\"{locale}\" />")
    lines.append("</locale-config>")
    lines.append("")
    return "\n".join(lines)


def write_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main() -> int:
    try:
        run_canonical_generator()

        cfg = load_locale_config()
        default_dir_locale = cfg.get("android", {}).get("defaultDirLocale", "zh-Hant")

        tags = discover_android_values_locales(default_dir_locale)
        write_file(LOCALES_CONFIG_PATH, render_locale_config(tags))
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
