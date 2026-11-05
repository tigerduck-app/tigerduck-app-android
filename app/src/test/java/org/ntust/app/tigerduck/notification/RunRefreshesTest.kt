package org.ntust.app.tigerduck.notification

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignInSeries

/** What a background run refreshes once its data is in, and that it stops with its sign-in. */
class RunRefreshesTest {
    private val series = SignInSeries()
    private val steps = mutableListOf<String>()

    private fun refresh(
        duringLiveUpdate: () -> Unit = {},
        duringWidgets: () -> Unit = {},
    ) = runBlocking {
        refreshAfterRun(
            series.current,
            liveUpdate = {
                steps += "live update"
                duringLiveUpdate()
            },
            widgets = {
                steps += "widgets"
                duringWidgets()
            },
            screens = { steps += "screens" },
        )
    }

    @Test
    fun `with the sign-in standing, each is refreshed, in order`() {
        refresh()

        assertEquals(listOf("live update", "widgets", "screens"), steps)
    }

    @Test
    fun `a sign-out during the Live Update's refresh stops the rest`() {
        refresh(duringLiveUpdate = { series.end() })

        assertEquals(listOf("live update"), steps)
    }

    @Test
    fun `a same-student sign-in during the Live Update's refresh stops the rest`() {
        refresh(
            duringLiveUpdate = {
                series.end()
                series.open()
            },
        )

        assertEquals(listOf("live update"), steps)
    }

    @Test
    fun `a sign-out during the widgets' refresh leaves the screens`() {
        refresh(duringWidgets = { series.end() })

        assertEquals(listOf("live update", "widgets"), steps)
    }
}
