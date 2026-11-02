#!/usr/bin/env python3
"""Fails CI when a long file, a long function or a copied rule is added.

The refactor gives each duplicated rule one home. This check stops the
duplicates and the giant files from growing back. Today's offenses live in
baseline.json, which may only shrink: a count above the baseline, or a path
the baseline does not list, fails.

Offense kinds (counts in the baseline):
  long-file       a Kotlin file over 400 lines; the count is its line count.
  long-function   a function over 80 lines (brace-counted; an expression body
                  counts to the end of its expression), recorded one by one as
                  {path, kind, name, lines}. The name is the enclosing
                  classes, the receiver, the function name and its parameter
                  types, e.g. MailRepository.search(String, Int), so adding
                  an overload does not rename a baselined function. A
                  renamed function counts as new.
  taipei-literal  occurrences of the "Asia/Taipei" literal outside TaipeiClock.kt.
  fdroid-literal  occurrences of the "fdroid" literal outside BuildConfig reads
                  in AppPreferences.kt.
  term-sort       a sort over a semester or term value outside AcademicTerm.kt.
                  Only the identifiers semester(s), semesterCode, term(s) and
                  termCode count, in sortedBy, sortedWith, sortBy, sortWith,
                  compareBy, thenBy and their Descending forms.
  top-app-bar     a call to one of Material's top app bars outside ui/component.
                  A page's bar is PageScaffold's. The baseline has no entries of
                  this kind and takes none.
  account-counter lines that compare an account by a hand-kept number: a
                  generation or epoch counter, DataCache.Generation, or a
                  "generation: Int" or "signIn: Int" parameter. The sign-in
                  type replaces them.
  demo-check      lines that ask whether the app is in the demo, outside the
                  demo's own homes. One place answers it.
  sign-in-read    lines that name StartsAccountWork outside the layers that may
                  start account work. Every other layer is handed the sign-in.
                  The baseline has no entries of this kind and takes none.
  bearer-home     lines that write a "Bearer " header outside the token manager,
                  the guard that reads it, and the one line of the departing
                  unregister, the only request with no sign-in. Takes no
                  baseline entries either.
  sign-out-reactor lines that follow a sign-in or a sign-out by watching state or
                  a stream of the account's own. Every reactor reads one stream
                  of account events, so a fast sign-out and sign-in can't be
                  missed. Takes no baseline entries either.
  sign-out-start  lines that start an account's sign-out outside its plan: the
                  token manager's logout, School Mail's or the library's
                  sign-out, or the school's or the library's credentials
                  dropped, anywhere but SignOutPlans.kt and those accounts'
                  own homes. One plan runs every sign-out, in order. Takes no
                  baseline entries either.
  sync-switch-read lines that name a sync switch or the re-enable hold
                  (cloudSyncEnabled, syncCourses, syncCourseColors,
                  syncCourseNames, syncAssignments, pendingConflictCategories)
                  outside SyncPolicy and the files that store or send them.
                  SyncPolicy answers whether a category keeps, sends or applies.
  sync-write      calls of a backend sync write (the override PATCHes, the
                  holiday PUT, the course deletes and uploads, the assignment
                  upload, and their ...Answered forms), and references to one
                  (client::deleteCourse), outside push/, sync/ and the queues.
                  A declaration is not a call. A write leaves through its
                  channel's outbox.
  status-in-message contains() of a status code or a backend error code on a
                  message. BackendError reads the code once. It has no home.
  full-sync-fetch calls of fetchFullSync, and references to it, outside
                  SyncApiClient and the Synchronizer, which runs every full sync.
  api-version-literal string literals that are "v3" or "v4", or hold /v3 or
                  /v4 as a path segment, outside ApiBase, which turns a base
                  from one version to the other. Takes no baseline entries.

Every source set of app, shared and wear is read except the test ones (test,
androidTest, testFixtures and anything starting with test or androidTest).
Stdlib only, and it must run on Python 3.9.

    python3 tools/guardrails/check_guardrails.py [--update-baseline]
                                                 [--against-ref REF]

--against-ref REF also fails when the working baseline has an entry that
REF's baseline lacks or a count above REF's, so a PR cannot raise its own
limit. A REF with no baseline file (the PR that adds it) skips that check.
A kind that REF's check_guardrails.py does not name is skipped too: it is the
PR that introduces the kind, and from the next PR on it is held like the rest.

--introduce KIND records today's counts for a kind the baseline has no entries
of, so a new ratchet can land with offenses already in the tree. It refuses a
kind that already has entries; --update-baseline stays downward only.

A baselined function whose parameter types changed is still the same function
when its file holds one function of that name on each side; its lines may not
grow.
"""
import argparse
import json
import os
import re
import subprocess
import sys

