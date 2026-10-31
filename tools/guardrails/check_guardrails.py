#!/usr/bin/env python3
"""Fails CI when a long file, a long function or a copied rule is added.

The refactor gives each duplicated rule one home. This check stops the
duplicates and the giant files from growing back. Today's offences live in
baseline.json, which may only shrink: a count above the baseline, or a path
the baseline does not list, fails.

Offence kinds (counts in the baseline):
  long-file       a Kotlin file over 400 lines; the count is its line count.
  long-function   functions over 80 lines (brace-counted); the count is the
                  summed length of those functions.
  taipei-literal  occurrences of the "Asia/Taipei" literal outside TaipeiClock.kt.
  fdroid-literal  occurrences of the "fdroid" literal outside BuildConfig reads
                  in AppPreferences.kt.
  term-sort       sorts over a semester or term string outside AcademicTerm.kt.

Only main sources (src/main) of app, shared and wear are read; tests are not.
Stdlib only, and it must run on Python 3.9.

    python3 tools/guardrails/check_guardrails.py [--update-baseline]
"""
import argparse
import json
import os
import re
import sys

MODULES = ("app", "shared", "wear")
MAX_FILE_LINES = 400
MAX_FUNCTION_LINES = 80

TAIPEI_HOME = "shared/src/main/java/org/ntust/app/tigerduck/shared/time/TaipeiClock.kt"
TERM_HOME = "shared/src/main/java/org/ntust/app/tigerduck/shared/time/AcademicTerm.kt"
PREFERENCES = "app/src/main/java/org/ntust/app/tigerduck/data/preferences/AppPreferences.kt"

# The brief's regex, widened to also match sortedByDescending and compareBy.
TERM_SORT = re.compile(
    r"sorted(By)?(Descending)?\s*\{[^}]*(semester|term)"
    r"|compareBy\s*(<[^>]*>)?\s*[({][^})]*(semester|term)")
FUN = re.compile(r"\bfun\b")

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.dirname(os.path.dirname(HERE))
DEFAULT_BASELINE = os.path.join(HERE, "baseline.json")


def blank_literals(text, keep_strings=False):
    """Blanks comments (and string contents unless keep_strings), keeping newlines.

    Brace counting and the sort regex must not see a brace or a keyword that
    sits in a comment or a string. Offsets and line numbers stay unchanged.
    """
    out = []
    i, n = 0, len(text)

    def blank(chunk):
        return "".join(c if c == "\n" else " " for c in chunk)

    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(blank(text[i:j]))
            i = j
        elif text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth += 1
                    j += 2
                elif text.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            out.append(blank(text[i:j]))
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j] if keep_strings else '"""' + blank(text[i + 3:j - 3]) + '"""')
            i = j
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(text[i:j] if keep_strings else '"' + blank(text[i + 1:j - 1]) + '"')
            i = j
        elif c == "'":
            m = re.match(r"'(\\.[^']*|[^\\'])'", text[i:i + 12])
            if m:
                out.append("' '" if not keep_strings else m.group(0))
                i += m.end()
            else:
                out.append(c)
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def long_function_lines(code):
    """Lengths of every brace-bodied function, from `fun` to its closing brace."""
    lengths = []
    for m in FUN.finditer(code):
        i, n, paren = m.end(), len(code), 0
        # Find the body's "{" after the parameter list; "=" first means an
        # expression body, and a ";" or a new declaration means no body.
        while i < n:
            c = code[i]
            if c == "(":
                paren += 1
            elif c == ")":
                paren -= 1
            elif paren == 0 and c in "{=;":
                break
            i += 1
        if i >= n or code[i] != "{":
            continue
        depth, j = 0, i
        while j < n:
            if code[j] == "{":
                depth += 1
            elif code[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        lengths.append(code.count("\n", m.start(), j) + 1)
    return lengths


def source_files(root):
    for module in MODULES:
        base = os.path.join(root, module, "src", "main")
        for dirpath, _, names in os.walk(base):
            for name in sorted(names):
                if name.endswith(".kt"):
                    full = os.path.join(dirpath, name)
                    yield os.path.relpath(full, root).replace(os.sep, "/"), full


def scan(root):
    """Returns {(path, kind): count} for every offence under root."""
    found = {}

    def add(path, kind, count):
        if count > 0:
            found[(path, kind)] = count

    for rel, full in source_files(root):
        with open(full, encoding="utf-8") as f:
            text = f.read()
        total = text.count("\n") + (0 if text.endswith("\n") or not text else 1)
        if total > MAX_FILE_LINES:
            add(rel, "long-file", total)

        code = blank_literals(text)
        add(rel, "long-function",
            sum(n for n in long_function_lines(code) if n > MAX_FUNCTION_LINES))
        add(rel, "term-sort",
            0 if rel == TERM_HOME else len(TERM_SORT.findall(code)))

        # Literals are matched with strings kept and comments blanked, so a
        # KDoc that mentions the zone does not count.
        lines = blank_literals(text, keep_strings=True).split("\n")
        if rel != TAIPEI_HOME:
            add(rel, "taipei-literal", sum(l.count('"Asia/Taipei"') for l in lines))
        add(rel, "fdroid-literal", sum(
            l.count('"fdroid"') for l in lines
            if not (rel == PREFERENCES and "BuildConfig" in l)))
    return found


def load_baseline(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return {(e["path"], e["kind"]): e["count"] for e in json.load(f)}


def write_baseline(path, counts):
    entries = [{"path": p, "kind": k, "count": c} for (p, k), c in sorted(counts.items())]
    with open(path, "w", encoding="utf-8") as f:
        json.dump(entries, f, indent=2)
        f.write("\n")


def worse(found, baseline):
    """Offences above the baseline: new paths and counts that grew."""
    return [(key, count, baseline.get(key, 0))
            for key, count in sorted(found.items()) if count > baseline.get(key, 0)]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--update-baseline", action="store_true",
                        help="rewrite the baseline downward; never adds or raises an entry")
    parser.add_argument("--root", default=DEFAULT_ROOT)
    parser.add_argument("--baseline", default=DEFAULT_BASELINE)
    args = parser.parse_args(argv)

    found = scan(args.root)
    baseline = load_baseline(args.baseline)

    if args.update_baseline:
        if baseline is None:
            # The first run has no baseline to be above, so it records today.
            write_baseline(args.baseline, found)
            print("Created %s with %d entries." % (args.baseline, len(found)))
            return 0
        bad = worse(found, baseline)
        if bad:
            report(bad)
            print("Refusing to update the baseline: it may only shrink.")
            return 1
        write_baseline(args.baseline, found)
        print("Baseline now has %d entries (was %d)." % (len(found), len(baseline)))
        return 0

    bad = worse(found, baseline or {})
    if bad:
        report(bad)
        return 1
    print("Guardrails ok: %d baselined offences, none new." % len(found))
    return 0


def report(bad):
    for (path, kind), count, allowed in bad:
        print("%s: %s %d (baseline %d)" % (path, kind, count, allowed))
    print("%d new offence(s). Fix them; the baseline must not grow." % len(bad))


if __name__ == "__main__":
    sys.exit(main())
