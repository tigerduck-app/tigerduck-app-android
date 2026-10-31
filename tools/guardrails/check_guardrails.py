#!/usr/bin/env python3
"""Fails CI when a long file, a long function or a copied rule is added.

The refactor gives each duplicated rule one home. This check stops the
duplicates and the giant files from growing back. Today's offenses live in
baseline.json, which may only shrink: a count above the baseline, or a path
the baseline does not list, fails.

Offence kinds (counts in the baseline):
  long-file       a Kotlin file over 400 lines; the count is its line count.
  long-function   a function over 80 lines (brace-counted; an expression body
                  counts to the end of its expression), recorded one by one as
                  {path, kind, name, lines}. A renamed function counts as new.
  taipei-literal  occurrences of the "Asia/Taipei" literal outside TaipeiClock.kt.
  fdroid-literal  occurrences of the "fdroid" literal outside BuildConfig reads
                  in AppPreferences.kt.
  term-sort       sorts over a semester or term string outside AcademicTerm.kt.

Every production source set (main, play, fdroid, debug) of app, shared and
wear is read; tests are not.
Stdlib only, and it must run on Python 3.9.

    python3 tools/guardrails/check_guardrails.py [--update-baseline]
"""
import argparse
import json
import os
import re
import sys

MODULES = ("app", "shared", "wear")
SOURCE_SETS = ("main", "play", "fdroid", "debug")
MAX_FILE_LINES = 400
MAX_FUNCTION_LINES = 80

TAIPEI_HOME = "shared/src/main/java/org/ntust/app/tigerduck/shared/time/TaipeiClock.kt"
TERM_HOME = "shared/src/main/java/org/ntust/app/tigerduck/shared/time/AcademicTerm.kt"
PREFERENCES = "app/src/main/java/org/ntust/app/tigerduck/data/preferences/AppPreferences.kt"

# The brief's regex, widened to also match sortedByDescending and compareBy.
TERM_SORT = re.compile(
    r"sorted(By)?(Descending)?\s*\{[^}]*\b(semester|term)"
    r"|compareBy\s*(<[^>]*>)?\s*[({][^})]*\b(semester|term)", re.IGNORECASE)
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


def skip_ws(code, i):
    while i < len(code) and code[i].isspace():
        i += 1
    return i


def block_end(code, i):
    """Index of the brace closing the one at code[i]."""
    depth, n = 0, len(code)
    while i < n:
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n - 1


CONTINUES = ("&&", "||", "?:", "?.", ".", "+", "-", "*", "/", "%", "->", "as ", "as?", "in ", "is ")
ENDS_OPEN = ("=", "+", "-", "*", "/", "%", ",", "&&", "||", "?:", "->", "(", ".", "?.")


def expression_end(code, i):
    """Index where the expression body starting at code[i] ends.

    It ends at the first newline outside every bracket whose line does not
    end in an operator and whose next line does not start with one.
    """
    depth, n = 0, len(code)
    while i < n:
        c = code[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth < 0:
                return i
        elif c == "\n" and depth == 0:
            before = code[:i].rstrip()
            j = skip_ws(code, i)
            if not before.endswith(ENDS_OPEN) and not code.startswith(CONTINUES, j):
                return i
        i += 1
    return n


def function_end(code, start):
    """Index where the function whose `fun` begins at start ends, or None when bodiless."""
    n = len(code)
    i, paren, closed = start + 3, 0, False
    while i < n:
        c = code[i]
        if c == "(":
            paren += 1
        elif c == ")":
            paren -= 1
            closed = closed or paren == 0
        elif paren == 0 and closed:
            if c == "{":
                return block_end(code, i)
            if c == "=":
                return expression_end(code, i + 1)
            if c == ";":
                return None
            if c == "\n":
                # The signature goes on only if the next line says so.
                j = skip_ws(code, i)
                if not (code.startswith(("{", "=", ":", "where "), j)):
                    return None
        i += 1
    return None


def long_functions(code):
    """[(name, lines)] for every function with a body, from `fun` to its end."""
    found, seen = [], {}
    for m in FUN.finditer(code):
        if re.match(r"\s+interface\b", code[m.end():m.end() + 20]):
            continue  # `fun interface`, not a function
        end = function_end(code, m.start())
        if end is None:
            continue
        head = code[m.end():code.find("(", m.end())] if "(" in code[m.end():] else ""
        head = re.sub(r"<[^<>]*>", " ", head)
        ids = re.findall(r"[\w`]+", head)
        name = ids[-1].strip("`") if ids else "<anonymous>"
        seen[name] = seen.get(name, 0) + 1
        if seen[name] > 1:
            name += "#%d" % seen[name]  # overloads get an index, in file order
        found.append((name, code.count("\n", m.start(), end) + 1))
    return found


def source_files(root):
    for module in MODULES:
        for source_set in SOURCE_SETS:
            base = os.path.join(root, module, "src", source_set)
            for dirpath, _, names in os.walk(base):
                for name in sorted(names):
                    if name.endswith(".kt"):
                        full = os.path.join(dirpath, name)
                        yield os.path.relpath(full, root).replace(os.sep, "/"), full


def scan(root):
    """Returns {(path, kind, name): n} for every offense under root.

    n is a count, a file's line count (long-file) or a function's line count
    (long-function, the only kind with a name).
    """
    found = {}

    def add(path, kind, count, name=""):
        if count > 0:
            found[(path, kind, name)] = count

    for rel, full in source_files(root):
        with open(full, encoding="utf-8") as f:
            text = f.read()
        total = text.count("\n") + (0 if text.endswith("\n") or not text else 1)
        if total > MAX_FILE_LINES:
            add(rel, "long-file", total)

        code = blank_literals(text)
        for name, lines in long_functions(code):
            if lines > MAX_FUNCTION_LINES:
                add(rel, "long-function", lines, name)
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
        return {(e["path"], e["kind"], e.get("name", "")): e.get("lines", e.get("count"))
                for e in json.load(f)}


def write_baseline(path, counts):
    entries = []
    for (p, k, name), n in sorted(counts.items()):
        if k == "long-function":
            entries.append({"path": p, "kind": k, "name": name, "lines": n})
        else:
            entries.append({"path": p, "kind": k, "count": n})
    with open(path, "w", encoding="utf-8") as f:
        json.dump(entries, f, indent=2)
        f.write("\n")


def worse(found, baseline):
    """Offenses above the baseline: new paths and counts that grew."""
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
    print("Guardrails ok: %d baselined offenses, none new." % len(found))
    return 0


def report(bad):
    for (path, kind, name), count, allowed in bad:
        print("%s: %s%s %d (baseline %d)" % (path, kind, " " + name if name else "", count, allowed))
    print("%d new offense(s). Fix them; the baseline must not grow." % len(bad))


if __name__ == "__main__":
    sys.exit(main())
