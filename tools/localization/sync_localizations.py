#!/usr/bin/env python3

import json
import re
import sys
from pathlib import Path
from typing import Dict, Iterable, Set


ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / "localization" / "source"
CONFIG_PATH = ROOT / "localization" / "config" / "locales.json"


def discover_source_files() -> Dict[str, Path]:
    files: Dict[str, Path] = {}
    for path in sorted(SOURCE_DIR.glob("*.json")):
        files[path.stem] = path
    if "en" not in files:
        raise ValueError("localization/source must contain en.json")
    return files


def android_values_dir(locale: str) -> str:
    # Keep Traditional Chinese as the app's base locale.
    if locale == "zh-Hant":
        return "values"
    if locale == "en":
        return "values-en"
    # Prefer region defaults for script-based Chinese so Android matches the
    # canonical qualifiers (values-zh-rCN / values-zh-rTW).
    if locale == "zh-Hans":
        return "values-zh-rCN"

    parts = locale.split("-")
    if len(parts) == 1:
        return f"values-{parts[0]}"
    if len(parts) == 2 and len(parts[1]) == 2:
        lang, region = parts
        return f"values-{lang}-r{region.upper()}"

    # Fallback to BCP-47 qualifier form when needed.
    # https://developer.android.com/guide/topics/resources/providing-resources#QualifierRules
    bcp = "+".join([p for p in parts if p])
    return f"values-b+{bcp}"


def android_outputs(root: Path, locales: Iterable[str]) -> Dict[str, Path]:
    return {
        locale: root / android_values_dir(locale) / "strings.xml" for locale in locales
    }


def ios_outputs(locales: Iterable[str]) -> Dict[str, Path]:
    out_root = ROOT / "localization" / "generated" / "ios"
    return {
        locale: out_root / f"{locale}.lproj" / "Localizable.strings" for locale in locales
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


def load_locale_config() -> dict:
    with CONFIG_PATH.open("r", encoding="utf-8") as handle:
        return json.load(handle)


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


# Matches Android format specifiers (positional like %1$s, simple like %d),
# already-escaped %%, or a bare %. Order in the alternation matters: longer
# tokens win at each position, so specifiers and %% pass through untouched
# and only a bare "%" gets doubled.
_ANDROID_PERCENT_RE = re.compile(r"%\d+\$[sdf]|%%|%[sdf]|%")


def _escape_percent(match: "re.Match[str]") -> str:
    token = match.group(0)
    return "%%" if token == "%" else token


def escape_android(value: str) -> str:
    escaped = (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
        .replace('"', '\\"')
        .replace("\n", "\\n")
    )
    return _ANDROID_PERCENT_RE.sub(_escape_percent, escaped)


def escape_ios(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def write_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def generate_android_locale_config(locales: Iterable[str]) -> str:
    lines = [
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
        "<!-- Generated from localization/source/*.json. Do not edit directly. -->",
        "<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">",
    ]
    for locale in sorted(locales):
        # BCP-47 language tags.
        lines.append(f"    <locale android:name=\"{locale}\" />")
    lines.append("</locale-config>")
    lines.append("")
    return "\n".join(lines)


def discover_android_values_locales(generated_android_dir: Path, default_dir_locale: str) -> Set[str]:
    """Return BCP-47 tags represented by values* directories under generated/android."""
    tags: Set[str] = set()
    for path in generated_android_dir.glob("*/strings.xml"):
        dir_name = path.parent.name
        if dir_name == "values":
            tags.add(default_dir_locale)
            continue
        if not dir_name.startswith("values-"):
            continue

        qualifier = dir_name[len("values-"):]
        if qualifier.startswith("b+"):
            # values-b+sr+Latn+RS -> sr-Latn-RS
            tags.add(qualifier[len("b+"):].replace("+", "-"))
            continue

        # values-en, values-en-rGB
        parts = qualifier.split("-r", 1)
        if len(parts) == 1:
            tags.add(parts[0])
        else:
            lang, region = parts
            tags.add(f"{lang}-{region}")

    return tags


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
        cfg = load_locale_config()
        android_cfg = cfg.get("android", {})
        default_dir_locale = android_cfg.get("defaultDirLocale", "zh-Hant")

        source_files = discover_source_files()
        locales = {locale: load_locale(path) for locale, path in source_files.items()}
        ordered_keys = list(validate_keys(locales))

        shared_android = android_outputs(ROOT / "localization" / "generated" / "android", locales.keys())
        app_android = android_outputs(ROOT / "app" / "src" / "main" / "res", locales.keys())
        ios = ios_outputs(locales.keys())

        for locale, output in shared_android.items():
            write_file(output, generate_android(locales[locale], ordered_keys))

        for locale, output in app_android.items():
            write_file(output, generate_android(locales[locale], ordered_keys))

        for locale, output in ios.items():
            write_file(output, generate_ios(locales[locale], ordered_keys))

        # Android 13+ system "App language" picker reads android:localeConfig.
        # The repo also ships many resource *aliases* (values-aa, values-en-rGB,
        # values-iw, ...) so build the list from generated Android outputs.
        android_locale_tags = discover_android_values_locales(
            ROOT / "localization" / "generated" / "android",
            default_dir_locale=default_dir_locale,
        )
        write_file(
            ROOT / "app" / "src" / "main" / "res" / "xml" / "locales_config.xml",
            generate_android_locale_config(android_locale_tags),
        )
    except Exception as error:
        print(f"Localization sync failed: {error}", file=sys.stderr)
        return 1

    print(
        "Localization sync complete: "
        f"{len(ordered_keys)} keys, "
        f"{len(locales)} shared Android files, "
        f"{len(locales)} app Android files, "
        f"{len(locales)} iOS files."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