MODULES = ("app", "shared", "wear")
MAX_FILE_LINES = 400
MAX_FUNCTION_LINES = 80
BASELINE_IN_REPO = "tools/guardrails/baseline.json"

PUSH_API = "app/src/main/java/org/ntust/app/tigerduck/push/PushApiClient.kt"
TAIPEI_HOME = "shared/src/main/java/org/ntust/app/tigerduck/shared/time/TaipeiClock.kt"
TERM_HOME = "shared/src/main/java/org/ntust/app/tigerduck/shared/time/AcademicTerm.kt"
PREFERENCES = "app/src/main/java/org/ntust/app/tigerduck/data/preferences/AppPreferences.kt"

SORT_CALL = re.compile(
    r"\b(?:sortedBy|sortedByDescending|sortedWith|sortBy|sortByDescending|sortWith"
    r"|compareBy|compareByDescending|thenBy|thenByDescending)\b")
# Explicit identifiers only: a field that merely contains "term" (terminalId)
# is not a term code, and "code" alone cannot be told from other codes.
TERM_NAME = re.compile(r"\b(?:semesters?|semesterCode|terms?|termCode)\b", re.IGNORECASE)
# Material's own names, so a page's helper that merely ends in the word passes.
TOP_APP_BAR_NAMES = (r"(?:CenterAligned|Medium|MediumFlexible|Large|LargeFlexible|TwoRows)?"
                     r"TopAppBar")
# A backtick may wrap the name, which Kotlin allows around any identifier.
TOP_APP_BAR = re.compile(r"\b" + TOP_APP_BAR_NAMES + r"`?\s*\(")
# `import ...material3.TopAppBar as MaterialBar` renames the call.
TOP_APP_BAR_ALIAS = re.compile(
    r"\bimport\s+androidx\.compose\.material3\.`?" + TOP_APP_BAR_NAMES
    + r"`?\s+as\s+`?(\w+)`?")


def top_app_bar_calls(code):
    """Calls to a Material top app bar, under its own name or an import alias."""
    count = len(TOP_APP_BAR.findall(code))
    for alias in TOP_APP_BAR_ALIAS.findall(code):
        count += len(re.findall(r"(?<![\w.])`?" + re.escape(alias) + r"`?\s*\(", code))
    return count


ACCOUNT_COUNTER = re.compile(
    r"\b(?:signInGeneration|userDataGeneration|writeGeneration|accountGeneration"
    r"|libraryAccountGeneration|schoolAccountGeneration|currentGeneration"
    r"|cacheGeneration|accountEpoch|isStillSignedInAs)\b"
    r"|DataCache\.Generation|\b(?:generation|signIn): Int\b")
DEMO_CHECK = re.compile(
    r"\b(?:isDemo|isDemoSession|appDemoActive|demoMailbox|demoFromElsewhere)\b"
    r"|\b(?:demoAccount|demo)\.isActive\b|\.demoMode\b")
STARTS_ACCOUNT_WORK = re.compile(r"\bStartsAccountWork\b")
APP_PACKAGE = "/org/ntust/app/tigerduck/"

# Homes are regexes on the path under the app's package. A file inside one is
# where that rule is allowed to live.
DEMO_HOMES = re.compile(
    r"(?:auth/session/AccountSessions?\.kt$|shared/account/SignIn\.kt$"
    r"|debug/DemoModeInterceptor\.kt$|debug/DebugFixtureStore\.kt$"
    r"|demo/|mail/MailDemo\.kt$|emptyclassroom/ClassroomDemoData\.kt$)")
SIGN_IN_HOMES = re.compile(
    r"(?:auth/|ui/screen/|ui/AppState\.kt$|TigerDuckApp\.kt$|ShareCodeLink\.kt$|demo/|liveactivity/"
    r"|notification/.*(?:Worker|Receiver)\.kt$|push/PushRegistrationService\.kt$"
    r"|push/NotificationSettingsSync\.kt$|mail/MailAccount\.kt$|mail/MailRepository\.kt$"
    r"|mail/sync/MailChecker\.kt$"
    r"|data/moodle/|data/DataMigration\.kt$|network/MoodleService\.kt$|network/CourseService\.kt$|network/moodle/"
    r"|network/AccountSessionGuard\.kt$|network/NtustSessionManager\.kt$)")
SIGN_OUT_REACTOR = re.compile(
    r"\b(?:authState|accountState|signOuts|studentChanges|signIns)\.collect\b|\bauthState\.first\b")
