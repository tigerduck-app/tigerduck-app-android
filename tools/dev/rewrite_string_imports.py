#!/usr/bin/env python3
"""Point Kotlin code's string resources at `:core:strings`' R class.

The strings generated from app-translation moved out of `:app` into the
`:core:strings` library. With `android.nonTransitiveRClass`, each module's R
class carries only its own resources, so `org.ntust.app.tigerduck.R.string.x`
no longer exists: the string is `org.ntust.app.tigerduck.core.strings.R.string.x`.
This script rewrites the references, file by file:

- A file whose `R` is `:app`'s and that uses it only for strings (and for
  `R.xml.locales_config`, which moved with them) imports the strings module's R
  instead. Its references stay `R.string.x`.
- A file that also uses `:app`'s own resources (drawables, raw, styles, ...),
  or uses its R in any other way, keeps `:app`'s R and reaches the strings
  through an alias: `import org.ntust.app.tigerduck.core.strings.R as StringsR`,
  `StringsR.string.x` (`StringsR2`, and so on, if the file already binds the name).
- A fully qualified `org.ntust.app.tigerduck.R.string.x`, and an import of
  `org.ntust.app.tigerduck.R.string` or of one string in it, gets the strings
  module's package.

Files in the `org.ntust.app.tigerduck` package itself, or importing all of it,
use `:app`'s R without importing it; they get the same treatment. Only code
decides which case a file is; comments are rewritten along with it, string
literals never (their `${...}` templates are code).

It changes only references that are wrong, so it is safe to run again, and on
any branch: a branch made before the move gets the same rewrite, and a file
already rewritten is left as it is. Run it from the repository root, after
merging the branch that added `:core:strings`:

    python3 tools/dev/rewrite_string_imports.py            # rewrite in place
    python3 tools/dev/rewrite_string_imports.py --check    # list, change nothing; exit 1 if any

With no paths it walks every module's `src` (`app/src`, and `core/*/src` and
`feature/*/src` where they exist). `:wear` keeps its own strings and R and is
never touched. What it cannot recognize stays on `:app`'s R, so a miss shows up
as a compile error, never as a string silently read from the wrong place; a file
it cannot read for sure (an import it cannot parse, a directive running into a
block comment, a parameter or variable named like the R it would rewrite) it
leaves alone and lists, for a rewrite by hand, and then exits 1.
"""

import argparse
import re
import sys
from pathlib import Path
from typing import Iterable, List, Optional, Sequence, Set, Tuple

ROOT = Path(__file__).resolve().parents[2]

APP_PACKAGE = "org.ntust.app.tigerduck"
APP_R = APP_PACKAGE + ".R"
STRINGS_R = APP_PACKAGE + ".core.strings.R"
# The name a file that also uses :app's own resources gives the strings module's R.
ALIAS = "StringsR"

# What :core:strings' R holds: every string the generator writes, and the locale list.
STRINGS_TYPES = frozenset({"string", "plurals"})
STRINGS_RESOURCES = frozenset({("xml", "locales_config")})

# QUOTED is a backticked name past its opening backtick, which is CODE: a reference
# may start with `R`, but a test named `shows R.string.x` holds none.
CODE, COMMENT, LITERAL, QUOTED = 0, 1, 2, 3

# A Kotlin name, plain or in backticks.
NAME = r"(?:`[^`\r\n]+`|\w+)"
IMPORT = re.compile(
    r"^[ \t]*import[ \t]+((?:" + NAME + r"\.)*" + NAME + r"(?:\.\*)?)(?:[ \t]+as[ \t]+(" + NAME + r"))?[ \t]*;?", re.M
)
# A line whose code starts an import directive, read or not; not a value named import.
IMPORT_LINE = re.compile(r"^[ \t]*import[ \t]+[`\w]", re.M)
PACKAGE = re.compile(r"^[ \t]*package[ \t]+([\w.]+)", re.M)
# `<qualifier>.<type>.<name>`, the qualifier being a name or `org.ntust.app.tigerduck.R`.
# Kotlin allows white space, line breaks among it, around each dot.
REFERENCE = re.compile(
    r"(?<![\w.`])(org\.ntust\.app\.tigerduck\.R|" + NAME + r")\s*\.\s*(" + NAME + r")\s*\.\s*(" + NAME + r")"
)


