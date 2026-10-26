"""Tests for rewrite_string_imports.py.

    python3 -m unittest discover -s tools/dev
"""

import contextlib
import io
import tempfile
import textwrap
import unittest
from pathlib import Path

import rewrite_string_imports as script
from rewrite_string_imports import Ambiguous, rewrite


def kt(source: str) -> str:
    return textwrap.dedent(source).lstrip("\n")


class StringsOnlyTest(unittest.TestCase):
    def test_a_file_using_r_only_for_strings_imports_the_strings_module_instead(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui.screen.home

            import androidx.compose.runtime.Composable
            import org.ntust.app.tigerduck.R
            import org.ntust.app.tigerduck.auth.AuthService
            import org.ntust.app.tigerduck.data.Thing
            import java.util.Locale

            fun title() = stringResource(R.string.home_title)
            """)
        self.assertEqual(kt("""
            package org.ntust.app.tigerduck.ui.screen.home

            import androidx.compose.runtime.Composable
            import org.ntust.app.tigerduck.auth.AuthService
            import org.ntust.app.tigerduck.core.strings.R
            import org.ntust.app.tigerduck.data.Thing
            import java.util.Locale

            fun title() = stringResource(R.string.home_title)
            """), rewrite(before))

    def test_the_locale_list_moved_with_the_strings(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui.screen.settings

            import org.ntust.app.tigerduck.R

            val a = R.string.language
            val b = R.xml.locales_config
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R\n", after)
        self.assertNotIn("import org.ntust.app.tigerduck.R\n", after)
        self.assertIn("val b = R.xml.locales_config", after)

    def test_the_app_package_gets_an_import_its_r_never_needed(self):
        before = kt("""
            package org.ntust.app.tigerduck

            import android.os.Bundle
            import org.ntust.app.tigerduck.auth.AuthService
            import javax.inject.Inject

            val a = R.string.app_name
            """)
        self.assertEqual(kt("""
            package org.ntust.app.tigerduck

            import android.os.Bundle
            import org.ntust.app.tigerduck.auth.AuthService
            import org.ntust.app.tigerduck.core.strings.R
            import javax.inject.Inject

            val a = R.string.app_name
            """), rewrite(before))

    def test_a_file_with_no_imports_gets_one_under_its_package(self):
        before = "package org.ntust.app.tigerduck\n\nval a = R.string.app_name\n"
        self.assertEqual(
            "package org.ntust.app.tigerduck\n\nimport org.ntust.app.tigerduck.core.strings.R\n\nval a = R.string.app_name\n",
            rewrite(before),
        )

    def test_a_resource_named_in_a_comment_does_not_count_as_a_use_of_apps_r(self):
        before = kt("""
            package org.ntust.app.tigerduck.notification

            import org.ntust.app.tigerduck.R

            // Used to be R.drawable.ic_old; see R.string.title.
            val a = R.string.title
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R\n", after)
        self.assertNotIn("StringsR", after)


class MixedTest(unittest.TestCase):
    before = kt("""
        package org.ntust.app.tigerduck.widget

        import androidx.glance.ImageProvider
        import org.ntust.app.tigerduck.R
        import org.ntust.app.tigerduck.ui.theme.Colors
        import kotlin.math.max
        import androidx.compose.material3.R as M3R

        /** Shows [R.string.widget_label] beside [R.drawable.widget_icon]. */
        fun icon() = ImageProvider(R.drawable.widget_icon)
        fun label(context: Context) = context.getString(R.string.widget_label)
        val m3 = M3R.string.close_sheet
        """)

    def test_a_file_also_using_apps_own_resources_keeps_its_r_and_aliases_the_strings(self):
        self.assertEqual(kt("""
            package org.ntust.app.tigerduck.widget

            import androidx.glance.ImageProvider
            import org.ntust.app.tigerduck.R
            import org.ntust.app.tigerduck.ui.theme.Colors
            import kotlin.math.max
            import androidx.compose.material3.R as M3R
            import org.ntust.app.tigerduck.core.strings.R as StringsR

            /** Shows [StringsR.string.widget_label] beside [R.drawable.widget_icon]. */
            fun icon() = ImageProvider(R.drawable.widget_icon)
            fun label(context: Context) = context.getString(StringsR.string.widget_label)
            val m3 = M3R.string.close_sheet
            """), rewrite(self.before))

    def test_a_string_added_later_to_an_aliased_file_reuses_the_alias(self):
        once = rewrite(self.before)
        later = once + "val more = R.string.another\n"
        again = rewrite(later)
        self.assertTrue(again.endswith("val more = StringsR.string.another\n"))
        self.assertEqual(1, again.count("as StringsR"))

    def test_a_style_counts_as_apps_own(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui.screen.browser

            import org.ntust.app.tigerduck.R

            val theme = R.style.Theme_TigerDuck
            val title = R.string.browser_title
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.R\n", after)
        self.assertIn("val theme = R.style.Theme_TigerDuck", after)
        self.assertIn("val title = StringsR.string.browser_title", after)


class ReferencesTest(unittest.TestCase):
    def test_a_fully_qualified_string_gets_the_strings_package(self):
        before = kt("""
            package org.ntust.app.tigerduck.network.model

            val a = context.getString(org.ntust.app.tigerduck.R.string.tag_server_notification)
            val b = org.ntust.app.tigerduck.R.drawable.ic_notification
            """)
        after = rewrite(before)
        self.assertIn("org.ntust.app.tigerduck.core.strings.R.string.tag_server_notification", after)
        self.assertIn("val b = org.ntust.app.tigerduck.R.drawable.ic_notification", after)
        self.assertNotIn("import", after)

    def test_templates_are_code_and_the_rest_of_a_literal_is_not(self):
        before = kt("""
            package org.ntust.app.tigerduck.library

            import org.ntust.app.tigerduck.R

            val icon = R.drawable.icon
            val a = "string ${R.string.error_network_unavailable}"
            val b = "R.string.left_alone"
            val c = \"\"\"raw ${text(R.string.raw_template)} R.string.raw_left\"\"\"
            val d = '"'
            val e = R.string.after_a_quote_char
            """)
        after = rewrite(before)
        self.assertIn('"string ${StringsR.string.error_network_unavailable}"', after)
        self.assertIn('"R.string.left_alone"', after)
        self.assertIn("${text(StringsR.string.raw_template)} R.string.raw_left", after)
        self.assertIn("val e = StringsR.string.after_a_quote_char", after)

    def test_other_libraries_r_is_left_alone(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import com.google.android.material.R

            val a = R.string.material_title
            val b = androidx.appcompat.R.style.Theme_AppCompat
            """)
        self.assertEqual(before, rewrite(before))

    def test_a_file_without_strings_is_left_alone(self):
        before = kt("""
            package org.ntust.app.tigerduck.notification

            import org.ntust.app.tigerduck.R

            val a = R.drawable.ic_notification
            """)
        self.assertEqual(before, rewrite(before))

    def test_only_strings_named_in_comments_change_nothing(self):
        before = kt("""
            package org.ntust.app.tigerduck.notification

            import org.ntust.app.tigerduck.R

            // Not R.string.anything any more.
            val a = R.drawable.ic_notification
            """)
        self.assertEqual(before, rewrite(before))


class EdgeCaseTest(unittest.TestCase):
    def test_an_alias_already_bound_gets_a_number(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import android.R as StringsR
            import org.ntust.app.tigerduck.R

            val a = R.drawable.icon
            val b = R.string.title
            val c = StringsR.string.ok
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R as StringsR2\n", after)
        self.assertIn("val b = StringsR2.string.title", after)
        self.assertIn("val c = StringsR.string.ok", after)
        self.assertEqual(after, rewrite(after))

    def test_apps_r_imported_under_the_alias_gets_another(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.R as StringsR

            val b = StringsR.string.title
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R as StringsR2\n", after)
        self.assertIn("val b = StringsR2.string.title", after)

    def test_an_import_with_a_comment_beside_it_is_still_an_import(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import androidx.compose.runtime.Composable
            import org.ntust.app.tigerduck.R // resources

            val b = R.string.title
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R // resources\n", after)
        self.assertNotIn("import org.ntust.app.tigerduck.R", after)

    def test_platform_r_imported_with_a_comment_in_the_app_package_is_left_alone(self):
        before = kt("""
            package org.ntust.app.tigerduck

            import android.R // platform

            val a = R.string.ok
            """)
        self.assertEqual(before, rewrite(before))

    def test_backticked_names_and_line_breaks_are_references_too(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.R

            val a = `R`.string.app_name
            val b = R.string.`when`
            val c = R
                .string
                .wrapped
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R\n", after)
        self.assertNotIn("import org.ntust.app.tigerduck.R\n", after)

    def test_any_other_use_of_apps_r_keeps_it(self):
        for use in ("R\n    .drawable.ic_notification", "R.drawable::class.java", "R::class"):
            before = "package org.ntust.app.tigerduck.ui\n\nimport org.ntust.app.tigerduck.R\n\nval a = R.string.title\nval b = " + use + "\n"
            after = rewrite(before)
            self.assertIn("import org.ntust.app.tigerduck.R\n", after, use)
            self.assertIn("val a = StringsR.string.title", after, use)
            self.assertIn("val b = " + use, after, use)

    def test_a_test_named_after_a_string_is_not_a_reference(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.R

            fun `shows R.string.title once`() = R.drawable.icon
            """)
        self.assertEqual(before, rewrite(before))

    def test_a_multi_dollar_literal_keeps_its_text(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.R

            val icon = R.drawable.icon
            val a = $$"text ${R.string.not_a_template} $${R.string.a_template}"
            val b = $$\"\"\"raw ${R.string.not_raw_template} $${R.string.raw_template}\"\"\"
            """)
        after = rewrite(before)
        self.assertIn("${R.string.not_a_template} $${StringsR.string.a_template}", after)
        self.assertIn("${R.string.not_raw_template} $${StringsR.string.raw_template}", after)

    def test_a_package_named_in_a_comment_is_not_the_files(self):
        before = kt("""
            /*
            package org.ntust.app.tigerduck.old
            */
            package org.ntust.app.tigerduck

            val a = R.string.app_name
            """)
        after = rewrite(before)
        self.assertTrue(after.endswith(
            "package org.ntust.app.tigerduck\n\nimport org.ntust.app.tigerduck.core.strings.R\n\nval a = R.string.app_name\n"
        ), after)
        self.assertEqual(after, rewrite(after))

    def test_importing_all_of_the_app_package_brings_its_r(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.*

            val a = R.string.app_name
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.*\nimport org.ntust.app.tigerduck.core.strings.R\n", after)

    def test_an_import_of_the_string_class_or_one_string_moves(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.R.drawable
            import org.ntust.app.tigerduck.R.string
            import org.ntust.app.tigerduck.R.string.app_name

            val a = string.title
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.R.drawable\n", after)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R.string\n", after)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R.string.app_name\n", after)
        self.assertEqual(after, rewrite(after))


class LeftAloneTest(unittest.TestCase):
    """What the script cannot read for sure it leaves as it is, for a person to rewrite."""

    def test_a_local_named_like_the_alias_gets_the_next_alias(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.R

            val a = R.drawable.icon
            fun title(StringsR: Resources) = R.string.app_name
            """)
        after = rewrite(before)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R as StringsR2\n", after)
        self.assertIn("fun title(StringsR: Resources) = StringsR2.string.app_name", after)

    def test_a_parameter_named_r_leaves_the_file_alone(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.R

            fun title(R: Resources) = R.string.app_name
            """)
        with self.assertRaises(Ambiguous):
            rewrite(before)

    def test_platform_r_imported_in_backticks_is_still_platform_r(self):
        for directive in ("import android.R as `R`", "import android.`R`"):
            before = "package org.ntust.app.tigerduck\n\n" + directive + "\n\nval a = R.string.ok\n"
            self.assertEqual(before, rewrite(before), directive)

    def test_a_directive_running_into_a_block_comment_leaves_the_file_alone(self):
        for before in (
            "package org.ntust.app.tigerduck.ui\n\nimport org.ntust.app.tigerduck.R /* resources\n   still */\n\nval a = R.drawable.icon\nval b = R.string.x\n",
            "package org.ntust.app.tigerduck /* the app\n   package */\n\nval a = R.string.x\n",
        ):
            with self.assertRaises(Ambiguous):
                rewrite(before)

    def test_an_import_it_cannot_read_leaves_the_file_alone(self):
        before = "package org.ntust.app.tigerduck\n\nimport android.R as\n    Platform\n\nval a = R.string.x\n"
        with self.assertRaises(Ambiguous):
            rewrite(before)

    def test_a_value_named_import_is_no_import(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.R

            fun a(import: Button) {
                import.click()
                text(R.string.action_import)
            }
            """)
        self.assertIn("import org.ntust.app.tigerduck.core.strings.R\n", rewrite(before))

    def test_the_tail_of_a_wrapped_qualified_name_is_not_apps_r(self):
        before = kt("""
            package org.ntust.app.tigerduck.ui

            import org.ntust.app.tigerduck.R

            val a = R.drawable.icon
            val b = androidx.compose.material3.
                R.string.close_sheet
            """)
        self.assertEqual(before, rewrite(before))

    def test_main_lists_what_it_left_and_fails(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(io.StringIO()) as err:
            path = Path(tmp) / "Foo.kt"
            source = "package org.ntust.app.tigerduck.ui\n\nimport org.ntust.app.tigerduck.R\n\nfun t(R: Res) = R.string.x\n"
            path.write_text(source, encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, script.main([tmp]))
            self.assertEqual(source, path.read_text(encoding="utf-8"))
            self.assertIn("Foo.kt: left alone", err.getvalue())


class IdempotenceTest(unittest.TestCase):
    def test_running_again_changes_nothing(self):
        cases = [
            MixedTest.before,
            "package org.ntust.app.tigerduck\n\nval a = R.string.app_name\n",
            kt("""
                package org.ntust.app.tigerduck.ui

                import org.ntust.app.tigerduck.R

                val a = R.string.title
                val b = org.ntust.app.tigerduck.R.string.other
                """),
        ]
        for before in cases:
            once = rewrite(before)
            self.assertNotEqual(before, once)
            self.assertEqual(once, rewrite(once))


class MainTest(unittest.TestCase):
    def setUp(self):
        # main() lists what it changed; keep the test run's output readable.
        quiet = contextlib.ExitStack()
        quiet.enter_context(contextlib.redirect_stdout(io.StringIO()))
        quiet.enter_context(contextlib.redirect_stderr(io.StringIO()))
        self.addCleanup(quiet.close)

    def test_check_lists_and_writes_nothing_then_a_run_rewrites(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "Foo.kt"
            source = "package org.ntust.app.tigerduck\n\nval a = R.string.app_name\n"
            path.write_text(source, encoding="utf-8")

            self.assertEqual(1, script.main(["--check", tmp]))
            self.assertEqual(source, path.read_text(encoding="utf-8"))

            self.assertEqual(0, script.main([tmp]))
            self.assertIn("import org.ntust.app.tigerduck.core.strings.R", path.read_text(encoding="utf-8"))
            self.assertEqual(0, script.main(["--check", tmp]))

    def test_crlf_files_keep_their_line_breaks(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "Foo.kt"
            path.write_bytes(b"package org.ntust.app.tigerduck.ui\r\n\r\nimport org.ntust.app.tigerduck.R\r\n\r\nval a = R.string.x\r\n")
            script.main([tmp])
            self.assertEqual(
                b"package org.ntust.app.tigerduck.ui\r\n\r\nimport org.ntust.app.tigerduck.core.strings.R\r\n\r\nval a = R.string.x\r\n",
                path.read_bytes(),
            )


if __name__ == "__main__":
    unittest.main()
