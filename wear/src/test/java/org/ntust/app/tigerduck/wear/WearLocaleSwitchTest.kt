package org.ntust.app.tigerduck.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.wear.WearLocaleSwitch.Step

class WearLocaleSwitchTest {

    private val switch = WearLocaleSwitch()

    @Test
    fun `the language already showing needs nothing, whatever its case`() {
        assertEquals(Step.Keep, switch.step(wanted = "zh-Hant-TW", showing = "zh-hant-tw"))
    }

    @Test
    fun `no language from the phone needs nothing`() {
        assertEquals(Step.Keep, switch.step(wanted = null, showing = "en-US"))
        assertEquals(Step.Keep, switch.step(wanted = " ", showing = "en-US"))
    }

    @Test
    fun `another language recreates`() {
        assertEquals(Step.Recreate, switch.step(wanted = "zh-Hant-TW", showing = "en-US"))
        assertEquals(Step.Recreate, switch.step(wanted = "en", showing = "en-US"))
    }

    @Test
    fun `a language recreated for once is not recreated for again, so it cannot loop`() {
        // The new instance still shows the old language: its start-up copy of the tag was stale,
        // or the platform would not take the language. Another recreate would end the same way.
        switch.recreating("en")
        assertEquals(Step.Stay, switch.step(wanted = "en", showing = "en-US"))
        assertEquals(Step.Stay, switch.step(wanted = "EN", showing = "en-US"))
    }

    @Test
    fun `the snapshot still loading after a recreate doesn't clear what was tried`() {
        // A new instance starts from an empty snapshot, with no language, before the stored one arrives.
        switch.recreating("en")
        assertEquals(Step.Keep, switch.step(wanted = null, showing = "en-US"))
        assertEquals(Step.Stay, switch.step(wanted = "en", showing = "en-US"))
    }

    @Test
    fun `a new language after one that stayed recreates again`() {
        switch.recreating("en")
        assertEquals(Step.Recreate, switch.step(wanted = "ja", showing = "en-US"))
    }

    @Test
    fun `once the screen shows what was wanted, switching back to an earlier language recreates again`() {
        switch.recreating("en")
        // Another sync, to Japanese, came up right on its own.
        assertEquals(Step.Keep, switch.step(wanted = "ja", showing = "ja"))
        // Back to English: not the stale "en" of before.
        assertEquals(Step.Recreate, switch.step(wanted = "en", showing = "ja"))
    }
}