def classify(text: str) -> List[int]:
    """CODE, COMMENT, LITERAL or QUOTED for each character of Kotlin source [text].

    Enough of Kotlin's lexer for this job: line and (nested) block comments,
    character literals, backticked names, and string literals, plain and raw,
    whose templates are code again. A template opens with as many `$` as the
    literal has before its opening quote: `${` in "..." and in a raw string,
    `$${` in a two-dollar literal, `$$"..."` or its raw form, where `${` is just text.
    """
    kinds = [CODE] * len(text)
    # The innermost construct last: ["code", brace depth] for the file and each
    # template, ["string", raw, the `$` count a template opens with] for a literal.
    stack: List[list] = [["code", 0]]
    i, n = 0, len(text)
    while i < n:
        top = stack[-1]
        if top[0] == "code":
            if text.startswith("//", i):
                end = text.find("\n", i)
                end = n if end < 0 else end
                kinds[i:end] = [COMMENT] * (end - i)
                i = end
            elif text.startswith("/*", i):
                depth, j = 1, i + 2
                while j < n and depth:
                    if text.startswith("/*", j):
                        depth, j = depth + 1, j + 2
                    elif text.startswith("*/", j):
                        depth, j = depth - 1, j + 2
                    else:
                        j += 1
                kinds[i:j] = [COMMENT] * (j - i)
                i = j
            elif text[i] == '"':
                # Multi-dollar interpolation: the `$`s right before the quote are the literal's.
                dollars = 0
                while i - dollars - 1 >= 0 and text[i - dollars - 1] == "$" and kinds[i - dollars - 1] == CODE:
                    dollars += 1
                raw = text.startswith('"""', i)
                start, i = i - dollars, i + (3 if raw else 1)
                kinds[start:i] = [LITERAL] * (i - start)
                stack.append(["string", raw, max(dollars, 1)])
            elif text[i] == "'":
                j = i + 1
                while j < n and text[j] != "'" and text[j] != "\n":
                    j += 2 if text[j] == "\\" else 1
                j = min(j + 1, n)
                kinds[i:j] = [LITERAL] * (j - i)
                i = j
            elif text[i] == "`":
                end = text.find("`", i + 1)
                end = n if end < 0 else end + 1
                kinds[i + 1:end] = [QUOTED] * (end - i - 1)
                i = end
            elif text[i] == "{":
                top[1] += 1
                i += 1
            elif text[i] == "}":
                if top[1] == 0 and len(stack) > 1:
                    # The end of a template: back into its string.
                    kinds[i] = LITERAL
                    stack.pop()
                else:
                    top[1] = max(top[1] - 1, 0)
                i += 1
            else:
                i += 1
        else:
            _, raw, dollars = top
            opener = "$" * dollars + "{"
            if raw and text.startswith('"""', i):
                # A raw string may end in more quotes than three; the last three close it.
                j = i + 3
                while j < n and text[j] == '"':
                    j += 1
                kinds[i:j] = [LITERAL] * (j - i)
                stack.pop()
                i = j
            elif not raw and text[i] == '"':
                kinds[i] = LITERAL
                stack.pop()
                i += 1
            elif not raw and text[i] == "\\":
                kinds[i:i + 2] = [LITERAL] * len(text[i:i + 2])
                i += 2
            elif not raw and text[i] == "\n":
                # An unterminated literal; Kotlin would reject it. Resynchronize.
                stack.pop()
                i += 1
            elif text.startswith(opener, i):
                kinds[i:i + len(opener)] = [LITERAL] * len(opener)
                stack.append(["code", 0])
                i += len(opener)
            else:
                kinds[i] = LITERAL
                i += 1
    return kinds


