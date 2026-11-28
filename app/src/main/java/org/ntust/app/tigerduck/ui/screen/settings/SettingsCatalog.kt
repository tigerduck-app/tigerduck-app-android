// Every row of Settings, once: its title, where it sits, what it needs and when it shows. The
// root, the pages, the sub-pages, search and the tests all read this list, as iOS's SettingsRow
// catalog (tigerduck-app#939) is read there. A notification's row is here twice: on its page, and
// on Notifications' sub-page, which links to each.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.PageSettings

/**
 * What the rows are shown and gated by, read live: who is signed in, whether TigerSync is on, and
 * what this phone and build have. Each is false by default, which shows the fewest rows.
 */
internal data class SettingsState(
    val schoolSignedIn: Boolean = false,
    val mailSignedIn: Boolean = false,
    val librarySignedIn: Boolean = false,
    val tigerSyncOn: Boolean = false,
    /** Course names come in English, so the abbreviation switches have something to change. */
    val coursesInEnglish: Boolean = false,
    val usesEnglishClassroomNames: Boolean = false,
    /** The phone can sense the flip that opens the library's QR code. */
    val hasFlipToLibrary: Boolean = false,
    /** This build ships the watch app, and a Wear OS watch has been connected to this phone ([org.ntust.app.tigerduck.wear.WatchSeen]). */
    val hasWatch: Boolean = false,
    /** A Live Update on this phone can draw the accent's gradient. */
    val hasLiveUpdateGradient: Boolean = false,
    /** The app checks for its own updates, which an F-Droid build leaves to the F-Droid client. */
    val hasUpdateCheck: Boolean = false,
    val hasWhatsNew: Boolean = false,
    val hasMoodleApp: Boolean = false,
    /** Someone's class table is imported, so the class tables have a list to manage. */
    val hasImportedClassTables: Boolean = false,
)

/** The root's sections, in order, around the list of pages. */
internal enum class SettingsSection(@StringRes val title: Int) {
    ACCOUNT(R.string.settings_section_account),
    APPEARANCE(R.string.settings_section_appearance),
    NOTIFICATIONS(R.string.settings_section_notifications),
    TIGERSYNC(R.string.cloud_sync_title),
    LANGUAGE(R.string.feature_category_language),
    OTHER(R.string.settings_section_other_settings),
    ABOUT(R.string.settings_section_about),
}

/**
 * A page that a row of a root section opens ([opener]), with rows of its own, as About › Others
 * is: titled as that row, and opened through [link].
 */
internal enum class SettingsSubPage(val opener: SettingsRow, val link: SettingsLink) {
    GRADIENT_USAGE(SettingsRow.GRADIENT_USAGE, SettingsLink.GRADIENT_USAGE),
    NOTIFICATION_SWITCHES(SettingsRow.NOTIFICATION_SWITCHES, SettingsLink.NOTIFICATION_SWITCHES),
    OTHER_SETTINGS(SettingsRow.OTHER_SETTINGS, SettingsLink.OTHER_SETTINGS),
    ABOUT_OTHERS(SettingsRow.ABOUT_OTHERS, SettingsLink.ABOUT_OTHERS);

    @get:StringRes
    val title: Int get() = opener.title

    val section: SettingsSection get() = (opener.place as SettingsPlace.Section).section
}

/** Where a row sits: in one of the root's sections, on a page's own settings, or on a section's sub-page. */
internal sealed interface SettingsPlace {
    @get:StringRes val title: Int

    data class Section(val section: SettingsSection) : SettingsPlace {
        override val title: Int get() = section.title
    }

    data class Page(val page: PageSettings) : SettingsPlace {
        override val title: Int get() = page.title
    }

    data class SubPage(val subPage: SettingsSubPage) : SettingsPlace {
        override val title: Int get() = subPage.title
    }
}

/**
 * What a closed row needs, said in its toast. No Android row waits on TigerSync: the phone posts
 * its own class and assignment reminders and runs Live Updates, where the iPhone has the backend
 * push them, so they work with it off.
 */
