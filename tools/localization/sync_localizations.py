#!/usr/bin/env python3

import json
import sys
from pathlib import Path
from typing import Dict, Iterable


ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / "localization" / "source"

SOURCE_FILES = {
    "en": SOURCE_DIR / "en.json",
    "zh-Hant": SOURCE_DIR / "zh-Hant.json",
}

ANDROID_OUTPUTS = {
    "zh-Hant": ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml",
    "en": ROOT / "app" / "src" / "main" / "res" / "values-en" / "strings.xml",
}

IOS_OUTPUTS = {
    "en": ROOT / "localization" / "generated" / "ios" / "en.lproj" / "Localizable.strings",
    "zh-Hant": ROOT / "localization" / "generated" / "ios" / "zh-Hant.lproj" / "Localizable.strings",
}


def load_locale(path: Path) -> Dict[str, str]:
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)

    if not isinstance(data, dict):
        raise ValueError(f"{path} must be a JSON object of key/value pairs")

    invalid = [key for key, value in data.items() if not isinstance(key, str) or not isinstance(value, str)]
    if invalid:
        joined = ", ".join(invalid)
        raise ValueError(f"{path} has non-string key/value entries: {joined}")

    return data


def validate_keys(locales: Dict[str, Dict[str, str]]) -> Iterable[str]:
    base_locale = "en"
    base_keys = set(locales[base_locale].keys())

    for locale, values in locales.items():
        missing = sorted(base_keys - set(values.keys()))
        extra = sorted(set(values.keys()) - base_keys)
        if missing or extra:
            parts = []
            if missing:
                parts.append(f"missing keys: {', '.join(missing)}")
            if extra:
                parts.append(f"extra keys: {', '.join(extra)}")
            raise ValueError(f"{locale} has key mismatch ({'; '.join(parts)})")

    return locales[base_locale].keys()


def escape_android(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
        .replace('"', '\\"')
        .replace("\n", "\\n")
    )


def escape_ios(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def write_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def generate_android(locale_values: Dict[str, str], ordered_keys: Iterable[str]) -> str:
    lines = [
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
        "<resources>",
        "    <!-- Generated from localization/source/*.json. Do not edit directly. -->",
    ]

    for key in ordered_keys:
        escaped = escape_android(locale_values[key])
        lines.append(f"    <string name=\"{key}\">{escaped}</string>")

    lines.append("</resources>")
    lines.append("")
    return "\n".join(lines)


def generate_ios(locale_values: Dict[str, str], ordered_keys: Iterable[str]) -> str:
    lines = [
        "/* Generated from localization/source/*.json. Do not edit directly. */",
    ]

    for key in ordered_keys:
        escaped = escape_ios(locale_values[key])
        lines.append(f"\"{key}\" = \"{escaped}\";")

    lines.append("")
    return "\n".join(lines)


def main() -> int:
    try:
        locales = {locale: load_locale(path) for locale, path in SOURCE_FILES.items()}
        ordered_keys = list(validate_keys(locales))

        for locale, output in ANDROID_OUTPUTS.items():
            write_file(output, generate_android(locales[locale], ordered_keys))

        for locale, output in IOS_OUTPUTS.items():
            write_file(output, generate_ios(locales[locale], ordered_keys))
    except Exception as error:
        print(f"Localization sync failed: {error}", file=sys.stderr)
        return 1

    print(
        "Localization sync complete: "
        f"{len(ordered_keys)} keys, {len(ANDROID_OUTPUTS)} Android files, {len(IOS_OUTPUTS)} iOS files."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