# A call, or a reference handed on to run it later. LibraryAccountGate's own signOut { } is not one.
SIGN_OUT_START = re.compile(
    r"\b(?:authTokenManager\.logout|(?:mailAccount|account|librarySession)\.signOut"
    r"|credentials\.signOutSchoolAccount|clearLibraryCredentials)\s*\("
    r"|\b(?:authTokenManager::logout|(?:mailAccount|account|librarySession)::signOut"
    r"|credentials::signOutSchoolAccount)\b|::clearLibraryCredentials\b")
SIGN_OUT_HOMES = re.compile(
    r"(?:auth/session/SignOutPlans\.kt$|mail/MailAccount\.kt$|library/LibrarySession\.kt$"
    r"|data/preferences/CredentialManager\.kt$)")
BEARER_HOMES = re.compile(r"(?:auth/AuthTokenManager\.kt$|auth/SessionHostGuard\.kt$)")
SYNC_SWITCH = re.compile(
    r"\b(?:cloudSyncEnabled|syncCourses|syncCourseColors|syncCourseNames|syncAssignments"
    r"|pendingConflictCategories)\b")
SYNC_SWITCH_HOMES = re.compile(
    r"(?:sync/SyncPolicy\.kt$|data/preferences/AppPreferences\.kt$|data/preferences/SchoolKeys\.kt$"
    r"|push/PushApiModels\.kt$)")
# A call, or a reference (client::deleteCourse) handed on to make it later. A name that
# merely starts with one (deleteCourseCaches) is not one of the writes, and a declaration
# of one is not a call: see calls().
SYNC_WRITES = (r"(?:patchAssignmentOverride|patchCourseOverride|patchCourseIgnore|putHolidayOverride"
               r"|deleteCourse|deleteAllCourses|uploadCourses|uploadAssignments)(?:Answered)?")
SYNC_WRITE = re.compile(r"\b" + SYNC_WRITES + r"\s*\(|::\s*" + SYNC_WRITES + r"\b")
QUEUES = r"(?:UnsentMarks|PendingHolidayOverrides|PendingCourseOverrides|CourseIgnoreSync" \
         r"|PendingCourseAdds|PendingCourseDeletes|CourseIgnoreQueue)"
SYNC_WRITE_HOMES = re.compile(r"(?:push/|sync/|data/" + QUEUES + r"\.kt$)")
# Positional or named: contains("401"), contains(other = "401"), and either after a
# named ignoreCase.
STATUS_IN_MESSAGE = re.compile(
    r'\bcontains\s*\(\s*(?:ignoreCase\s*=\s*\w+\s*,\s*)?(?:other\s*=\s*)?'
    r'"(?:\d{3}|session_revoked|clock_ahead|hlc_required)"')
FULL_SYNC_FETCH = re.compile(r"\bfetchFullSync\s*\(|::\s*fetchFullSync\b")
# What stands between "fun" and the name it declares on that line: modifiers come before
# "fun", and after it only type parameters and a receiver type (Foo<T>?.), comments blanked.
DECLARES = re.compile(r"\bfun\b[\s\w.<>,?*:`]*$")
FULL_SYNC_HOMES = re.compile(r"(?:push/SyncApiClient\.kt$|sync/Synchronizer\.kt$)")
# On a literal's own text, a template standing as TEMPLATE: "v3" itself, or v3 or v4
# as a whole path segment, "/v4", "https://h/v3/", "$base/v4?x" or "${base}/v3".
TEMPLATE = "\x00"
API_VERSION = re.compile(r"(?:^|/)v[34](?=$|[/?#$\x00])")
API_VERSION_HOMES = re.compile(r"network/backend/ApiBase\.kt$")
BEARER_LITERAL = '"Bearer '
UNREGISTER_BEARER = 'removeDevice("Bearer '
# Kinds that are counts of lines or calls and, apart from these, take no baseline entry
# the flag cannot record.
INTRODUCIBLE = ("account-counter", "demo-check", "sync-switch-read", "sync-write",
                "status-in-message", "full-sync-fetch")


def in_home(rel, homes):
    """True when rel sits under the app package at a path the homes allow."""
    at = rel.find(APP_PACKAGE)
    return at >= 0 and homes.match(rel[at + len(APP_PACKAGE):]) is not None


def matching_lines(code, pattern):
    return sum(1 for line in code.split("\n") if pattern.search(line))


def calls(code, pattern):
    """Calls and callable references the pattern finds in lexed code, declarations left out.

    A match is a declaration when its line reads "fun" before it with nothing between but
    type parameters and a receiver type: fun deleteCourse(, fun <T> Foo<T>.deleteCourse(.
    Python's lookbehind is fixed-width, so the line before the match is read instead.
    """
    count = 0
    for m in pattern.finditer(code):
        line_start = code.rfind("\n", 0, m.start()) + 1
        if not DECLARES.search(code, line_start, m.start()):
            count += 1
    return count


