package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/account-state.md` lists every store the app keeps, with the account it belongs to and
 * what clears it at that account's sign-out (see [AccountStateInventory]). This holds the list to
 * the sources, and checks on altered copies of them that the check fails where it should.
 */
class AccountStateInventoryTest {

    private val app = AccountStateInventory.app

    private fun path(file: String) = app.sources.keys.single { it.endsWith("/$file") }

    /** The app's inventory over its sources with [old] in [file] replaced by [new]. */
    private fun edited(file: String, old: String, new: String): AccountStateInventory {
        val source = app.sources.getValue(path(file))
        assertTrue("$file holds `$old`", old in source)
        return AccountStateInventory(app.sources + (path(file) to source.replaceFirst(old, new)), app.text)
    }

    /** The app's sources over its inventory with [old] replaced by [new]. */
    private fun listed(old: String, new: String): AccountStateInventory {
        assertTrue("the inventory holds `$old`", old in app.text)
        return AccountStateInventory(app.sources, app.text.replaceFirst(old, new))
    }

    @Test
    fun `every store the app keeps is listed, with what clears it at its account's sign-out`() {
        assertEquals(emptyList<String>(), app.problems())
    }

    @Test
    fun `the holiday sets and edit times are seen as string sets, through the preference helpers`() {
        val seen = app.keys.getValue("tigerduck_prefs").filter { it.name.startsWith("holiday") }.associate { it.name to it.type }
        assertEquals(
            mapOf(
                "holidayNotifyOverrides" to "StringSet",
                "holidayOverridesAwaitingUpload" to "StringSet",
                "holidayOverrideEditTimes" to "StringSet",
            ),
            seen,
        )
    }

    @Test
    fun `the inventory reads every kind of store`() {
        val stores = app.rows.map { it.store }.toSet()
        assertTrue(stores.containsAll(setOf("prefs", "cache", "database", "tigerduck_prefs", "tigerduck_credentials")))
        assertTrue(app.rows.any { it.clearer?.via != null })
    }

    @Test
    fun `a new preference without a row fails`() {
        val problems = edited(
            "AppPreferences.kt",
            "    var hasCompletedOnboarding: Boolean",
            "    var lastViewedCourse: String?\n" +
                "        get() = prefs.getString(\"lastViewedCourse\", null)\n" +
                "        set(value) = prefs.edit().putString(\"lastViewedCourse\", value).apply()\n\n" +
                "    var hasCompletedOnboarding: Boolean",
        ).problems()
        assertTrue(problems.toString(), "tigerduck_prefs `lastViewedCourse` has no row in docs/account-state.md" in problems)
    }

    @Test
    fun `a new preferences file without a row fails`() {
        val problems = edited(
            "BulletinReadStateStore.kt",
            "context.getSharedPreferences(\"bulletin_read_state\", Context.MODE_PRIVATE)",
            "context.getSharedPreferences(\"exam_seats\", Context.MODE_PRIVATE)",
        ).problems()
        assertTrue(problems.toString(), "prefs `exam_seats` has no row in docs/account-state.md" in problems)
        assertTrue(problems.toString(), "prefs `bulletin_read_state` is listed but no longer declared" in problems)
    }

    @Test
    fun `a new cache file without a row fails`() {
        val problems = edited("DataCache.kt", "\"skipped_dates.json\"", "\"exam_seats.json\"").problems()
        assertTrue(problems.toString(), "cache `exam_seats.json` has no row in docs/account-state.md" in problems)
    }

    @Test
    fun `a school key the sign-out stops clearing fails`() {
        val problems = edited("AuthService.kt", "prefs.classTableSelectedSemester = null\n", "").problems()
        assertTrue(
            problems.toString(),
            "tigerduck_prefs `classTableSelectedSemester`: `logout` in AuthService.kt does not clear it" in problems,
        )
    }

    @Test
    fun `a cache file the sign-out stops clearing fails`() {
        val problems = edited("DataCache.kt", "\"ignored_courses.json\",\n", "").problems()
        assertTrue(
            problems.toString(),
            "cache `ignored_courses.json`: `clearAllUserData` in DataCache.kt does not clear it" in problems,
        )
    }

    @Test
    fun `a credential the sign-out stops clearing fails`() {
        val problems = edited(
            "CredentialManager.kt",
            ".remove(\"moodle_token\")\n            .remove(\"moodle_private_token\")",
            ".remove(\"moodle_token\")",
        ).problems()
        assertTrue(
            problems.toString(),
            "tigerduck_credentials `moodle_private_token`: `clearNtustCredentials` in CredentialManager.kt does not clear it" in problems,
        )
    }

    @Test
    fun `a file of an account its clearer stops clearing fails`() {
        val problems = edited("BulletinReadStateStore.kt", "prefs.edit().remove(KEY_READ_IDS).apply()", "").problems()
        assertTrue(
            problems.toString(),
            "prefs `bulletin_read_state`: `clear` in BulletinReadStateStore.kt does not clear it" in problems,
        )
    }

    @Test
    fun `a clearer that no longer makes the call its row names fails`() {
        val problems = edited("NotificationSettingsSync.kt", "assignmentStore.hasUnconfirmedEdit = false", "").problems()
        assertTrue(
            problems.toString(),
            "tigerduck_prefs `hasUnconfirmedAssignmentSyncEdit`: `cancelPendingPushes` in NotificationSettingsSync.kt does not clear it" in problems,
        )
    }

    @Test
    fun `an account's row that names nothing that clears it fails`() {
        val problems = listed(
            "| tigerduck_prefs | `classTableSelectedSemester` | school | `AuthService.kt` `logout` |",
            "| tigerduck_prefs | `classTableSelectedSemester` | school | |",
        ).problems()
        assertTrue(
            problems.toString(),
            "tigerduck_prefs `classTableSelectedSemester` belongs to an account but names nothing that clears it" in problems,
        )
    }

    @Test
    fun `a row naming a function that is gone fails`() {
        val problems = listed(
            "| tigerduck_prefs | `ssoLoginTimestamp` | school | `AppPreferences.kt` `clearSsoTimestamp` |",
            "| tigerduck_prefs | `ssoLoginTimestamp` | school | `AppPreferences.kt` `forgetSsoTimestamp` |",
        ).problems()
        assertTrue(problems.toString(), "tigerduck_prefs `ssoLoginTimestamp`: no `forgetSsoTimestamp` in AppPreferences.kt" in problems)
    }

    @Test
    fun `a row with a scope the inventory does not know fails`() {
        val problems = listed(
            "| prefs | `cjk_centering` | keys | |",
            "| prefs | `cjk_centering` | phone | |",
        ).problems()
        assertTrue(problems.toString(), "prefs `cjk_centering` has unknown scope phone" in problems)
    }

    // Listed whole, a file of the device would take a key of an account added to it unseen.
    @Test
    fun `a file outliving the accounts listed as a whole fails`() {
        val problems = listed(
            "| prefs | `cjk_centering` | keys | |",
            "| prefs | `cjk_centering` | device | |",
        ).problems()
        assertTrue(problems.toString(), "prefs `cjk_centering`: a file outliving the accounts lists its keys (scope keys)" in problems)
    }

    @Test
    fun `a key written inside an editor's block without a row fails`() {
        val problems = edited(
            "AppPreferences.kt",
            "    var hasCompletedOnboarding: Boolean",
            "    fun keepLastViewedCourse(value: String) {\n" +
                "        prefs.edit().apply { putString(\"lastViewedCourse\", value) }.apply()\n" +
                "    }\n\n" +
                "    var hasCompletedOnboarding: Boolean",
        ).problems()
        assertTrue(problems.toString(), "tigerduck_prefs `lastViewedCourse` has no row in docs/account-state.md" in problems)
    }

    @Test
    fun `a key written in a block inside an editor's block without a row fails`() {
        val problems = edited(
            "AppPreferences.kt",
            "    var hasCompletedOnboarding: Boolean",
            "    fun keepLastViewedCourse(value: String?) {\n" +
                "        prefs.edit().apply { if (value != null) { putString(\"lastViewedCourse\", value) } }.apply()\n" +
                "    }\n\n" +
                "    var hasCompletedOnboarding: Boolean",
        ).problems()
        assertTrue(problems.toString(), "tigerduck_prefs `lastViewedCourse` has no row in docs/account-state.md" in problems)
    }

    @Test
    fun `a key written in an edit block of its own without a row fails`() {
        val problems = edited(
            "AppPreferences.kt",
            "    var hasCompletedOnboarding: Boolean",
            "    fun keepLastViewedCourse(value: String) {\n" +
                "        prefs.edit(commit = true) { putString(\"lastViewedCourse\", value) }\n" +
                "    }\n\n" +
                "    var hasCompletedOnboarding: Boolean",
        ).problems()
        assertTrue(problems.toString(), "tigerduck_prefs `lastViewedCourse` has no row in docs/account-state.md" in problems)
    }

    @Test
    fun `a key handed through a parameter is read where the parameter is passed`() {
        val problems = edited(
            "AppPreferences.kt",
            "    var hasCompletedOnboarding: Boolean",
            "    private fun keep(key: String, value: String) {\n" +
                "        prefs.edit().putString(key, value).apply()\n" +
                "    }\n\n" +
                "    fun keepLastViewedCourse(value: String) = keep(\"lastViewedCourse\", value)\n\n" +
                "    var hasCompletedOnboarding: Boolean",
        ).problems()
        assertTrue(problems.toString(), "tigerduck_prefs `lastViewedCourse` has no row in docs/account-state.md" in problems)
    }

    @Test
    fun `a key handed through a parameter something unreadable is passed for fails`() {
        val problems = edited(
            "AppPreferences.kt",
            "    var hasCompletedOnboarding: Boolean",
            "    private fun keep(key: String, value: String) {\n" +
                "        prefs.edit().putString(key, value).apply()\n" +
                "    }\n\n" +
                "    fun keepLastViewedCourse(value: String) = keep(courseKey(), value)\n\n" +
                "    var hasCompletedOnboarding: Boolean",
        ).problems()
        assertTrue(problems.toString(), problems.any { "a key of tigerduck_prefs this cannot read: `key`" in it })
    }

    @Test
    fun `a key handed through a parameter is read at a call right after another`() {
        listOf(
            "        println(value)\n        keep(\"lastViewedCourse\", value)",
            "        if (value.isNotEmpty()) keep(\"lastViewedCourse\", value)",
        ).forEach { call ->
            val problems = edited(
                "AppPreferences.kt",
                "    var hasCompletedOnboarding: Boolean",
                "    private fun keep(key: String, value: String) {\n" +
                    "        prefs.edit().putString(key, value).apply()\n" +
                    "    }\n\n" +
                    "    fun keepLastViewedCourse(value: String) {\n$call\n    }\n\n" +
                    "    var hasCompletedOnboarding: Boolean",
            ).problems()
            assertTrue("$call: $problems", "tigerduck_prefs `lastViewedCourse` has no row in docs/account-state.md" in problems)
        }
    }

    @Test
    fun `a key written in another scope function's block inside an editor's without a row fails`() {
        val problems = edited(
            "AppPreferences.kt",
            "    var hasCompletedOnboarding: Boolean",
            "    fun keepLastViewedCourse(value: String) {\n" +
                "        prefs.edit(commit = true) { value.apply { putString(\"lastViewedCourse\", this) } }\n" +
                "    }\n\n" +
                "    var hasCompletedOnboarding: Boolean",
        ).problems()
        assertTrue(problems.toString(), "tigerduck_prefs `lastViewedCourse` has no row in docs/account-state.md" in problems)
    }

    // A template's expression is code, and a string inside it no comment.
    @Test
    fun `a key written in a string template, or after a slash pair quoted in one, without a row fails`() {
        listOf(
            "        val note = \"\${prefs.edit().putString(\"lastViewedCourse\", value).commit()}\"",
            "        val slashes = \"\${\"//\"}\"; prefs.edit().putString(\"lastViewedCourse\", value).apply()",
        ).forEach { line ->
            val problems = edited(
                "AppPreferences.kt",
                "    var hasCompletedOnboarding: Boolean",
                "    fun keepLastViewedCourse(value: String) {\n$line\n    }\n\n    var hasCompletedOnboarding: Boolean",
            ).problems()
            assertTrue("$line: $problems", "tigerduck_prefs `lastViewedCourse` has no row in docs/account-state.md" in problems)
        }
    }

    // Whatever the caller calls the instance it holds.
    @Test
    fun `a key passed to a helper of the store's class on an instance of any name without a row fails`() {
        val problems = edited(
            "PushIdentity.kt",
            "    fun uuid(): String =",
            "    fun keepLastViewedCourse(settings: AppPreferences, value: String) = settings.putString(\"lastViewedCourse\", value)\n\n" +
                "    fun uuid(): String =",
        ).problems()
        assertTrue(problems.toString(), "tigerduck_prefs `lastViewedCourse` has no row in docs/account-state.md" in problems)
    }

    // A map's clear() is no store's: the file is cleared by its editor's.
    @Test
    fun `a whole file whose clearer clears something else fails`() {
        val problems = edited(
            "MailStateStore.kt",
            "        clearCount++\n        prefs.edit().clear().apply()",
            "        clearCount++\n        prefs.all.toMutableMap().clear()",
        ).problems()
        assertTrue(problems.toString(), "prefs `school_mail_state`: `clear` in MailStateStore.kt does not clear it" in problems)
    }

    // A comment is no code: the removal it holds removes nothing.
    @Test
    fun `a credential whose removal is commented out fails`() {
        val problems = edited(
            "CredentialManager.kt",
            "                .remove(\"library_password\")\n",
            "                // .remove(\"library_password\")\n",
        ).problems()
        assertTrue(
            problems.toString(),
            "tigerduck_credentials `library_password`: `clearLibraryCredentials` in CredentialManager.kt does not clear it" in problems,
        )
    }

    // A brace in a string is no block's: the body read is still the function's.
    @Test
    fun `a brace in a string leaves the clearer's body whole`() {
        val problems = edited(
            "AppPreferences.kt",
            "    fun clearSsoTimestamp() {\n",
            "    fun clearSsoTimestamp() {\n        android.util.Log.d(\"AppPreferences\", \"} {\")\n",
        ).problems()
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `a store opened under a name the check cannot read fails`() {
        val problems = edited(
            "BulletinReadStateStore.kt",
            "context.getSharedPreferences(\"bulletin_read_state\", Context.MODE_PRIVATE)",
            "context.getSharedPreferences(nameForAccount(), Context.MODE_PRIVATE)",
        ).problems()
        assertTrue(problems.toString(), problems.any { "opens a store whose name this cannot read: `nameForAccount()`" in it })
    }

    @Test
    fun `a key the check cannot read fails`() {
        val problems = edited(
            "AppPreferences.kt",
            "prefs.getBoolean(\"hasCompletedOnboarding\", false)",
            "prefs.getBoolean(onboardingKey(), false)",
        ).problems()
        assertTrue(problems.toString(), problems.any { "a key of tigerduck_prefs this cannot read: `onboardingKey()`" in it })
    }

    // Still named, no longer deleted: the statement that names the files must delete them.
    @Test
    fun `a cache family whose statement no longer deletes fails`() {
        val problems = edited(
            "DataCache.kt",
            "name.startsWith(\"score_\") && name.endsWith(\".json\") }\n                            ?.forEach { it.delete() }",
            "name.startsWith(\"score_\") && name.endsWith(\".json\") }\n                            ?.forEach { it.exists() }",
        ).problems()
        assertTrue(problems.toString(), "cache `score_*.json`: `clearAllUserData` in DataCache.kt does not clear it" in problems)
    }

    @Test
    fun `a whole file cleared of one key only fails`() {
        val problems = edited(
            "MailStateStore.kt",
            "        clearCount++\n        prefs.edit().clear().apply()",
            "        clearCount++\n        prefs.edit().remove(K_NAME).apply()",
        ).problems()
        assertTrue(problems.toString(), "prefs `school_mail_state`: `clear` in MailStateStore.kt does not clear it" in problems)
    }

    @Test
    fun `a temporary file the sign-out stops deleting fails`() {
        val problems = edited("DataCache.kt", "                runCatching { deleteUnfinishedWrites(cacheDir) }\n", "")
            .let { AccountStateInventory(it.sources + (path("DataCache.kt") to it.sources.getValue(path("DataCache.kt"))
                .replace("                runCatching { deleteUnfinishedWrites(userDataDir) }\n", "")), app.text) }
            .problems()
        assertTrue(problems.toString(), "cache `*.tmp`: `clearAllUserData` in DataCache.kt does not clear it" in problems)
    }
}
