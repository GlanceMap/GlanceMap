package com.glancemap.glancemapwearos.domain.sensors

import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FusedOrientationIntegritySensorMonitorTest {
    @Test
    fun screenTopUsesDisplayAxesForAllFourRotations() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        listOf(0f, 270f, 180f, 90f).forEachIndexed { rotation, expected ->
            val witness = gameRotationScreenTopWitness(identity, rotation)
            assertEquals(expected, requireNotNull(witness.headingDeg), ANGLE_TOLERANCE_DEG)
            assertEquals(rotation, witness.displayRotation)
        }
    }

    @Test
    fun displayRotationSelectsTheActualHorizontalAxisOnATiltedWatch() {
        val cosine = 0.2f
        val sine = kotlin.math.sqrt(1f - cosine * cosine)
        val tilted = floatArrayOf(1f, 0f, 0f, 0f, cosine, -sine, 0f, sine, cosine)
        assertNull(gameRotationScreenTopWitness(tilted, 0).headingDeg)
        assertEquals(270f, requireNotNull(gameRotationScreenTopWitness(tilted, 1).headingDeg), ANGLE_TOLERANCE_DEG)
    }

    @Test
    fun screenTopProjectionUsesTheSamePhysicalAxisAsTheMapHeading() {
        val witness =
            gameRotationScreenTopWitness(
                rotationMatrix(
                    screenTopEast = 1f,
                    screenTopNorth = 0f,
                ),
            )

        assertEquals(90f, requireNotNull(witness.headingDeg), ANGLE_TOLERANCE_DEG)
        assertEquals(1f, witness.horizontalProjection, ANGLE_TOLERANCE_DEG)
    }

    @Test
    fun tiltedWatchWithoutHorizontalScreenTopHasNoHeadingWitness() {
        val witness =
            gameRotationScreenTopWitness(
                rotationMatrix(
                    screenTopEast = 0.1f,
                    screenTopNorth = 0.1f,
                ),
            )

        assertNull(witness.headingDeg)
        assertTrue(witness.horizontalProjection < 0.35f)
    }

    @Test
    fun normalWristPitchRetainsAStableScreenTopHeading() {
        val witness =
            gameRotationScreenTopWitness(
                rotationMatrix(
                    screenTopEast = 0f,
                    screenTopNorth = 0.8f,
                ),
            )

        assertEquals(0f, requireNotNull(witness.headingDeg), ANGLE_TOLERANCE_DEG)
        assertEquals(0.8f, witness.horizontalProjection, ANGLE_TOLERANCE_DEG)
    }

    @Test
    fun implausibleSingleSampleJumpIsRejected() {
        assertTrue(isPlausibleRelativeHeadingStep(20f, elapsedMs = 20L))
        assertFalse(isPlausibleRelativeHeadingStep(45f, elapsedMs = 20L))
        assertTrue(isPlausibleRelativeHeadingStep(90f, elapsedMs = 200L))
    }

    @Test
    fun sensorEventCallbackRemainsBoundToTheRegistrationThatCapturedIt() {
        val callbacks = mutableListOf<String>()
        val oldRegistration =
            requireNotNull(
                captureFusedIntegrityMonitorCallbacks(
                    registrationGeneration = 1L,
                    activeRegistrationGeneration = 1L,
                    started = true,
                    relativeRegistered = true,
                    magneticRegistered = true,
                    onRelativeHeading = { _, _ -> callbacks += "old" },
                    onMagneticField = { _, _ -> callbacks += "old_magnetic" },
                ),
            )
        val newRegistration =
            requireNotNull(
                captureFusedIntegrityMonitorCallbacks(
                    registrationGeneration = 2L,
                    activeRegistrationGeneration = 2L,
                    started = true,
                    relativeRegistered = true,
                    magneticRegistered = true,
                    onRelativeHeading = { _, _ -> callbacks += "new" },
                    onMagneticField = { _, _ -> callbacks += "new_magnetic" },
                ),
            )

        oldRegistration.onRelativeHeading?.invoke(
            RelativeHeadingWitness(headingDeg = 10f, horizontalProjection = 0.9f),
            1_000L,
        )

        assertEquals(1L, oldRegistration.registrationGeneration)
        assertEquals(2L, newRegistration.registrationGeneration)
        assertEquals(listOf("old"), callbacks)
    }

    @Test
    fun retiredListenerWaitingForRestartIsRejectedBeforeReadingItsEvent() {
        val monitor = allocateWithoutAndroid(FusedOrientationIntegritySensorMonitor::class.java)
        setMonitorField(monitor, "started", true)
        setMonitorField(monitor, "registrationGeneration", 1L)
        setMonitorField(monitor, "gameRotationVectorRegistered", true)
        setMonitorField(monitor, "magnetometerRegistered", true)
        val oldListener = createListener(monitor, generation = 1L)
        val event = allocateWithoutAndroid(SensorEvent::class.java)
        val dispatched = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker =
            Thread {
                dispatched.countDown()
                try {
                    // The uninitialized event must never be read by a retired listener.
                    oldListener.onSensorChanged(event)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

        synchronized(monitor) {
            worker.start()
            assertTrue(dispatched.await(2L, TimeUnit.SECONDS))
            setMonitorField(monitor, "registrationGeneration", 2L)
        }
        worker.join(2_000L)

        assertFalse("Old callback did not finish", worker.isAlive)
        assertNull("Old listener read an event after restart", failure.get())
    }

    @Test
    fun stoppedRegistrationCannotCaptureCallbacks() {
        assertNull(
            captureFusedIntegrityMonitorCallbacks(
                registrationGeneration = 1L,
                activeRegistrationGeneration = 1L,
                started = false,
                relativeRegistered = true,
                magneticRegistered = true,
                onRelativeHeading = { _, _ -> error("Stopped registration callback") },
                onMagneticField = { _, _ -> error("Stopped registration callback") },
            ),
        )
    }

    private fun createListener(
        monitor: FusedOrientationIntegritySensorMonitor,
        generation: Long,
    ): SensorEventListener {
        val relative: (RelativeHeadingWitness, Long) -> Unit = { _, _ -> error("Retired relative callback") }
        val magnetic: (Float, Long) -> Unit = { _, _ -> error("Retired magnetic callback") }
        val create =
            FusedOrientationIntegritySensorMonitor::class.java
                .getDeclaredMethod(
                    "createSensorListener",
                    Long::class.javaPrimitiveType,
                    Function2::class.java,
                    Function2::class.java,
                ).apply { isAccessible = true }
        return create.invoke(monitor, generation, relative, magnetic) as SensorEventListener
    }

    private fun setMonitorField(
        monitor: FusedOrientationIntegritySensorMonitor,
        name: String,
        value: Any,
    ) {
        FusedOrientationIntegritySensorMonitor::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(monitor, value)
    }

    private fun <T> allocateWithoutAndroid(type: Class<T>): T {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        return requireNotNull(type.cast(unsafe.allocateInstance(type)))
    }

    private fun rotationMatrix(
        screenTopEast: Float,
        screenTopNorth: Float,
    ): FloatArray =
        floatArrayOf(
            1f,
            screenTopEast,
            0f,
            0f,
            screenTopNorth,
            0f,
            0f,
            0f,
            1f,
        )

    private companion object {
        const val ANGLE_TOLERANCE_DEG = 0.01f
    }
}