def unquote(name: str) -> str:
    return name.replace("`", "")


class Ambiguous(Exception):
    """A file this script cannot rewrite for sure: it is left as it is, for a person to."""


def previous_significant(text: str, kinds: List[int], at: int) -> str:
    """The last character before [at] that is neither white space nor a comment, or ""."""
    i = at - 1
    while i >= 0 and (text[i].isspace() or kinds[i] == COMMENT):
        i -= 1
    return text[i] if i >= 0 else ""


def next_significant(text: str, kinds: List[int], at: int) -> str:
    """The two characters from the first at or after [at] that is neither white space nor a comment."""
    i = at
    while i < len(text) and (text[i].isspace() or kinds[i] == COMMENT):
        i += 1
    return text[i:i + 2]


def is_strings_resource(kind: str, name: str) -> bool:
    return kind in STRINGS_TYPES or (kind, name) in STRINGS_RESOURCES


class Import:
    """One import directive: [start] its line, [end] the directive, [line_end] the line's break."""

    def __init__(self, match: "re.Match[str]", line_end: int):
        self.start, self.end, self.line_end = match.start(), match.end(), line_end
        self.target_span = match.span(1)
        # As written, and as Kotlin reads it: `R` and R are one name.
        self.raw_target = match.group(1)
        self.target = unquote(match.group(1))
        self.alias = unquote(match.group(2)) if match.group(2) else None
        self.trailing = False

    @property
    def name(self) -> str:
        return self.alias or self.target.rsplit(".", 1)[-1]

    def sort_key(self) -> Tuple[int, str]:
        return import_sort_key(self.target, self.alias is not None)


def parse_imports(text: str, kinds: List[int]) -> List[Import]:
    """The file's import directives: in code, each alone on its line but for a comment."""
    imports = []
    for match in IMPORT.finditer(text):
        if kinds[match.start(1)] != CODE:
            continue
        line_end = text.find("\n", match.end())
        line_end = len(text) if line_end < 0 else line_end
        rest = range(match.end(), line_end)
        if not all(text[k].isspace() or kinds[k] == COMMENT for k in rest):
            continue
        imp = Import(match, line_end)
        imp.trailing = any(kinds[k] == COMMENT for k in rest)
        imports.append(imp)
    return imports


def parse_package(text: str, kinds: List[int]) -> Optional["re.Match[str]"]:
    return next((m for m in PACKAGE.finditer(text) if kinds[m.start(1)] == CODE), None)


def import_sort_key(target: str, aliased: bool) -> Tuple[int, str]:
    """IntelliJ's Kotlin layout: everything else, then java, javax and kotlin, then aliases."""
    if aliased:
        group = 4
    elif target.startswith("java."):
        group = 1
    elif target.startswith("javax."):
        group = 2
    elif target.startswith("kotlin."):
        group = 3
    else:
        group = 0
    return group, target


def newline(text: str) -> str:
    """The line break [text] uses, so a file written with CRLF keeps it."""
    return "\r\n" if "\r\n" in text else "\n"


def insert_import(text: str, target: str, alias: Optional[str] = None) -> str:
    """[text] with `import <target>[ as <alias>]` added where IntelliJ would sort it."""
    nl = newline(text)
    line = "import " + target + (" as " + alias if alias else "")
    kinds = classify(text)
    imports = parse_imports(text, kinds)
    if not imports:
        package = parse_package(text, kinds)
        if package is None:
            return line + nl + nl + text
        end = text.find("\n", package.end())
        end = len(text) if end < 0 else end
        if text[end - 1:end] == "\r":
            end -= 1
        return text[:end] + nl + nl + line + text[end:]
    key = import_sort_key(target, alias is not None)
    # After the last of its group to sort before it, which also holds in a file not quite sorted...
    preceding = [i for i in imports if i.sort_key()[0] == key[0] and i.sort_key() < key]
    if preceding:
        at = max(preceding, key=Import.sort_key).line_end
        if text[at - 1:at] == "\r":
            at -= 1
        return text[:at] + nl + line + text[at:]
    # ...or else before the first to sort after it.
    for imp in imports:
        if imp.sort_key() > key:
            return text[:imp.start] + line + nl + text[imp.start:]
    at = imports[-1].line_end
    if text[at - 1:at] == "\r":
        at -= 1
    return text[:at] + nl + line + text[at:]


