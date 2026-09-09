package org.ntust.app.tigerduck.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.ntust.app.tigerduck.sensor.FlipDetector.Companion.DEBOUNCE_NANOS
import org.ntust.app.tigerduck.sensor.FlipDetector.Companion.isFaceDown
import org.ntust.app.tigerduck.sensor.FlipDetector.Companion.nextState

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
    context: Context,
    private val onFaceDown: () -> Unit,
) {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var registered = false
    private var state = DetectorState.initial()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val faceDown = isFaceDown(event.values)
            state = nextState(state, faceDown, event.timestamp)
            if (state.fired) onFaceDown()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Idempotent. No-op if already registered or no rotation-vector sensor
     * exists on this device.
     */
    fun register() {
        if (registered) return
        val sm = sensorManager ?: return
        val s = sensor ?: return
        // Reset the state machine each time we (re-)register so a sensor
        // event from a previous session can't leak into the new debounce window.
        state = DetectorState.initial()
        sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI)
        registered = true
    }

    /** Idempotent. */
    fun unregister() {
        if (!registered) return
        sensorManager?.unregisterListener(listener)
        registered = false
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

        /** Debounce window — must be sustained before a transition is committed. */
        internal const val DEBOUNCE_NANOS: Long = 400_000_000L  // 400 ms

        /**
         * Sentinel stored in [DetectorState.windowStartNanos] before the first
         * sensor event is received. Guaranteed to be distinct from any real
         * nanosecond timestamp the sensor delivers.
         */
        private const val WINDOW_UNSET = Long.MIN_VALUE

        /**
         * Advance the debounce machine by one sensor event.
         *
         * The committed [Phase] transitions only when the predicate has held
         * for at least [DEBOUNCE_NANOS]. `fired` is `true` only on the
         * specific transition Upright -> FaceDown; all other transitions
         * (including the cold-start Unknown -> FaceDown) leave `fired = false`.
         */
        internal fun nextState(
            current: DetectorState,
            isFaceDown: Boolean,
            timestampNanos: Long,
        ): DetectorState {
            // Start the window on the very first event, or whenever the
            // observed predicate changes direction.
            if (current.windowStartNanos == WINDOW_UNSET || isFaceDown != current.pendingFaceDown) {
                return current.copy(
                    pendingFaceDown = isFaceDown,
                    windowStartNanos = timestampNanos,
                    fired = false,
                )
            }
            val elapsed = timestampNanos - current.windowStartNanos
            if (elapsed < DEBOUNCE_NANOS) {
                // Window still open — phase unchanged, callback not fired.
                return current.copy(fired = false)
            }
            // Window satisfied. Decide whether to commit the new phase.
            val targetPhase = if (isFaceDown) Phase.FaceDown else Phase.Upright
            if (targetPhase == current.phase) {
                return current.copy(fired = false)
            }
            val didFire = current.phase == Phase.Upright && targetPhase == Phase.FaceDown
            return current.copy(phase = targetPhase, fired = didFire)
        }
    }

    /** Tri-state for the debounce machine. */
    enum class Phase { Unknown, Upright, FaceDown }

    /**
     * Immutable state for the debounce machine.
     *
     * @property phase the committed phase (only changes after the window elapses)
     * @property pendingFaceDown the predicate value observed during the current window
     * @property windowStartNanos timestamp of the first event in the current window
     * @property fired `true` iff this transition fired the onFaceDown callback;
     *                 callers consume and reset this on each step.
     */
    data class DetectorState(
        val phase: Phase,
        val pendingFaceDown: Boolean,
        val windowStartNanos: Long,
        val fired: Boolean,
    ) {
        companion object {
            fun initial(): DetectorState = DetectorState(
                phase = Phase.Unknown,
                pendingFaceDown = false,
                windowStartNanos = Long.MIN_VALUE,
                fired = false,
            )
        }
    }
}
