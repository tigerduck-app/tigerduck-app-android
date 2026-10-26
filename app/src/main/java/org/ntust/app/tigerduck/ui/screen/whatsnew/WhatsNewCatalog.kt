package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.AppState

/**
 * Feature pages, keyed by the versionCode that introduced them. The summary
 * page that follows them comes from `assets/whatsnew.json`, not from here.
 *
 * Add a release's pages under its versionCode, in the order they should
 * appear. Copy is a [WhatsNewText] — Traditional Chinese and English written
 * inline, like the summary JSON, not `app-translation` strings. A user who
 * skipped versions sees every skipped version's pages, oldest first, so keep
 * each page meaningful on its own. A page that's more than a few lines (its
 * own demo, say) goes in a file of its own, as [mailBottomBarPage] does.
 */
object WhatsNewCatalog {
    fun pages(appState: AppState): Map<Int, List<WhatsNewPage>> = mapOf(
        // Mail shipped in 2.2.0, but few noticed it then; every upgrade
        // lands on 2.3.0, so it's introduced here.
        28 to release230Pages(
            tabs = { appState.configuredTabs },
            setTabs = { appState.configuredTabs = it },
            mailBarQuestionAsked = { appState.prefs.mailBarQuestionAsked },
            markMailBarQuestionAsked = { appState.prefs.mailBarQuestionAsked = true },
        ),
        30 to release240Pages(
            isLibrarySignedIn = { appState.isLibraryLoggedIn },
            tabs = { appState.configuredTabs },
            setTabs = { appState.configuredTabs = it },
            mailBarQuestionAsked = { appState.prefs.mailBarQuestionAsked },
            markMailBarQuestionAsked = { appState.prefs.mailBarQuestionAsked = true },
        ),
    )
}

/** 2.3.0's pages: School Mail, then whether it takes a place in the bottom bar. */
internal fun release230Pages(
    tabs: () -> List<AppFeature>,
    setTabs: (List<AppFeature>) -> Unit,
    mailBarQuestionAsked: () -> Boolean = { false },
    markMailBarQuestionAsked: () -> Unit = {},
): List<WhatsNewPage> = listOf(
    schoolMailPage(tabs),
    mailBottomBarPage(tabs = tabs, setTabs = setTabs, markAsked = markMailBarQuestionAsked, wasAsked = mailBarQuestionAsked),
)

/** 2.4.0's pages: where to see them again, then the features. */
internal fun release240Pages(
    isLibrarySignedIn: () -> Boolean,
    tabs: () -> List<AppFeature>,
    setTabs: (List<AppFeature>) -> Unit,
    mailBarQuestionAsked: () -> Boolean = { false },
    markMailBarQuestionAsked: () -> Unit = {},
): List<WhatsNewPage> = listOf(
    replayPage(),
    moodleCoursePage(),
    moodleAssignmentPage(),
    classTableSharePage(),
    libraryPage(isSignedIn = isLibrarySignedIn),
    // Before the Library question: Mail in a slot of its own can fill the bar, which that page then tells.
    mailBottomBarPage(tabs = tabs, setTabs = setTabs, markAsked = markMailBarQuestionAsked, wasAsked = mailBarQuestionAsked),
    libraryBottomBarPage(isSignedIn = isLibrarySignedIn, tabs = tabs, setTabs = setTabs),
    bottomBarEditorPage(),
    mailNotificationsPage(),
    emptyClassroomPage(),
    displaySizePage(),
)
