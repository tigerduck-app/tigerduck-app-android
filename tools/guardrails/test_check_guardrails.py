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


if __name__ == "__main__":
    unittest.main()