def sign_in_reads(code):
    """Lines that name StartsAccountWork, apart from the import."""
    return sum(1 for line in code.split("\n")
               if STARTS_ACCOUNT_WORK.search(line) and not line.lstrip().startswith("import "))


# By directory rather than whole path, so the check still holds once ui/component
# moves into a module of its own.
SHARED_UI = "/ui/component/"
FUN = re.compile(r"\bfun\b")
CLASS_DECL = re.compile(r"(?<!::)\b(?:class|interface|object)\s+(\w+)")
COMPANION = re.compile(r"\bcompanion\s+object\s*(?=[{:])")

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.dirname(os.path.dirname(HERE))
DEFAULT_BASELINE = os.path.join(HERE, "baseline.json")


# ---------------------------------------------------------------- lexing

def string_literals(text):
    """The text of every string literal in text, a template in it standing as TEMPLATE.

    A literal inside a template is a literal of its own. Comments hold none.
    """
    found = []
    _code(text, 0, False, [], False, found)
    return found


def lex(text, keep_strings=False):
    """Returns text with comments and string text blanked, same length.

    Brace counting and the sort scan must not see a brace or a keyword that
    sits in a comment or a string. Code inside a "${...}" template stays, since
    it is code. With keep_strings the string text stays too (for the literal
    checks). A newline inside a raw string becomes "\\r" so that "end of line"
    means a line of code; offsets and the other newlines are unchanged.
    """
    out = []
    _code(text, 0, keep_strings, out, False)
    return "".join(out)


def _blank(chunk, raw):
    return "".join(("\r" if raw else "\n") if c == "\n" else " " for c in chunk)


def _code(text, i, keep, out, in_template, literals=None):
    n, depth = len(text), 0
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(_blank(text[i:j], False))
            i = j
        elif text.startswith("/*", i):
            nest, j = 1, i + 2
            while j < n and nest:
                if text.startswith("/*", j):
                    nest += 1
                    j += 2
                elif text.startswith("*/", j):
                    nest -= 1
                    j += 2
                else:
                    j += 1
            out.append(_blank(text[i:j], False))
            i = j
        elif text.startswith('"""', i):
            i = _string(text, i, keep, out, True, literals)
        elif c == '"':
            i = _string(text, i, keep, out, False, literals)
        elif c == "'":
            m = re.match(r"'(\\.[^']*|[^\\'])'", text[i:i + 12])
            if m:
                out.append(m.group(0) if keep else "'" + " " * (m.end() - 2) + "'")
                i += m.end()
            else:
                out.append(c)
                i += 1
        elif in_template and c == "{":
            depth += 1
            out.append(c)
            i += 1
        elif in_template and c == "}":
            if depth == 0:
                return i
            depth -= 1
            out.append(c)
            i += 1
        else:
            out.append(c)
            i += 1
    return i


def _string(text, i, keep, out, raw, literals=None):
    quote = '"""' if raw else '"'
    out.append(quote)
    i += len(quote)
    n = len(text)
    own = []

    def done(at):
        if literals is not None:
            literals.append("".join(own))
        return at

    while i < n:
        if text.startswith(quote, i):
            out.append(quote)
            return done(i + len(quote))
        c = text[i]
        if not raw and c == "\n":
            return done(i)  # unterminated; let the code go on
        if not raw and c == "\\":
            out.append(text[i:i + 2] if keep else "  ")
            own.append(text[i:i + 2])
            i += 2
        elif text.startswith("${", i):
            out.append("${" if keep else "  ")
            own.append(TEMPLATE)
            i = _code(text, i + 2, keep, out, True, literals)
            out.append("}" if keep else " ")
            i += 1
        else:
            out.append(c if keep else _blank(c, raw) if c == "\n" else " ")
            own.append(c)
            i += 1
    return done(i)


# ---------------------------------------------------------------- scanning helpers

def skip_ws(code, i):
    while i < len(code) and code[i].isspace():
        i += 1
    return i


def balanced(code, i, open_c, close_c):
    """Index of the bracket closing the one at code[i] (or the last index)."""
    depth, n = 0, len(code)
    while i < n:
        if code[i] == open_c:
            depth += 1
        elif code[i] == close_c:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n - 1


def angle_end(code, i):
    """Index of the ">" closing the "<" at code[i]; "->" is not a closer."""
    depth, n = 0, len(code)
    while i < n:
        if code[i] == "<":
            depth += 1
        elif code[i] == ">" and code[i - 1] != "-":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n - 1


