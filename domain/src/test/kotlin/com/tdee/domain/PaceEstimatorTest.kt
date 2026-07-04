package com.tdee.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaceEstimatorTest {

    @Test
    fun `expectedPace blends recent and long-run with lambda`() {
        val recent = -0.10  // kg/day
        val longRun = -0.04 // kg/day
        // span >= MIN_LONGRUN_SPAN_DAYS -> full blend.
        // 0.3·(-0.10) + 0.7·(-0.04) = -0.030 + -0.028 = -0.058
        val expected = 0.3 * recent + 0.7 * longRun
        assertEquals(expected, PaceEstimator.expectedPace(recent, longRun, 90L), 1e-12)
        assertEquals(-0.058, PaceEstimator.expectedPace(recent, longRun, 90L), 1e-12)
    }

    @Test
    fun `expectedPace at the span threshold uses the blend`() {
        val recent = -0.10
        val longRun = -0.04
        val atThreshold = PaceEstimator.expectedPace(recent, longRun, PaceEstimator.MIN_LONGRUN_SPAN_DAYS)
        assertEquals(0.3 * recent + 0.7 * longRun, atThreshold, 1e-12)
    }

    @Test
    fun `expectedPace falls back to recent when long-run span is too short`() {
        val recent = -0.10
        val longRun = -0.04
        // span below the minimum -> long-run ignored, return recent alone.
        val short = PaceEstimator.MIN_LONGRUN_SPAN_DAYS - 1
        assertEquals(recent, PaceEstimator.expectedPace(recent, longRun, short), 1e-12)
        assertEquals(recent, PaceEstimator.expectedPace(recent, longRun, 0L), 1e-12)
    }

    @Test
    fun `coneHalfWidth is zero at zero horizon and linear in horizon`() {
        assertEquals(0.0, PaceEstimator.coneHalfWidthKg(0L), 1e-12)
        // Linear: doubling the horizon doubles the half-width.
        val h = 28L
        val w1 = PaceEstimator.coneHalfWidthKg(h)
        val w2 = PaceEstimator.coneHalfWidthKg(2 * h)
        assertEquals(2.0 * w1, w2, 1e-12)
        // Slope equals CONE_P90_KG_PER_DAY.
        assertEquals(PaceEstimator.CONE_P90_KG_PER_DAY * h, w1, 1e-12)
    }

    @Test
    fun `cone at 28 days matches the calibrated 5point9 lb target`() {
        // 5.9 lb -> kg; the constant is calibrated so a +28-day cone ≈ this.
        val kgPerLb = 0.45359237
        val targetKg = 5.9 * kgPerLb
        val cone28 = PaceEstimator.coneHalfWidthKg(28L)
        assertTrue(kotlin.math.abs(cone28 - targetKg) < 0.05,
            "28-day cone ($cone28 kg) should be ≈ 5.9 lb (${targetKg} kg)")
    }
}
