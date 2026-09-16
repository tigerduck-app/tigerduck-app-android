package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.sync.ExactAlarmAccess

class SchoolMailSettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val state = InMemoryMailStateStore().apply {
        displayName = "中文名"
        diagnostics = listOf("1789466442000|ALARM|NewMail", "garbage")
    }
    private val scheduler = RecordingScheduler()
    private val vm = SchoolMailSettingsViewModel(state, scheduler, ExactAlarmAccess { false })

    @Test
    fun `initial state reads the store`() {
        val ui = vm.ui.value
        assertEquals(true, ui.notificationsEnabled)
        assertEquals("中文名", ui.displayName)
        assertFalse(ui.canExactAlarm)
        assertEquals(listOf("09/15 18:00:42 · ALARM · NewMail"), ui.diagnostics)
    }

    @Test
    fun `turning notifications off cancels checks and on schedules them`() {
        vm.setNotifications(false)
        assertFalse(state.notificationsEnabled)
        assertEquals(1, scheduler.cancelled)
        vm.setNotifications(true)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `display name is saved as typed`() {
        vm.setDisplayName("王小明")
        assertEquals("王小明", state.displayName)
        assertEquals("王小明", vm.ui.value.displayName)
    }
}