def prev_char_index(code, i):
    j = i - 1
    while j >= 0 and code[j].isspace():
        j -= 1
    return j


ENDS_OPEN = ("=", "+", "-", "*", "/", "%", ",", "&&", "||", "?:", "->", "(", ".", "?.")
CONTINUES = re.compile(
    r"(?:else|catch|finally|as|in|is)\b|\.|\?\.|\?:|&&|\|\||->|==|!=|[-+*/%]")


DANGLING_WORD = re.compile(r"\b(?:else|try|finally)\s*$")
CONTROL_HEAD = re.compile(r"\b(?:if|while|for|when)\s*\(")


def needs_branch(line):
    """True when the line ends in `if (...)`, `else`, `try` and the like, with the branch on the next line."""
    line = line.rstrip()
    if DANGLING_WORD.search(line):
        return True
    for m in CONTROL_HEAD.finditer(line):
        if balanced(line, m.end() - 1, "(", ")") == len(line) - 1:
            return True
    return False


def expression_end(code, i):
    """Index where the expression body starting at code[i] ends.

    It ends at the first newline outside every bracket whose line does not
    end in an operator and whose next line does not start with one, or with
    else, catch or finally.
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
        elif c == ";" and depth == 0:
            return i
        elif c == "\n" and depth == 0:
            j = prev_char_index(code, i)
            if (not code[:j + 1].endswith(ENDS_OPEN)
                    and not CONTINUES.match(code, skip_ws(code, i))
                    and not needs_branch(code[code.rfind("\n", 0, i) + 1:i])):
                return i
        i += 1
    return n


def body_end(code, i):
    """What follows a parameter list: ("block"|"expr", end index) or None when bodiless.

    A newline does not end the declaration while the signature is incomplete
    (after ":" or ",", inside "<...>"), nor when the next line starts the body
    with "{", "=", ":" or "where".
    """
    n, depth = len(code), 0
    while i < n:
        c = code[i]
        if c == "\n" and depth <= 0:
            j = prev_char_index(code, i)
            k = skip_ws(code, i)
            if not (code[j] in ",:<" or code[:j + 1].endswith("->")
                    or re.match(r"\{|=|:|where\b", code[k:k + 6])):
                return None
        elif c in "(<[":
            depth += 1
        elif c in ")]" or (c == ">" and code[i - 1] != "-"):
            depth = max(depth - 1, 0)
        elif depth <= 0:
            if c == "{":
                return "block", balanced(code, i, "{", "}")
            if c == "=":
                return "expr", expression_end(code, i + 1)
            if c == ";":
                return None
        i += 1
    return None


def param_types(text):
    """The types of a parameter list, with default values dropped.

    Commas split parameters only outside (), [] and {}, and outside the
    generic <...> of a type. A "<" in a default value (`= 1 < 2`) is an
    operator, so angle brackets are tracked in the type part alone.
    """
    types, cur = [], []
    depth = angle = 0
    state = "name"  # then "type" after the colon, then "default" after "="
    for k, c in enumerate(text + ","):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and state == "type" and c == "<":
            angle += 1
        elif depth == 0 and state == "type" and c == ">" and text[k - 1] != "-":
            angle = max(angle - 1, 0)
        elif depth == 0 and angle == 0:
            if c == "," :
                if "".join(cur).strip() or state != "name":
                    types.append(re.sub(r"\s+", "", "".join(cur)))
                cur, state = [], "name"
                continue
            if c == ":" and state == "name":
                state = "type"
                continue
            if c == "=" and state == "type":
                state = "default"
                continue
        if state == "type":
            cur.append(c)
    return [t for t in types if t]


# ---------------------------------------------------------------- functions

def parse_function(code, start):
    """For the `fun` at start: (receiver, name, param types, end) or None."""
    n = len(code)
    i = skip_ws(code, start + 3)
    if code.startswith("<", i):
        i = skip_ws(code, angle_end(code, i) + 1)
    hstart = i
    if code.startswith("(", i):
        j = skip_ws(code, balanced(code, i, "(", ")") + 1)
        if j < n and code[j] == ".":
            i = j  # a parenthesized receiver, as in `fun (Int) -> Unit.foo()`
        else:
            i = hstart  # an anonymous function: these are its parameters
    depth = 0
    while i < n:
        c = code[i]
        if c == "<":
            depth += 1
        elif c == ">" and code[i - 1] != "-":
            depth -= 1
        elif c == "(" and depth <= 0:
            break
        elif c in "{=;" and depth <= 0:
            return None
        i += 1
    if i >= n:
        return None
    header = re.sub(r"\s+", "", code[hstart:i])
    m = re.search(r"([\w`]+)$", header)
    name = m.group(1).strip("`") if m else "<anonymous>"
    receiver = header[:m.start()].rstrip(".") if m else ""
    pe = balanced(code, i, "(", ")")
    types = param_types(code[i + 1:pe])
    body = body_end(code, pe + 1)
    if body is None:
        return None
    return receiver, name, types, body[1]


def declaration_body(code, i):
    """(start, end) of the braces of the class/object at i, or None when it has none."""
    n, depth = len(code), 0
    while i < n:
        c = code[i]
        if c == "\n" and depth <= 0:
            j = prev_char_index(code, i)
            k = skip_ws(code, i)
            if not (code[j] in ",:" or re.match(r"\{|:|where\b|by\b|,", code[k:k + 6])):
                return None
        elif c in "(<[":
            depth += 1
        elif c in ")]" or (c == ">" and code[i - 1] != "-"):
            depth = max(depth - 1, 0)
        elif depth <= 0:
            if c == "{":
                return i, balanced(code, i, "{", "}")
            if c in ";=":
                return None
        i += 1
    return None


def containers(code):
    """[(body start, body end, name)] of every class, interface and object."""
    found = []
    for m in CLASS_DECL.finditer(code):
        body = declaration_body(code, m.end())
        if body:
            found.append((body[0], body[1], m.group(1)))
    for m in COMPANION.finditer(code):
        body = declaration_body(code, m.end())
        if body:
            found.append((body[0], body[1], "Companion"))
    return sorted(found)


def long_functions(text, code):
    """[(identity, lines)] for every function with a body."""
    found, seen = [], {}
    enclosing = containers(code)
    for m in FUN.finditer(code):
        if re.match(r"\s+interface\b", code[m.end():m.end() + 20]):
            continue  # `fun interface`, not a function
        parsed = parse_function(code, m.start())
        if parsed is None:
            continue
        receiver, name, types, end = parsed
        parts = [c[2] for c in enclosing if c[0] < m.start() < c[1]]
        parts.append(receiver + "." + name if receiver else name)
        ident = "%s(%s)" % (".".join(parts), ", ".join(types))
        seen[ident] = seen.get(ident, 0) + 1
        if seen[ident] > 1:
            ident += "#%d" % seen[ident]  # an identical signature gets an index
        found.append((ident, text.count("\n", m.start(), end) + 1))
    return found


def term_sorts(code):
    """How many sorts over a term identifier the code holds."""
    count, covered, n = 0, -1, len(code)
    for m in SORT_CALL.finditer(code):
        if m.start() < covered:
            continue  # inside a sort already counted: sortedWith(compareBy { ... })
        i = skip_ws(code, m.end())
        if code.startswith("<", i):
            i = skip_ws(code, angle_end(code, i) + 1)
        if i >= n or code[i] not in "({":
            continue
        end = balanced(code, i, code[i], ")" if code[i] == "(" else "}")
        if code[i] == "(":
            k = end + 1
            while k < n and code[k] in " \t":
                k += 1
            if k < n and code[k] == "{":
                end = balanced(code, k, "{", "}")  # a trailing lambda: sortedBy() { ... }
        if TERM_NAME.search(code, i, end):
            count += 1
            covered = end
    return count


# ---------------------------------------------------------------- scan

KINDS = ("long-file", "long-function", "taipei-literal", "fdroid-literal", "term-sort",
         "top-app-bar", "account-counter", "demo-check", "sign-in-read", "bearer-home",
         "sign-out-reactor", "sign-out-start", "sync-switch-read", "sync-write",
         "status-in-message", "full-sync-fetch", "api-version-literal")


def is_test_set(name):
    return name.startswith(("test", "androidTest"))


def source_files(root):
    for module in MODULES:
        src = os.path.join(root, module, "src")
        if not os.path.isdir(src):
            continue
        for source_set in sorted(os.listdir(src)):
            if is_test_set(source_set):
                continue
            for dirpath, _, names in os.walk(os.path.join(src, source_set)):
                for name in sorted(names):
                    if name.endswith(".kt"):
                        full = os.path.join(dirpath, name)
                        yield os.path.relpath(full, root).replace(os.sep, "/"), full


class Found(dict):
    """The offenses, and how many functions of each name each file holds (names), long or not."""

    def __init__(self):
        super().__init__()
        self.names = {}


def scan(root):
    """Returns {(path, kind, name): n} for every offense under root.

    n is a count, a file's line count (long-file) or a function's line count
    (long-function, the only kind with a name).
    """
    found = Found()

    def add(path, kind, count, name=""):
        if count > 0:
            found[(path, kind, name)] = count

    for rel, full in source_files(root):
        with open(full, encoding="utf-8") as f:
            text = f.read()
        total = text.count("\n") + (0 if text.endswith("\n") or not text else 1)
        if total > MAX_FILE_LINES:
            add(rel, "long-file", total)

        code = lex(text)
        for name, lines in long_functions(text, code):
            base = SIGNATURE.match(name)
            if base:
                found.names[(rel, base.group(1))] = found.names.get((rel, base.group(1)), 0) + 1
            if lines > MAX_FUNCTION_LINES:
                add(rel, "long-function", lines, name)
        if rel != TERM_HOME:
            add(rel, "term-sort", term_sorts(code))
        if SHARED_UI not in rel:
            add(rel, "top-app-bar", top_app_bar_calls(code))
        add(rel, "account-counter", matching_lines(code, ACCOUNT_COUNTER))
        add(rel, "sign-out-reactor", matching_lines(code, SIGN_OUT_REACTOR))
        if not in_home(rel, SIGN_OUT_HOMES):
            add(rel, "sign-out-start", matching_lines(code, SIGN_OUT_START))
        if not in_home(rel, DEMO_HOMES):
            add(rel, "demo-check", matching_lines(code, DEMO_CHECK))
        if not in_home(rel, SIGN_IN_HOMES):
            add(rel, "sign-in-read", sign_in_reads(code))
        if not in_home(rel, SYNC_SWITCH_HOMES):
            add(rel, "sync-switch-read", matching_lines(code, SYNC_SWITCH))
        if not in_home(rel, SYNC_WRITE_HOMES):
            add(rel, "sync-write", calls(code, SYNC_WRITE))
        if not in_home(rel, FULL_SYNC_HOMES):
            add(rel, "full-sync-fetch", calls(code, FULL_SYNC_FETCH))
        if not in_home(rel, API_VERSION_HOMES):
            add(rel, "api-version-literal",
                sum(1 for literal in string_literals(text) if API_VERSION.search(literal)))

        # Literals are matched with strings kept and comments blanked, so a
        # KDoc that mentions the zone does not count.
        kept = lex(text, keep_strings=True)
        if rel != TAIPEI_HOME:
            add(rel, "taipei-literal", kept.count('"Asia/Taipei"'))
        add(rel, "fdroid-literal", sum(
            line.count('"fdroid"') for line in kept.split("\n")
            if not (rel == PREFERENCES and "BuildConfig" in line)))
        if not in_home(rel, BEARER_HOMES):
            # One call there: the unregister, which holds its session and no sign-in.
            rest = kept.replace(UNREGISTER_BEARER, "", 1) if rel == PUSH_API else kept
            add(rel, "bearer-home", rest.count(BEARER_LITERAL))
        add(rel, "status-in-message", len(STATUS_IN_MESSAGE.findall(kept)))
    return found


# ---------------------------------------------------------------- baseline

def parse_baseline(raw):
    return {(e["path"], e["kind"], e.get("name", "")): e.get("lines", e.get("count"))
            for e in json.loads(raw)}


def load_baseline(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return parse_baseline(f.read())


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


SIGNATURE = re.compile(r"^(.*?)\((.*)\)(#\d+)?$")


def without_parameters(name):
    """A function's identity minus its parameter types, or None for a non-function."""
    m = SIGNATURE.match(name)
    return (m.group(1) + (m.group(3) or "")) if m else None


