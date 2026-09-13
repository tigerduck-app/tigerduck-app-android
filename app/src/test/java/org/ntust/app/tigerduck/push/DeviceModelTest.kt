package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceModelTest {

    @Test
    fun `adds the maker when the model leaves it out`() {
        assertEquals("Google Pixel 8", deviceModelName("Google", "Pixel 8"))
    }

    @Test
    fun `capitalises a lower-case maker`() {
        assertEquals("Samsung SM-S918B", deviceModelName("samsung", "SM-S918B"))
    }

    @Test
    fun `does not repeat a maker the model already leads with`() {
        assertEquals("OnePlus 12", deviceModelName("OnePlus", "OnePlus 12"))
        assertEquals("motorola edge 50", deviceModelName("Motorola", "motorola edge 50"))
    }

    @Test
    fun `falls back to whichever part is present, or null`() {
        assertEquals("Pixel 8", deviceModelName(null, " Pixel 8 "))
        assertEquals("Google", deviceModelName("Google", ""))
        assertNull(deviceModelName(" ", null))
    }

    @Test
    fun `never exceeds what the backend accepts`() {
        assertEquals(64, deviceModelName("Maker", "x".repeat(100))!!.length)
    }

    @Test
    fun `the register request carries it under its wire key`() {
        val json = Gson().toJsonTree(
            DeviceRegisterRequest(clientDeviceId = "d", deviceModel = "Google Pixel 8")
        ) as JsonObject
        assertEquals("Google Pixel 8", json.get("device_model").asString)
    }

    @Test
    fun `an unknown model is left off the register request`() {
        val json = Gson().toJsonTree(DeviceRegisterRequest(clientDeviceId = "d")) as JsonObject
        assertFalse(json.has("device_model"))
    }
}