def swap_import(text: str) -> str:
    """[text] importing the strings module's R as R in place of :app's."""
    kinds = classify(text)
    imports = parse_imports(text, kinds)
    old = next((i for i in imports if i.target == APP_R and i.name == "R"), None)
    if old is None:
        # The app's own package, or all of it imported: its R was never imported by name.
        return insert_import(text, STRINGS_R)
    if old.trailing or len(imports) == 1:
        # In place, keeping the comment beside it.
        return text[:old.target_span[0]] + STRINGS_R + text[old.target_span[1]:]
    end = old.line_end + 1 if old.line_end < len(text) else old.line_end
    return insert_import(text[:old.start] + text[end:], STRINGS_R)


def free_alias(bound: Set[str]) -> str:
    if ALIAS not in bound:
        return ALIAS
    n = 2
    while ALIAS + str(n) in bound:
        n += 1
    return ALIAS + str(n)


def rewrite(text: str) -> str:
    """[text] with its string references pointed at :core:strings' R; [text] itself if none are wrong."""
    kinds = classify(text)
    imports = parse_imports(text, kinds)
    package_match = parse_package(text, kinds)
    package = package_match.group(1) if package_match else ""

    app_names: Set[str] = {i.name for i in imports if i.target == APP_R}
    strings_names: Set[str] = {i.name for i in imports if i.target == STRINGS_R}
    bound = {i.name for i in imports if i.name != "*"}
    if "R" not in bound and (package == APP_PACKAGE or any(i.target == APP_PACKAGE + ".*" for i in imports)):
        # The app's own package, or a file importing all of it: its R needs no import by name.
        app_names.add("R")

    # (start, end, replacement) of each edit, all of them at offsets in [text].
    edits: List[Tuple[int, int, str]] = []
    # Imports of org.ntust.app.tigerduck.R.string, or of a string in it.
    for imp in imports:
        if not imp.raw_target.startswith(APP_R + "."):
            continue
        parts = imp.target[len(APP_R) + 1:].split(".")
        if parts[0] in STRINGS_TYPES or tuple(parts[:2]) in STRINGS_RESOURCES:
            start, end = imp.target_span
            edits.append((start, start + len(APP_R), STRINGS_R))

    directives = [(i.start, i.end) for i in imports]
    if package_match:
        directives.append((package_match.start(), package_match.end()))

    def in_directive(at: int) -> bool:
        return any(start <= at < end for start, end in directives)

    wrong: List[Tuple[int, int, str]] = []  # (start, end, qualifier) of each string reached through :app's R
    qualifiers: Set[int] = set()
    for match in REFERENCE.finditer(text):
        if kinds[match.start()] in (LITERAL, QUOTED) or in_directive(match.start()):
            continue
        # The tail of a longer qualified name, broken over lines: androidx.compose.material3.
        # then R.string.x on the next.
        if previous_significant(text, kinds, match.start()) == ".":
            continue
        qualifier = match.group(1)
        if qualifier != APP_R:
            qualifier = unquote(qualifier)
            if qualifier not in app_names:
                continue
        if is_strings_resource(unquote(match.group(2)), unquote(match.group(3))):
            wrong.append((match.start(1), match.end(1), qualifier))
            qualifiers.add(match.start(1))

    def uses(name: str) -> List[int]:
        """Where code names [name] itself, not as the tail of a longer qualified name."""
        use = re.compile(r"(?<![\w.`])(?:`" + re.escape(name) + r"`|" + re.escape(name) + r")(?![\w`])")
        return [
            m.start() for m in use.finditer(text)
            if kinds[m.start()] == CODE and not in_directive(m.start())
            and previous_significant(text, kinds, m.start()) != "."
        ]

    # Any other use of :app's R by name in code keeps its import: R.drawable.x,
    # R.style::class, R itself.
    app_used = any(at not in qualifiers for name in app_names for at in uses(name))

    by_name = [w for w in wrong if w[2] != APP_R]
    code_by_name = [w for w in by_name if kinds[w[0]] == CODE]
    if not edits and not any(kinds[w[0]] == CODE for w in wrong):
        return text

    # Rewriting it now: first make sure the file reads as this script reads it.
    read = {text.count("\n", 0, i.start) for i in imports}
    for line in IMPORT_LINE.finditer(text):
        at = line.group(0).index("import") + line.start()
        if kinds[at] == CODE and text.count("\n", 0, line.start()) not in read:
            raise Ambiguous("an import it cannot read")
    for start, end in directives:
        line_end = text.find("\n", end)
        if line_end >= 0 and kinds[line_end] == COMMENT:
            raise Ambiguous("a directive running into a block comment")
    for name in app_names | strings_names:
        for at in uses(name):
            after = next_significant(text, kinds, at + len(name) + (2 if text[at] == "`" else 0))
            if not (after.startswith(".") and after != "..") and after != "::":
                raise Ambiguous("%s named by something of its own" % name)

    swap = bool(code_by_name) and not app_used and not strings_names and "R" in app_names
    if swap:
        target = "R"
    elif strings_names:
        target = sorted(strings_names)[0]
    else:
        target = free_alias(bound | app_names | {unquote(m.group(0)) for m in re.finditer(NAME, text) if kinds[m.start()] != LITERAL})

    for start, end, qualifier in wrong:
        if qualifier == APP_R:
            edits.append((start, end, STRINGS_R))
        elif code_by_name:
            edits.append((start, end, target))
    for start, end, replacement in sorted(edits, reverse=True):
        text = text[:start] + replacement + text[end:]

    if not code_by_name:
        return text
    if swap:
        return swap_import(text)
    if not strings_names:
        return insert_import(text, STRINGS_R, target)
    return text