internal enum class SettingsGate(@StringRes val reason: Int) {
    TIGERSYNC(R.string.settings_gate_tigersync),
    SCHOOL_SIGN_IN(R.string.settings_gate_school_sign_in),
    MAIL_SIGN_IN(R.string.settings_gate_mail_sign_in),
    LIBRARY_SIGN_IN(R.string.settings_gate_library_sign_in);

    fun isOpen(state: SettingsState): Boolean = when (this) {
        TIGERSYNC -> state.tigerSyncOn
        SCHOOL_SIGN_IN -> state.schoolSignedIn
        MAIL_SIGN_IN -> state.mailSignedIn
        LIBRARY_SIGN_IN -> state.librarySignedIn
    }
}

/** Every setting, in the order its section, page or sub-page lists it. */
internal enum class SettingsRow(@StringRes val title: Int) {
    SCHOOL_ACCOUNT(R.string.settings_account_ntust_system),
    LIBRARY_ACCOUNT(R.string.settings_account_library_system),
    MAIL_ACCOUNT(R.string.school_mail_account_title),
    ACCENT_COLOR(R.string.settings_accent_color),
    GRADIENT_USAGE(R.string.settings_accent_gradient_usage),
    ACCENT_GRADIENT_ON_WIDGETS(R.string.settings_accent_gradient_on_widgets),
    ACCENT_GRADIENT_ON_WATCH(R.string.settings_accent_gradient_on_watch),
    ACCENT_GRADIENT_ON_LIVE_ACTIVITIES(R.string.settings_accent_gradient_on_live_activities),
    VISUAL_PRESET(R.string.settings_visual_preset_label),
    COLOR_THEME(R.string.settings_color_theme),
    DISPLAY_SIZE(R.string.settings_display_size_title),
    TAB_BAR(R.string.tab_editor_title),
    NOTIFICATION_PERMISSIONS(R.string.notification_permission_settings_nav_title),
    NOTIFICATION_SWITCHES(R.string.settings_notification_switches),
    NOTIFICATION_LIVE_ACTIVITY(R.string.live_activity_channel_name),
    NOTIFICATION_ASSIGNMENT_REMINDERS(R.string.settings_assignment_due_reminder),
    NOTIFICATION_CLASS_REMINDERS(R.string.settings_class_reminder),
    NOTIFICATION_BULLETINS(R.string.bulletin_notifications_title),
    NOTIFICATION_MAIL(R.string.school_mail_settings_notifications),
    TIGERSYNC(R.string.cloud_sync_title),
    LANGUAGE(R.string.settings_language),
    APP_ICON(R.string.settings_app_icon),
    STORAGE(R.string.settings_storage_title),
    OTHER_SETTINGS(R.string.settings_section_other_settings),
    API_ENDPOINT(R.string.settings_api_endpoint),
    VIBRATION(R.string.vibration_settings_title),
    SCREEN_ROTATION(R.string.settings_screen_rotation),
    ANALYTICS(R.string.settings_analytics_enabled),
    VERSION(R.string.settings_version),
    CHECK_FOR_UPDATES(R.string.settings_check_for_updates),
    WHATS_NEW(R.string.settings_whats_new),
    WEBSITE(R.string.settings_official_website),
    ABOUT_OTHERS(R.string.settings_about_others),
    SERVER_STATUS(R.string.settings_check_server_status),
    SHOW_FULL_DUE_DATE(R.string.settings_home_full_due_date),
    INVERT_SLIDER(R.string.settings_invert_slider_direction),
    ASSIGNMENT_REMINDERS(R.string.settings_assignment_due_reminder),
    LIVE_ACTIVITY(R.string.live_activity_channel_name),
    CLASS_TABLE_LIST(R.string.class_table_list_title),
    PIN_TODAY_COURSES(R.string.settings_class_table_pin_today),
    SHOW_CLASSROOM(R.string.settings_class_table_show_classroom),
    OUTLINE_REQUIRED_COURSES(R.string.settings_class_table_outline_required),
    SHOW_ALL_PERIODS(R.string.settings_class_table_all_periods),
    COURSE_NAME_SIZE(R.string.settings_font_size_title),
    CLASS_REMINDERS(R.string.settings_class_reminder),
    ENGLISH_COURSE_NAMES(R.string.settings_use_english_course_abbreviation),
    ENGLISH_CLASSROOM_NAMES(R.string.settings_use_english_classroom_abbreviation),
    MANDARIN_CLASSROOM_NAMES(R.string.settings_classroom_mandarin_display),
    REASSIGN_COURSE_COLORS(R.string.settings_reset_course_colors),
    CLASS_TABLE_STORAGE(R.string.settings_storage_title),
    BULLETIN_PUSH(R.string.bulletin_push_settings_header),
    MAIL_NOTIFICATIONS(R.string.school_mail_settings_notifications),
    MAIL_DISPLAY_NAME(R.string.school_mail_settings_display_name),
    MAIL_STORAGE(R.string.settings_storage_title),
    FLIP_TO_LIBRARY(R.string.settings_library_flip_to_show_qr),
    LINK_OPENING_METHOD(R.string.settings_link_opening_method),
    WEBSITE_DATA(R.string.settings_storage_title),
    MOODLE_OPEN_IN(R.string.settings_moodle_open_in),
    PIN_MYSELF(R.string.course_members_pin_myself),
    MOODLE_STORAGE(R.string.settings_storage_title);