def same_function(path, name, found, baseline):
    """The baseline's key for a function whose parameter types changed, or None.

    It matches only when the file holds one function of that name on each side,
    long or short, so overloads still tell each other apart: a short overload
    beside a new long one stops the long one inheriting the old allowance.
    """
    bare = without_parameters(name)
    if bare is None:
        return None
    names = getattr(found, "names", None)
    if names is not None:
        if names.get((path, SIGNATURE.match(name).group(1)), 0) != 1:
            return None
        ours = [k for k in found if k[0] == path and k[1] == "long-function"
                and without_parameters(k[2]) == bare]
    else:
        ours = [k for k in found if k[0] == path and k[1] == "long-function"
                and without_parameters(k[2]) == bare]
    theirs = [k for k in baseline if k[0] == path and k[1] == "long-function"
              and without_parameters(k[2]) == bare]
    return theirs[0] if len(ours) == 1 and len(theirs) == 1 else None


def overloads_at_ref(root, ref, path, name):
    """How many functions called name the file held at REF; 0 when it wasn't there."""
    shown = subprocess.run(["git", "-C", root, "show", "%s:%s" % (ref, path)],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    if shown.returncode != 0:
        return 0
    text = shown.stdout
    names = (SIGNATURE.match(n) for n, _ in long_functions(text, lex(text)))
    return sum(1 for m in names if m and m.group(1) == name)


def borrowed(found, baseline, root, ref):
    """Long functions matched by name alone in a file that held overloads of that name at REF.

    The baseline names only long functions, so a deleted overload's allowance would
    pass to a short one that grew. Only the old source can tell the two apart.
    """
    bad = []
    for key, lines in sorted(found.items()):
        if key[1] != "long-function" or key in baseline:
            continue
        renamed = same_function(key[0], key[2], found, baseline)
        if renamed and overloads_at_ref(root, ref, key[0], SIGNATURE.match(key[2]).group(1)) > 1:
            bad.append((key, lines, 0))
    return bad


def worse(found, baseline, skip_kinds=()):
    """Offenses above the baseline: new entries and counts that grew."""
    bad = []
    for key, count in sorted(found.items()):
        if key[1] in skip_kinds:
            continue
        allowed = baseline.get(key)
        if allowed is None and key[1] == "long-function":
            renamed = same_function(key[0], key[2], found, baseline)
            allowed = baseline.get(renamed) if renamed else None
        allowed = allowed or 0
        if count > allowed:
            bad.append((key, count, allowed))
    return bad


def kinds_ref_lacks(root, ref):
    """Kinds that REF's check_guardrails.py does not name; none when it has no script."""
    shown = subprocess.run(["git", "-C", root, "show", "%s:tools/guardrails/check_guardrails.py" % ref],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    if shown.returncode != 0:
        return ()
    return tuple(k for k in KINDS if '"%s"' % k not in shown.stdout)


def baseline_at_ref(root, ref):
    """(status, baseline): status is "ok", "no-file" or "bad-ref"."""
    def git(*args):
        return subprocess.run(["git", "-C", root] + list(args), stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, universal_newlines=True)

    if git("rev-parse", "--verify", "--quiet", ref + "^{commit}").returncode != 0:
        return "bad-ref", None
    shown = git("show", "%s:%s" % (ref, BASELINE_IN_REPO))
    if shown.returncode != 0:
        return "no-file", None
    return "ok", parse_baseline(shown.stdout)


def report(bad):
    for (path, kind, name), count, allowed in bad:
        print("%s: %s%s %d (baseline %d)" % (path, kind, " " + name if name else "", count, allowed))
    print("%d new offense(s). Fix them; the baseline must not grow." % len(bad))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--update-baseline", action="store_true",
                        help="rewrite the baseline downward; never adds or raises an entry")
    parser.add_argument("--introduce", metavar="KIND",
                        help="record today's counts for a kind the baseline has no entries of")
    parser.add_argument("--against-ref", metavar="REF",
                        help="also fail when the baseline is above REF's baseline")
    parser.add_argument("--root", default=DEFAULT_ROOT)
    parser.add_argument("--baseline", default=DEFAULT_BASELINE)
    args = parser.parse_args(argv)

    found = scan(args.root)
    baseline = load_baseline(args.baseline)

    if args.introduce:
        kind = args.introduce
        if kind not in INTRODUCIBLE:
            print("Cannot introduce %s: only %s." % (kind, ", ".join(INTRODUCIBLE)))
            return 1
        if any(k[1] == kind for k in (baseline or {})):
            print("The baseline already has %s entries; --update-baseline lowers them." % kind)
            return 1
        merged = dict(baseline or {})
        merged.update({k: n for k, n in found.items() if k[1] == kind})
        write_baseline(args.baseline, merged)
        print("Recorded %d %s entries." % (len(merged) - len(baseline or {}), kind))
        return 0

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

    status = 0
    bad = worse(found, baseline or {})
    if bad:
        report(bad)
        status = 1

    if args.against_ref:
        where, theirs = baseline_at_ref(args.root, args.against_ref)
        if where == "bad-ref":
            print("Cannot resolve %s." % args.against_ref)
            return 1
        if where == "no-file":
            print("%s has no baseline; skipping the comparison." % args.against_ref)
        else:
            raised = worse(baseline or {}, theirs, kinds_ref_lacks(args.root, args.against_ref))
            if raised:
                report(raised)
                print("The baseline is above %s's: it may only shrink." % args.against_ref)
                status = 1
        overloads = borrowed(found, baseline or {}, args.root, args.against_ref)
        if where == "ok":
            overloads += borrowed(baseline or {}, theirs, args.root, args.against_ref)
        if overloads:
            report(overloads)
            status = 1

    if status == 0:
        print("Guardrails ok: %d baselined offenses, none new." % len(found))
    return status


if __name__ == "__main__":
    sys.exit(main())
