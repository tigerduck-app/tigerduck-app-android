package org.ntust.app.tigerduck.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * Detects a sustained "phone face-down" gesture using the rotation-vector
 * sensor. The two pure helpers ([isFaceDown] and [nextState]) carry all the
 * logic and are unit-tested in isolation; this class is just the
 * [SensorManager] glue around them.
 *
 * The detector emits exactly one [onFaceDown] callback per Upright -> FaceDown
 * transition; flickers under the debounce window are filtered out. A cold-
 * start [DetectorState.Unknown] state ensures opening the app while the phone
 * is already face-down does NOT auto-fire.
 *
 * Phone-only: do not instantiate from `:wear`.
 */
class FlipDetector(
    private val context: Context,
    private val onFaceDown: () -> Unit,
) {

    fun register() {
        // Filled in by Task 4.
    }

    fun unregister() {
        // Filled in by Task 4.
    }

    companion object {

        /**
         * `true` iff the device has a rotation-vector sensor we can register
         * against. Settings reads this to decide whether the toggle is
         * interactive or greyed out.
         */
        fun isSupported(context: Context): Boolean {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return false
            return sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        }

        /**
         * `R[8] <= FACE_DOWN_R8_THRESHOLD` captures both "screen facing the
         * ground" and "within ~32 degrees of flat" in one comparison. Tighter
         * (more negative) = fewer false positives but harder to trigger.
         */
        internal const val FACE_DOWN_R8_THRESHOLD = -0.85f

        /**
         * Re-arm threshold for the inverse transition (FaceDown -> Upright).
         * Looser so a momentary jiggle while reading the QR doesn't trip the
         * detector back to Upright and immediately re-fire.
         */
        internal const val UPRIGHT_R8_THRESHOLD = -0.5f

        /**
         * Returns `true` when the rotation vector indicates the device is
         * roughly face-down (within ~32 degrees of perfectly inverted).
         *
         * Pure — no Android SDK call at runtime. We compute R[8] directly
         * from the quaternion. Given unit quaternion (qx, qy, qz, qw), the
         * rotation matrix element at row 2, col 2 is:
         *
         *   R[8] = 1 - 2*(qx² + qy²)
         *
         * This is the world-frame Z-component of the device's screen-normal
         * axis. R[8] ≈ +1 means screen faces up; R[8] ≈ -1 means face-down.
         *
         * The input [rotationVector] follows Android's sensor convention:
         *   [qx*sin(θ/2), qy*sin(θ/2), qz*sin(θ/2), cos(θ/2)].
         * A 3-element vector (w omitted) is also handled: w is recovered as
         * sqrt(max(0, 1 - qx² - qy² - qz²)) to stay consistent with how
         * [android.hardware.SensorManager.getRotationMatrixFromVector] behaves
         * when the sensor omits the scalar component.
         */
        internal fun isFaceDown(rotationVector: FloatArray): Boolean {
            val qx = rotationVector[0]
            val qy = rotationVector[1]
            // R[8] = 1 - 2*(qx² + qy²) — independent of qz and qw.
            val r8 = 1f - 2f * (qx * qx + qy * qy)
            return r8 <= FACE_DOWN_R8_THRESHOLD
        }
    }
}