    val place: SettingsPlace
        get() = when (this) {
            SCHOOL_ACCOUNT, LIBRARY_ACCOUNT, MAIL_ACCOUNT -> SettingsPlace.Section(SettingsSection.ACCOUNT)
            ACCENT_COLOR, GRADIENT_USAGE, VISUAL_PRESET, COLOR_THEME, DISPLAY_SIZE, TAB_BAR ->
                SettingsPlace.Section(SettingsSection.APPEARANCE)
            ACCENT_GRADIENT_ON_WIDGETS, ACCENT_GRADIENT_ON_WATCH, ACCENT_GRADIENT_ON_LIVE_ACTIVITIES ->
                SettingsPlace.SubPage(SettingsSubPage.GRADIENT_USAGE)
            NOTIFICATION_PERMISSIONS, NOTIFICATION_SWITCHES -> SettingsPlace.Section(SettingsSection.NOTIFICATIONS)
            NOTIFICATION_LIVE_ACTIVITY, NOTIFICATION_ASSIGNMENT_REMINDERS, NOTIFICATION_CLASS_REMINDERS, NOTIFICATION_BULLETINS,
            NOTIFICATION_MAIL -> SettingsPlace.SubPage(SettingsSubPage.NOTIFICATION_SWITCHES)
            TIGERSYNC -> SettingsPlace.Section(SettingsSection.TIGERSYNC)
            LANGUAGE -> SettingsPlace.Section(SettingsSection.LANGUAGE)
            APP_ICON, STORAGE, OTHER_SETTINGS -> SettingsPlace.Section(SettingsSection.OTHER)
            API_ENDPOINT, VIBRATION, SCREEN_ROTATION, ANALYTICS -> SettingsPlace.SubPage(SettingsSubPage.OTHER_SETTINGS)
            VERSION, CHECK_FOR_UPDATES, WHATS_NEW, WEBSITE, ABOUT_OTHERS -> SettingsPlace.Section(SettingsSection.ABOUT)
            SERVER_STATUS -> SettingsPlace.SubPage(SettingsSubPage.ABOUT_OTHERS)
            SHOW_FULL_DUE_DATE, INVERT_SLIDER, ASSIGNMENT_REMINDERS, LIVE_ACTIVITY -> SettingsPlace.Page(PageSettings.HOME)
            CLASS_TABLE_LIST, PIN_TODAY_COURSES, SHOW_CLASSROOM, OUTLINE_REQUIRED_COURSES, SHOW_ALL_PERIODS, COURSE_NAME_SIZE,
            CLASS_REMINDERS, ENGLISH_COURSE_NAMES, ENGLISH_CLASSROOM_NAMES, MANDARIN_CLASSROOM_NAMES,
            REASSIGN_COURSE_COLORS, CLASS_TABLE_STORAGE -> SettingsPlace.Page(PageSettings.CLASS_TABLE)
            BULLETIN_PUSH -> SettingsPlace.Page(PageSettings.BULLETINS)
            MAIL_NOTIFICATIONS, MAIL_DISPLAY_NAME, MAIL_STORAGE -> SettingsPlace.Page(PageSettings.MAIL)
            FLIP_TO_LIBRARY -> SettingsPlace.Page(PageSettings.LIBRARY)
            LINK_OPENING_METHOD, WEBSITE_DATA -> SettingsPlace.Page(PageSettings.BROWSER)
            MOODLE_OPEN_IN, PIN_MYSELF, MOODLE_STORAGE -> SettingsPlace.Page(PageSettings.MOODLE)
        }