def default_roots(root: Path) -> List[Path]:
    roots = [root / "app" / "src"]
    for group in ("core", "feature"):
        roots += sorted(p for p in (root / group).glob("*/src") if p.is_dir())
    return [p for p in roots if p.is_dir()]


def kotlin_files(paths: Iterable[Path]) -> List[Path]:
    files: List[Path] = []
    for path in paths:
        if path.is_file():
            files.append(path)
        else:
            files += sorted(path.rglob("*.kt"))
    return files


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="*", type=Path, help="Files or directories (default: every module's src)")
    parser.add_argument("--check", action="store_true", help="Change nothing; list what would change and exit 1 if any")
    args = parser.parse_args(argv)

    files = kotlin_files(args.paths or default_roots(ROOT))
    changed: List[Path] = []
    left: List[Tuple[Path, str]] = []
    for path in files:
        # newline="" reads line breaks as they are, and writes them back the same way.
        with path.open("r", encoding="utf-8", newline="") as handle:
            original = handle.read()
        try:
            updated = rewrite(original)
        except Ambiguous as reason:
            left.append((path, str(reason)))
            continue
        if updated != original:
            changed.append(path)
            if not args.check:
                with path.open("w", encoding="utf-8", newline="") as handle:
                    handle.write(updated)

    def shown(path: Path) -> str:
        try:
            return str(path.resolve().relative_to(ROOT))
        except ValueError:
            return str(path)

    for path in changed:
        print(shown(path))
    for path, reason in left:
        print("%s: left alone, %s; point its strings at %s by hand" % (shown(path), reason, STRINGS_R), file=sys.stderr)
    verb = "would change" if args.check else "changed"
    print("%d of %d Kotlin files %s." % (len(changed), len(files), verb), file=sys.stderr)
    return 1 if left or (args.check and changed) else 0


if __name__ == "__main__":
    raise SystemExit(main())
