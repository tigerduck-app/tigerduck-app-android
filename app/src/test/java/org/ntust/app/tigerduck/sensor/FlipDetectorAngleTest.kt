package org.ntust.app.tigerduck.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tests for [FlipDetector.isFaceDown] — pure JVM tests over the rotation-matrix
 * thresholding. Inputs are authored as quaternions `(x*sin(θ/2), y*sin(θ/2),
 * z*sin(θ/2), cos(θ/2))`; [FlipDetector.isFaceDown] extracts R[8] from the
 * quaternion via pure arithmetic so no Android SDK stub is required.
 *
 * Vector axes used (Android device frame):
 *   X = right, Y = up (top of screen), Z = out of screen (toward user).
 * A face-up device has its Z-axis pointing up in world coords (R[8] ≈ +1).
 * A face-down device has its Z-axis pointing down (R[8] ≈ -1).
 */
class FlipDetectorAngleTest {

    @Test
    fun `face up flat is not face down`() {
        // Identity rotation: device Z-axis is world Z-axis (up).
        assertFalse(FlipDetector.isFaceDown(identity()))
    }

    @Test
    fun `face down flat is face down`() {
        // 180-degree rotation about world X-axis flips the device upside down.
        assertTrue(FlipDetector.isFaceDown(rotateAroundX(PI)))
    }

    @Test
    fun `face down tilted 20 degrees is still face down`() {
        // 180 - 20 = 160 deg about X: screen mostly faces down, 20-deg tilt.
        assertTrue(FlipDetector.isFaceDown(rotateAroundX(PI - 20.0 * PI / 180.0)))
    }

    @Test
    fun `face down tilted 45 degrees is rejected as too tilted`() {
        // 180 - 45 = 135 deg about X: screen tilted past the threshold (~32 deg
        // of flat). This is the "phone in pocket leaning against thigh" geometry
        // we don't want to fire on.
        assertFalse(FlipDetector.isFaceDown(rotateAroundX(PI - 45.0 * PI / 180.0)))
    }

    @Test
    fun `portrait vertical screen toward user is not face down`() {
        // 90 deg about X: device standing on its bottom edge, screen vertical.
        assertFalse(FlipDetector.isFaceDown(rotateAroundX(PI / 2.0)))
    }

    @Test
    fun `landscape vertical is not face down`() {
        // 90 deg about Y: device on its side, screen vertical.
        assertFalse(FlipDetector.isFaceDown(rotateAroundY(PI / 2.0)))
    }

    // --- helpers --------------------------------------------------------

    /** Identity rotation = device aligned with world frame, screen up. */
    private fun identity(): FloatArray = floatArrayOf(0f, 0f, 0f, 1f)

    /**
     * Rotation vector for an angle [theta] around the X-axis.
     * Format: `(x*sin(θ/2), y*sin(θ/2), z*sin(θ/2), cos(θ/2))`.
     */
    private fun rotateAroundX(theta: Double): FloatArray {
        val s = sin(theta / 2.0).toFloat()
        val c = cos(theta / 2.0).toFloat()
        return floatArrayOf(s, 0f, 0f, c)
    }

    private fun rotateAroundY(theta: Double): FloatArray {
        val s = sin(theta / 2.0).toFloat()
        val c = cos(theta / 2.0).toFloat()
        return floatArrayOf(0f, s, 0f, c)
    }
}