    /**
     * What the row needs before it takes input. iOS also gates the reminders and Live Updates on
     * TigerSync, and Moodle's notifications, which Android has not, on the school sign-in.
     */
    val gate: SettingsGate?
        get() = when (this) {
            BULLETIN_PUSH, NOTIFICATION_BULLETINS -> SettingsGate.SCHOOL_SIGN_IN
            MAIL_NOTIFICATIONS, MAIL_DISPLAY_NAME, NOTIFICATION_MAIL -> SettingsGate.MAIL_SIGN_IN
            FLIP_TO_LIBRARY -> SettingsGate.LIBRARY_SIGN_IN
            else -> null
        }

    /** Whether the row is there at all. A closed gate leaves it there, dimmed. */
    fun isShown(state: SettingsState): Boolean = when (this) {
        ACCENT_GRADIENT_ON_WATCH -> state.hasWatch
        ACCENT_GRADIENT_ON_LIVE_ACTIVITIES -> state.hasLiveUpdateGradient
        CHECK_FOR_UPDATES -> state.hasUpdateCheck
        WHATS_NEW -> state.hasWhatsNew
        ENGLISH_COURSE_NAMES, ENGLISH_CLASSROOM_NAMES -> state.coursesInEnglish
        MANDARIN_CLASSROOM_NAMES -> state.coursesInEnglish && state.usesEnglishClassroomNames
        FLIP_TO_LIBRARY -> state.hasFlipToLibrary
        MOODLE_OPEN_IN -> state.hasMoodleApp
        CLASS_TABLE_LIST -> state.hasImportedClassTables
        else -> true
    }

    /** What the row's toast says while its gate is closed; null while it takes input. */
    @StringRes
    fun closedReason(state: SettingsState): Int? = gate?.takeUnless { it.isOpen(state) }?.reason
}

@get:StringRes
internal val PageSettings.title: Int
    get() = when (this) {
        PageSettings.HOME -> R.string.feature_home
        PageSettings.CLASS_TABLE -> R.string.feature_class_table
        PageSettings.BULLETINS -> R.string.feature_announcements
        PageSettings.MAIL -> R.string.feature_school_mail
        PageSettings.LIBRARY -> R.string.feature_library
        PageSettings.BROWSER -> R.string.settings_page_browser
        PageSettings.MOODLE -> R.string.settings_page_moodle
    }

/**
 * Where the root links to the page: the Pages section for the app's own pages, Other settings for
 * the browser's and Moodle's, which serve every page rather than being one.
 */
internal val PageSettings.linkedFrom: RootPart
    get() = when (this) {
        PageSettings.BROWSER, PageSettings.MOODLE -> RootPart.OTHER
        PageSettings.HOME, PageSettings.CLASS_TABLE, PageSettings.BULLETINS, PageSettings.MAIL, PageSettings.LIBRARY ->
            RootPart.PAGES
    }

internal val PageSettings.rows: List<SettingsRow>
    get() = SettingsRow.entries.filter { it.place == SettingsPlace.Page(this) }

/** Whether the page has a row to show: the root lists it only then, and it closes once it has none. */
internal fun PageSettings.isShown(state: SettingsState): Boolean = rows.any { it.isShown(state) }
