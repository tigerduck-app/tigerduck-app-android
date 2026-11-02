"""Tests for check_guardrails.py, run against a throwaway source tree."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_guardrails as cg  # noqa: E402

APP = "app/src/main/java/org/ntust/app/tigerduck/"
TAIPEI_HOME = "shared/src/main/java/org/ntust/app/tigerduck/shared/time/TaipeiClock.kt"
TERM_HOME = "shared/src/main/java/org/ntust/app/tigerduck/shared/time/AcademicTerm.kt"
PREFS = APP + "data/preferences/AppPreferences.kt"


class GuardrailsTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.baseline = os.path.join(self.root, "baseline.json")

    def tearDown(self):
        shutil.rmtree(self.root)

    def write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)

    def write_baseline(self, entries):
        with open(self.baseline, "w", encoding="utf-8") as f:
            json.dump(entries, f)

    def read_baseline(self):
        with open(self.baseline, encoding="utf-8") as f:
            return json.load(f)

    def run_cli(self, *extra):
        return cg.main(["--root", self.root, "--baseline", self.baseline, *extra])

    @staticmethod
    def lines(n):
        return "".join("val a%d = %d\n" % (i, i) for i in range(n))

    def test_clean_tree_passes(self):
        self.write(APP + "A.kt", self.lines(400))
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 0)

    def test_new_401_line_file_fails(self):
        self.write(APP + "Big.kt", self.lines(401))
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 1)

    def test_baselined_file_that_grows_fails(self):
        # The count of a long file is its line count, so growth is caught.
        self.write_baseline([{"path": APP + "Big.kt", "kind": "long-file", "count": 450}])
        self.write(APP + "Big.kt", self.lines(451))
        self.assertEqual(self.run_cli(), 1)

    def test_baselined_file_that_shrinks_passes(self):
        self.write(APP + "Big.kt", self.lines(420))
        self.write_baseline([{"path": APP + "Big.kt", "kind": "long-file", "count": 450}])
        self.assertEqual(self.run_cli(), 0)

    def test_test_sources_are_ignored(self):
        self.write("app/src/test/java/X.kt", self.lines(900) + 'val z = "Asia/Taipei"\n')
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 0)

    def test_long_function_fails_and_short_passes(self):
        body = "".join("    println(%d)\n" % i for i in range(80))
        self.write(APP + "F.kt", "fun big() {\n" + body + "}\n")
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 1)
        body = "".join("    println(%d)\n" % i for i in range(70))
        self.write(APP + "F.kt", "fun ok() {\n" + body + "}\n")
        self.assertEqual(self.run_cli(), 0)

    def test_braces_in_strings_and_comments_do_not_count(self):
        src = 'fun f() {\n    val s = "}}}"\n    // }\n}\n' + self.lines(401)
        self.write(APP + "F.kt", src)
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 1)  # the file is long, the function is not
        offenses = cg.scan(self.root)
        self.assertNotIn("long-function", {k for (_, k, _) in offenses})

    def top_app_bars(self):
        return {k[0]: v for k, v in cg.scan(self.root).items() if k[1] == "top-app-bar"}

    def test_top_app_bar_on_a_page_fails(self):
        self.write(APP + "ui/screen/foo/FooScreen.kt", "val a = { TopAppBar(title = {}) }\n")
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 1)

    def test_top_app_bar_in_ui_component_passes(self):
        self.write(APP + "ui/component/PageScaffold.kt", "val a = { TopAppBar(title = {}) }\n")
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 0)

    def test_top_app_bar_in_a_flavor_counts_outside_ui_component_only(self):
        play = "app/src/play/java/org/ntust/app/tigerduck/"
        self.write(play + "ui/component/Bar.kt", "val a = { TopAppBar(title = {}) }\n")
        self.write(play + "ui/screen/Page.kt", "val a = { TopAppBar(title = {}) }\n")
        self.assertEqual(self.top_app_bars(), {play + "ui/screen/Page.kt": 1})

    def test_every_material_top_app_bar_counts(self):
        self.write(APP + "ui/screen/Page.kt", "".join(
            "val a%d = { %s(title = {}) }\n" % (i, name) for i, name in enumerate((
                "TopAppBar", "CenterAlignedTopAppBar", "MediumTopAppBar", "LargeTopAppBar",
                "MediumFlexibleTopAppBar", "LargeFlexibleTopAppBar", "TwoRowsTopAppBar"))))
        self.assertEqual(self.top_app_bars(), {APP + "ui/screen/Page.kt": 7})

    def test_a_space_before_the_parenthesis_is_still_a_top_app_bar(self):
        self.write(APP + "ui/screen/Page.kt", "val a = { TopAppBar (title = {}) }\n")
        self.assertEqual(self.top_app_bars(), {APP + "ui/screen/Page.kt": 1})

    def test_a_renamed_import_of_a_top_app_bar_still_counts(self):
        self.write(APP + "ui/screen/Page.kt", (
            "import androidx.compose.material3.TopAppBar as MaterialBar\n"
            "import androidx.compose.material3.MediumTopAppBar as `Bar2`\n"
            "val a = { MaterialBar(title = {}) }\n"
            "val b = { Bar2 (title = {}) }\n"))
        self.assertEqual(self.top_app_bars(), {APP + "ui/screen/Page.kt": 2})

    def test_a_backticked_top_app_bar_call_counts(self):
        self.write(APP + "ui/screen/Page.kt", "val a = { `TopAppBar`(title = {}) }\n")
        self.assertEqual(self.top_app_bars(), {APP + "ui/screen/Page.kt": 1})

    def test_a_renamed_import_in_ui_component_passes(self):
        self.write(APP + "ui/component/Bar.kt", (
            "import androidx.compose.material3.TopAppBar as MaterialBar\n"
            "val a = { MaterialBar(title = {}) }\n"))
        self.assertEqual(self.top_app_bars(), {})

    def test_an_alias_of_something_else_is_not_a_top_app_bar(self):
        self.write(APP + "ui/screen/Page.kt", (
            "import androidx.compose.material3.TopAppBarDefaults as Defaults\n"
            "import androidx.compose.material3.Text as MaterialText\n"
            "val a = { MaterialText(\"x\") }\n"
            "val b = Defaults.windowInsets\n"))
        self.assertEqual(self.top_app_bars(), {})

    def test_what_only_names_a_top_app_bar_is_not_one(self):
        self.write(APP + "ui/screen/Page.kt", (
            "import androidx.compose.material3.TopAppBar\n"
            "import androidx.compose.material3.TopAppBarDefaults\n"
            "// TopAppBar(title = {}) in a comment\n"
            "val a = TopAppBarDefaults.windowInsets\n"
            "val b = TopAppBarColors(c, c, c, c, c)\n"
            'val c = "TopAppBar(title = {})"\n'
            "val d = MyTopAppBarHelper(1)\n"))
        self.assertEqual(self.top_app_bars(), {})

    def test_a_baseline_entry_cannot_be_added_for_a_top_app_bar(self):
        self.write(APP + "ui/screen/Page.kt", "val a = { TopAppBar(title = {}) }\n")
        self.write_baseline([])
        self.assertEqual(self.run_cli("--update-baseline"), 1)
        self.assertEqual(self.read_baseline(), [])

    def test_taipei_literal_allowed_in_home_only(self):
        self.write(TAIPEI_HOME, 'val z = ZoneId.of("Asia/Taipei")\n')
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 0)
        self.write(APP + "Other.kt", 'val z = ZoneId.of("Asia/Taipei")\n')
        self.assertEqual(self.run_cli(), 1)

    def test_fdroid_literal(self):
        self.write(PREFS, 'val f = BuildConfig.FLAVOR.equals("fdroid")\n')
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 0)
        self.write(PREFS, 'val f = flavor == "fdroid"\n')
        self.assertEqual(self.run_cli(), 1)
        self.write(PREFS, 'val f = BuildConfig.FLAVOR.equals("fdroid")\n')
        self.write(APP + "Other.kt", 'val f = BuildConfig.FLAVOR == "fdroid"\n')
        self.assertEqual(self.run_cli(), 1)

    def test_term_sort_allowed_in_home_only(self):
        sort = "val s = list.sortedByDescending { it.semester }\n"
        self.write(TERM_HOME, sort)
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 0)
        self.write(APP + "Other.kt", sort)
        self.assertEqual(self.run_cli(), 1)

    def test_compare_by_term_is_caught(self):
        self.write(APP + "Other.kt", "val c = compareBy<Term>({ it.semester })\n")
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 1)

    def test_bodiless_funs_do_not_swallow_the_next_class(self):
        iface = "interface Api {\n    fun a(x: Int): String\n    fun b()\n    suspend fun c(): Int\n}\n"
        body = "".join("        println(%d)\n" % i for i in range(79))
        cls = "class Impl {\n    fun big() {\n" + body + "    }\n    fun small() { }\n}\n"
        self.write(APP + "Api.kt", iface + cls)
        found = [(k, v) for k, v in cg.scan(self.root).items() if k[1] == "long-function"]
        self.assertEqual(found, [((APP + "Api.kt", "long-function", "Impl.big()"), 81)])

    def test_expression_body_counts_to_end_of_expression(self):
        src = "fun f() =\n" + "".join("    listOf(%d) +\n" % i for i in range(85)) + "    emptyList()\nfun g() = 1\n"
        self.write(APP + "E.kt", src)
        found = {k[2]: v for k, v in cg.scan(self.root).items() if k[1] == "long-function"}
        self.assertEqual(found, {"f()": 87})

    def test_new_long_function_fails_even_when_another_shrinks(self):
        def fn(name, n):
            return "fun %s() {\n" % name + "".join("    println(1)\n" for _ in range(n - 2)) + "}\n"
        self.write(APP + "F.kt", fn("old", 85) + fn("fresh", 81))
        self.write_baseline([{"path": APP + "F.kt", "kind": "long-function", "name": "old()", "lines": 200}])
        self.assertEqual(self.run_cli(), 1)
        self.write(APP + "F.kt", fn("old", 85))
        self.assertEqual(self.run_cli(), 0)

    def test_baselined_function_that_grows_fails(self):
        def fn(n):
            return "fun old() {\n" + "".join("    println(1)\n" for _ in range(n - 2)) + "}\n"
        self.write(APP + "F.kt", fn(91))
        self.write_baseline([{"path": APP + "F.kt", "kind": "long-function", "name": "old()", "lines": 90}])
        self.assertEqual(self.run_cli(), 1)

    def test_renamed_long_function_counts_as_new(self):
        body = "".join("    println(1)\n" for _ in range(80))
        self.write(APP + "F.kt", "fun renamed() {\n" + body + "}\n")
        self.write_baseline([{"path": APP + "F.kt", "kind": "long-function", "name": "old()", "lines": 90}])
        self.assertEqual(self.run_cli(), 1)

    def test_term_keyword_needs_word_boundary_and_ignores_case(self):
        self.write_baseline([])
        self.write(APP + "A.kt", "val s = list.sortedBy { it.determined }\n")
        self.assertEqual(self.run_cli(), 0)
        self.write(APP + "A.kt", "val s = list.sortedBy { it.Semester }\n")
        self.assertEqual(self.run_cli(), 1)

    def test_play_debug_and_fdroid_source_sets_count(self):
        self.write_baseline([])
        for sset in ("play", "fdroid", "debug"):
            self.write("app/src/%s/java/X.kt" % sset, self.lines(401))
            self.assertEqual(self.run_cli(), 1, sset)
            os.remove(os.path.join(self.root, "app/src/%s/java/X.kt" % sset))
        self.assertEqual(self.run_cli(), 0)

    def long_fn(self, header, n, tail="{"):
        """A declaration `header` whose body makes it n lines long."""
        return header + " " + tail + "\n" + "".join("    println(1)\n" for _ in range(n - 2)) + "}\n"

    def long_functions(self):
        return {k[2]: v for k, v in cg.scan(self.root).items() if k[1] == "long-function"}

    # --- fix round 2

    def test_other_source_sets_count_and_test_sets_do_not(self):
        self.write_baseline([])
        self.write("app/src/release/java/X.kt", self.lines(401))
        self.assertEqual(self.run_cli(), 1)
        os.remove(os.path.join(self.root, "app/src/release/java/X.kt"))
        for sset in ("testPlay", "androidTestDebug", "testFixtures", "androidTest"):
            self.write("app/src/%s/java/X.kt" % sset, self.lines(401))
        self.assertEqual(self.run_cli(), 0)

    def test_closing_brace_in_a_string_template_does_not_end_the_function(self):
        body = '    val s = "${"}"}"\n' + "".join("    println(1)\n" for _ in range(85))
        self.write(APP + "T.kt", "fun big() {\n" + body + "}\n")
        self.assertEqual(self.long_functions(), {"big()": 88})

    def test_sort_inside_a_string_template_is_seen(self):
        self.write(APP + "T.kt", 'val s = "${terms.sortedBy { it.semester }}"\n')
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 1)

    def test_return_type_on_the_next_line(self):
        src = "fun big():\n    Unit {\n" + "".join("    println(1)\n" for _ in range(83)) + "}\n"
        self.write(APP + "R.kt", src)
        self.assertEqual(self.long_functions(), {"big()": 86})

    def test_if_else_expression_body_counts_the_else_branch(self):
        src = ("fun pick(x: Boolean) = if (x) 1\n    else {\n"
               + "".join("        println(1)\n" for _ in range(83)) + "    }\n")
        self.write(APP + "E.kt", src)
        self.assertEqual(self.long_functions(), {"pick(Boolean)": 86})

    def test_raw_string_body_counts_every_line(self):
        src = 'fun text() = """\n' + "".join("line\n" for _ in range(85)) + '"""\nfun after() = 1\n'
        self.write(APP + "S.kt", src)
        self.assertEqual(self.long_functions(), {"text()": 87})

    def term_sort_fails(self, code):
        self.write(APP + "Other.kt", code + "\n")
        self.write_baseline([])
        return self.run_cli() == 1

    def test_missed_term_sorts(self):
        for code in (
            "val a = terms.sortedWith(compareByDescending { it.semester })",
            "val a = terms.sortedBy<Term, String> { it.semester }",
            "val a = terms.sortedBy { it.items.map { x -> x.id }.first().semester }",
            "val a = terms.sortWith(compareBy<Term> { it.term }.thenBy { it.id })",
            "val a = c.thenByDescending { it.termCode }",
        ):
            self.assertTrue(self.term_sort_fails(code), code)

    def test_term_sort_counts_once_per_call(self):
        self.write(APP + "Other.kt", "val a = t.sortedWith(compareBy { it.semester })\n")
        counts = [v for k, v in cg.scan(self.root).items() if k[1] == "term-sort"]
        self.assertEqual(counts, [1])

    def test_terminal_id_is_not_a_term_sort(self):
        self.assertFalse(self.term_sort_fails("val a = list.sortedBy { it.terminalId }"))
        self.assertTrue(self.term_sort_fails("val a = list.sortedBy { it.semesterCode }"))

    def test_inserting_an_overload_keeps_the_identity(self):
        long = self.long_fn("fun search(n: Int)", 90)
        self.write(APP + "M.kt", "class MailRepository {\n" + long + "}\n")
        before = self.long_functions()
        self.assertEqual(before, {"MailRepository.search(Int)": 90})
        self.write_baseline([{"path": APP + "M.kt", "kind": "long-function",
                              "name": "MailRepository.search(Int)", "lines": 90}])
        self.write(APP + "M.kt", "class MailRepository {\n    fun search(q: String) = 1\n" + long + "}\n")
        self.assertEqual(self.long_functions(), {"MailRepository.search(Int)": 90})
        self.assertEqual(self.run_cli(), 0)

    def test_receiver_functions_get_a_name(self):
        self.write(APP + "X.kt", self.long_fn("fun String.shout(times: Int = 2, x: List<Int>)", 85)
                   + self.long_fn("fun ((Int) -> Unit).twice()", 85))
        self.assertEqual(self.long_functions(),
                         {"String.shout(Int, List<Int>)": 85, "((Int)->Unit).twice()": 85})

    # --- fix round 3

    def test_multiline_if_branch_is_counted(self):
        src = ("fun text(flag: Boolean) = if (flag)\n    buildString {\n"
               + "".join("        append(1)\n" for _ in range(82)) + "    }\n    else \"\"\n")
        self.write(APP + "B.kt", src)
        self.assertEqual(self.long_functions(), {"text(Boolean)": 86})

    def test_comparison_in_a_default_value_keeps_the_signature(self):
        body = "".join("    println(1)\n" for _ in range(83))
        self.write(APP + "D.kt", "fun f(enabled: Boolean = 1 < 2, limit: Int = 3) {\n" + body + "}\n")
        self.assertEqual(self.long_functions(), {"f(Boolean, Int)": 85})
        self.write(APP + "D.kt", "fun f(enabled: Boolean = true, limit: Int = 3) {\n" + body + "}\n")
        self.assertEqual(self.long_functions(), {"f(Boolean, Int)": 85})

    def test_trailing_lambda_after_empty_parentheses(self):
        self.assertTrue(self.term_sort_fails("val a = terms.sortedByDescending() { it.semester }"))

    def git(self, *args):
        subprocess.run(["git", "-C", self.root, "-c", "user.email=a@b", "-c", "user.name=t",
                        "-c", "commit.gpgsign=false"] + list(args),
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_against_ref_fails_a_raised_baseline(self):
        rel = "tools/guardrails/baseline.json"
        self.baseline = os.path.join(self.root, rel)
        self.write(APP + "A.kt", 'val a = "Asia/Taipei"\nval b = "Asia/Taipei"\n')
        entry = {"path": APP + "A.kt", "kind": "taipei-literal", "count": 1}
        os.makedirs(os.path.dirname(self.baseline))
        self.write_baseline([entry])
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        self.git("branch", "base")
        # The tree has 2 literals against a baseline of 1, so it fails by itself;
        # a PR that raises the baseline to 2 passes the plain run but not this one.
        entry["count"] = 2
        self.write_baseline([entry])
        self.assertEqual(self.run_cli(), 0)
        self.assertEqual(self.run_cli("--against-ref", "base"), 1)
        entry["count"] = 1
        self.write(APP + "A.kt", 'val a = "Asia/Taipei"\n')
        self.write_baseline([entry])
        self.assertEqual(self.run_cli("--against-ref", "base"), 0)
        # An entry the target lacks fails; a ref without a baseline file skips.
        self.write(APP + "B.kt", 'val a = "Asia/Taipei"\n')
        self.write_baseline([entry, {"path": APP + "B.kt", "kind": "taipei-literal", "count": 1}])
        self.assertEqual(self.run_cli("--against-ref", "base"), 1)
        self.git("rm", "-q", "--cached", rel)
        self.git("commit", "-q", "-m", "no baseline")
        self.assertEqual(self.run_cli("--against-ref", "HEAD"), 0)
        self.assertEqual(self.run_cli("--against-ref", "nope"), 1)

    def test_new_path_for_baselined_kind_fails(self):
        self.write(APP + "A.kt", 'val z = "Asia/Taipei"\n')
        self.write(APP + "B.kt", 'val z = "Asia/Taipei"\n')
        self.write_baseline([{"path": APP + "A.kt", "kind": "taipei-literal", "count": 1}])
        self.assertEqual(self.run_cli(), 1)

    def test_count_above_baseline_fails(self):
        self.write(APP + "A.kt", 'val a = "Asia/Taipei"\nval b = "Asia/Taipei"\n')
        self.write_baseline([{"path": APP + "A.kt", "kind": "taipei-literal", "count": 1}])
        self.assertEqual(self.run_cli(), 1)

    def test_update_baseline_lowers_and_drops(self):
        self.write(APP + "A.kt", 'val a = "Asia/Taipei"\n')
        self.write_baseline([
            {"path": APP + "A.kt", "kind": "taipei-literal", "count": 3},
            {"path": APP + "Gone.kt", "kind": "taipei-literal", "count": 1},
        ])
        self.assertEqual(self.run_cli("--update-baseline"), 0)
        self.assertEqual(self.read_baseline(),
                         [{"path": APP + "A.kt", "kind": "taipei-literal", "count": 1}])

    def test_update_baseline_never_raises_a_count(self):
        self.write(APP + "A.kt", 'val a = "Asia/Taipei"\nval b = "Asia/Taipei"\n')
        entries = [{"path": APP + "A.kt", "kind": "taipei-literal", "count": 1}]
        self.write_baseline(entries)
        self.assertEqual(self.run_cli("--update-baseline"), 1)
        self.assertEqual(self.read_baseline(), entries)

    def test_update_baseline_refuses_new_entries(self):
        self.write(APP + "A.kt", 'val a = "Asia/Taipei"\n')
        self.write_baseline([])
        self.assertEqual(self.run_cli("--update-baseline"), 1)
        self.assertEqual(self.read_baseline(), [])

    def test_update_baseline_creates_missing_baseline(self):
        self.write(APP + "A.kt", 'val a = "Asia/Taipei"\n')
        self.assertEqual(self.run_cli("--update-baseline"), 0)
        self.assertEqual(self.read_baseline(),
                         [{"path": APP + "A.kt", "kind": "taipei-literal", "count": 1}])

    # --- ratchets that start non-empty

    def counts(self, kind):
        return {k[0]: n for k, n in cg.scan(self.root).items() if k[1] == kind}

    def test_introduce_records_a_new_kind_and_refuses_one_with_entries(self):
        self.write(APP + "A.kt", "val x = signInGeneration\nval y = 1\nval z = accountEpoch\n")
        self.write_baseline([])
        self.assertEqual(self.run_cli("--introduce", "account-counter"), 0)
        self.assertEqual(self.read_baseline(),
                         [{"path": APP + "A.kt", "kind": "account-counter", "count": 2}])
        self.assertEqual(self.run_cli(), 0)
        self.assertEqual(self.run_cli("--introduce", "account-counter"), 1)
        self.assertEqual(self.run_cli("--introduce", "taipei-literal"), 1)

    def test_introduce_keeps_the_other_kinds_entries(self):
        self.write(APP + "A.kt", 'val a = "Asia/Taipei"\nval b = isDemo\n')
        self.write_baseline([{"path": APP + "A.kt", "kind": "taipei-literal", "count": 1}])
        self.assertEqual(self.run_cli("--introduce", "demo-check"), 0)
        self.assertEqual(len(self.read_baseline()), 2)

    def test_a_kind_cannot_be_raised_once_it_has_entries(self):
        self.write(APP + "A.kt", "val x = signInGeneration\n")
        self.write_baseline([{"path": APP + "A.kt", "kind": "account-counter", "count": 1}])
        self.write(APP + "A.kt", "val x = signInGeneration\nval y = signInGeneration\n")
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.run_cli("--update-baseline"), 1)

    def commit_checker(self, text):
        """Commits a stand-in check_guardrails.py and the baseline as branch base."""
        rel = "tools/guardrails/baseline.json"
        self.baseline = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(self.baseline), exist_ok=True)
        self.write("tools/guardrails/check_guardrails.py", text)

    def test_against_ref_skips_a_kind_the_ref_checker_does_not_name(self):
        self.write(APP + "A.kt", "val x = signInGeneration\n")
        self.commit_checker('KINDS = ("taipei-literal",)\n')
        self.write_baseline([])
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        self.git("branch", "base")
        self.write_baseline([{"path": APP + "A.kt", "kind": "account-counter", "count": 1}])
        self.assertEqual(self.run_cli("--against-ref", "base"), 0)
        # Once the ref's checker names the kind, the same entry is a raise.
        self.write("tools/guardrails/check_guardrails.py", 'KINDS = ("account-counter",)\n')
        self.write_baseline([])
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "names it")
        self.write_baseline([{"path": APP + "A.kt", "kind": "account-counter", "count": 1}])
        self.assertEqual(self.run_cli("--against-ref", "HEAD"), 1)

    def long_pull(self, header, lines=85):
        body = "".join("    println(1)\n" for _ in range(lines))
        return header + " {\n" + body + "}\n"

    def test_a_changed_parameter_list_is_the_same_function(self):
        key = {"path": APP + "S.kt", "kind": "long-function", "name": "pull(String)", "lines": 87}
        self.write_baseline([key])
        self.write(APP + "S.kt", self.long_pull("fun pull(a: String, b: Boolean)"))
        self.assertEqual(self.run_cli(), 0)
        self.assertEqual(self.run_cli("--update-baseline"), 0)
        self.assertEqual(self.read_baseline()[0]["name"], "pull(String, Boolean)")

    def test_a_changed_parameter_list_may_not_grow_the_function(self):
        self.write_baseline([{"path": APP + "S.kt", "kind": "long-function",
                              "name": "pull(String)", "lines": 80}])
        self.write(APP + "S.kt", self.long_pull("fun pull(a: String, b: Boolean)"))
        self.assertEqual(self.run_cli(), 1)

    def test_a_new_long_overload_beside_a_shortened_one_inherits_nothing(self):
        # pull(String) was baselined at 87 lines and is now short; pull(Int) is new and long.
        self.write_baseline([{"path": APP + "S.kt", "kind": "long-function",
                              "name": "pull(String)", "lines": 87}])
        self.write(APP + "S.kt", self.long_pull("fun pull(a: String)", 10) + self.long_pull("fun pull(a: Int)"))
        self.assertEqual(self.run_cli(), 1)
        # Alone, the same long function keeps the allowance when only its parameters changed.
        self.write(APP + "S.kt", self.long_pull("fun pull(a: Int)"))
        self.assertEqual(self.run_cli(), 0)

    def test_a_deleted_overload_lends_nothing_to_the_one_that_grew(self):
        rel = "tools/guardrails/baseline.json"
        self.baseline = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(self.baseline))
        key = {"path": APP + "S.kt", "kind": "long-function", "name": "pull(String)", "lines": 100}
        self.write_baseline([key])
        self.write(APP + "S.kt", self.long_pull("fun pull(a: String)", 98) + self.long_pull("fun pull(a: Int)", 10))
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        self.git("branch", "base")
        # pull(String) goes and pull(Int) grows, under what looks like one function changing.
        self.write(APP + "S.kt", self.long_pull("fun pull(a: Int)", 88))
        self.assertEqual(self.run_cli("--against-ref", "base"), 1)
        # Regenerating the baseline hides nothing.
        self.write_baseline([{"path": APP + "S.kt", "kind": "long-function", "name": "pull(Int)", "lines": 90}])
        self.assertEqual(self.run_cli("--against-ref", "base"), 1)

    def test_a_lone_function_keeps_its_allowance_against_the_target(self):
        rel = "tools/guardrails/baseline.json"
        self.baseline = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(self.baseline))
        self.write_baseline([{"path": APP + "S.kt", "kind": "long-function", "name": "pull(String)", "lines": 100}])
        self.write(APP + "S.kt", self.long_pull("fun pull(a: String)", 98))
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        self.git("branch", "base")
        self.write(APP + "S.kt", self.long_pull("fun pull(a: Int)", 90))
        self.assertEqual(self.run_cli("--against-ref", "base"), 0)

    def test_overloads_still_match_on_the_full_name(self):
        self.write_baseline([{"path": APP + "S.kt", "kind": "long-function",
                              "name": "pull(String)", "lines": 87}])
        self.write(APP + "S.kt", self.long_pull("fun pull(a: String)") + self.long_pull("fun pull(a: Int)"))
        self.assertEqual(self.run_cli(), 1)

    def test_a_changed_parameter_list_in_another_file_is_new(self):
        self.write_baseline([{"path": APP + "S.kt", "kind": "long-function",
                              "name": "pull(String)", "lines": 87}])
        self.write(APP + "T.kt", self.long_pull("fun pull(a: String, b: Int)"))
        self.assertEqual(self.run_cli(), 1)

    def test_account_counter_hits_a_use_and_misses_a_comment(self):
        self.write(APP + "A.kt",
                   "// signInGeneration\nval a = \"accountEpoch\"\nfun f(generation: Int) {}\n"
                   "val c = DataCache.Generation\nval d = tokens.signInGeneration\n"
                   "val e = 1 // userDataGeneration\n")
        self.assertEqual(self.counts("account-counter"), {APP + "A.kt": 3})

    def test_account_counter_needs_a_word_boundary(self):
        self.write(APP + "A.kt", "val a = mySignInGenerationLabel\nval b = generation: Long\n")
        self.assertEqual(self.counts("account-counter"), {})

    def test_sign_out_reactor_hits_each_watch_and_misses_a_comment(self):
        self.write(APP + "A.kt",
                   "// authState.collect\nval a = \"signOuts.collect\"\n"
                   "authService.authState.collect { }\nauthState.first { !it }\n"
                   "account.accountState.collect { }\nauth.signIns.collect { }\n"
                   "account.studentChanges.collect { }\nmail.signOuts.collect { }\n")
        self.assertEqual(self.counts("sign-out-reactor"), {APP + "A.kt": 6})

    def test_sign_out_reactor_takes_no_baseline(self):
        self.write(APP + "A.kt", "authState.collect { }\n")
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.run_cli("--introduce", "sign-out-reactor"), 1)

    def test_sign_out_reactor_leaves_the_stream_and_a_read_of_the_state_alone(self):
        self.write(APP + "A.kt",
                   "sessions.events.collect { }\nsession.changes.collect { }\n"
                   "val a = authState.value\nval b = myauthState.collect\n")
        self.assertEqual(self.counts("sign-out-reactor"), {})

    def test_demo_check_hits_a_use_and_honors_its_homes(self):
        line = "val a = if (isDemo) 1 else demoAccount.isActive\n"
        self.write(APP + "ui/Screen.kt", line)
        self.write(APP + "demo/Thing.kt", line)
        self.write(APP + "auth/session/AccountSession.kt", line)
        self.write(APP + "debug/DemoModeInterceptor.kt", line)
        self.write(APP + "mail/MailDemo.kt", line)
        self.write("app/src/play/java/org/ntust/app/tigerduck/demo/Play.kt", line)
        # The sign-in type carries isDemo for the whole session.
        self.write("shared/src/main/java/org/ntust/app/tigerduck/shared/account/SignIn.kt", line)
        self.write(APP + "ui/Quiet.kt", "// isDemo\nval s = \"fixtures.demoMode\"\n")
        self.assertEqual(self.counts("demo-check"), {APP + "ui/Screen.kt": 1})

    def test_demo_check_sees_the_fixture_store_forms(self):
        self.write(APP + "ui/A.kt", "val a = store.demoMode\nval b = fixtures.demoMode\nval c = demo.isActive\n")
        self.assertEqual(self.counts("demo-check"), {APP + "ui/A.kt": 3})

    def test_sign_in_read_honors_its_homes_and_takes_no_baseline(self):
        line = "@OptIn(StartsAccountWork::class)\nfun f() {}\n"
        for home in ("auth/AuthService.kt", "ui/screen/Home.kt", "ui/AppState.kt", "TigerDuckApp.kt",
                     "demo/D.kt", "liveactivity/L.kt", "notification/ClassWorker.kt",
                     "notification/BootReceiver.kt", "push/PushRegistrationService.kt",
                     "mail/MailAccount.kt", "mail/MailRepository.kt", "mail/sync/MailChecker.kt", "data/moodle/M.kt",
                     "data/DataMigration.kt", "network/MoodleService.kt", "network/moodle/N.kt",
                     "network/AccountSessionGuard.kt", "network/NtustSessionManager.kt"):
            self.write(APP + home, line)
        self.assertEqual(self.counts("sign-in-read"), {})
        self.write(APP + "data/cache/DataCache.kt", "import x.StartsAccountWork\n" + line)
        self.write(APP + "notification/Helper.kt", line)
        self.write(APP + "mail/store/MailCache.kt", line)
        self.assertEqual(self.counts("sign-in-read"),
                         {APP + "data/cache/DataCache.kt": 1, APP + "notification/Helper.kt": 1,
                          APP + "mail/store/MailCache.kt": 1})
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.run_cli("--introduce", "sign-in-read"), 1)

    def test_bearer_home_holds_the_literal_to_its_homes_and_takes_no_baseline(self):
        self.write(APP + "auth/AuthTokenManager.kt", 'fun h() = "Bearer $access"\n')
        self.write(APP + "auth/SessionHostGuard.kt", 'const val BEARER = "Bearer "\n')
        self.write(APP + "push/PushApiClient.kt", 'val u = removeDevice("Bearer $accessToken", id)\n')
        self.write(APP + "push/SyncApiClient.kt", '// Takes the "Bearer " header.\nval n = 1\n')
        self.assertEqual(self.counts("bearer-home"), {})
        # The unregister is one call of that file, not the file: another request counts alone,
        # or on the same line.
        self.write(APP + "push/PushApiClient.kt", 'val v = patch("Bearer $t")\n')
        self.assertEqual(self.counts("bearer-home"), {APP + "push/PushApiClient.kt": 1})
        self.write(APP + "push/PushApiClient.kt", 'val v = patch("Bearer $t", "Bearer $u")\n')
        self.assertEqual(self.counts("bearer-home"), {APP + "push/PushApiClient.kt": 2})
        self.write(APP + "push/PushApiClient.kt", 'val u = removeDevice("Bearer $accessToken", id)\nval v = patch("Bearer $t")\n')
        self.write(APP + "network/BulletinApiClient.kt", 'val h = "Bearer " + token\n')
        self.assertEqual(self.counts("bearer-home"),
                         {APP + "push/PushApiClient.kt": 1, APP + "network/BulletinApiClient.kt": 1})
        self.write_baseline([])
        self.assertEqual(self.run_cli(), 1)
        self.assertEqual(self.run_cli("--introduce", "bearer-home"), 1)


if __name__ == "__main__":
    unittest.main()
