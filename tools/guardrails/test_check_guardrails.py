"""Tests for check_guardrails.py, run against a throwaway source tree."""
import json
import os
import shutil
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
        self.write(APP + "Big.kt", self.lines(450))
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
        offences = cg.scan(self.root)
        self.assertNotIn("long-function", {k for (_, k) in offences})

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
