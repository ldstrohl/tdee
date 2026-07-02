package com.tdee.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineTest {

    private val zone: ZoneId = ZoneOffset.UTC

    /** Instant at noon UTC on the given date (avoids day-boundary ambiguity for dayStartHour=0). */
    private fun at(date: LocalDate, hour: Int = 12): Instant =
        date.atTime(hour, 0).toInstant(ZoneOffset.UTC)

    private fun profile(
        smoothing: Int = 14,
        tdeeWindow: Int = 14,
        dayStartHour: Int = 0,
        sex: Sex = Sex.MALE,
        goalRate: Double = 0.0,
    ) = UserProfile(
        sex = sex,
        birthYear = 1990,
        heightCm = 180.0,
        activityLevel = ActivityLevel.SEDENTARY,
        goalRateKgPerWeek = goalRate,
        smoothingWindowDays = smoothing,
        tdeeWindowDays = tdeeWindow,
        dayStartHour = dayStartHour,
    )

    private fun sample(date: LocalDate, hour: Int, kg: Double, q: SampleQuality = SampleQuality.MANUAL) =
        WeightSample(at(date, hour), kg, q)

    // ---- EMA -------------------------------------------------------------

    @Test
    fun `ema matches hand-computed series with carry-forward`() {
        // N=14 -> alpha = 2/15. Days: d0=80, d1=82, (d2 missing -> carry), d3=78.
        val d0 = LocalDate.of(2026, 1, 1)
        val samples = listOf(
            sample(d0, 8, 80.0),
            sample(d0.plusDays(1), 8, 82.0),
            // d0+2 intentionally missing
            sample(d0.plusDays(3), 8, 78.0),
        )
        val engine = DefaultTdeeEngine(samples, emptyList(), profile(), zone)
        val alpha = 2.0 / 15.0

        val ema0 = 80.0
        val ema1 = alpha * 82.0 + (1 - alpha) * ema0
        val ema2 = ema1 // carried forward
        val ema3 = alpha * 78.0 + (1 - alpha) * ema2

        assertEquals(ema0, engine.weightTrendAt(at(d0)), 1e-9)
        assertEquals(ema1, engine.weightTrendAt(at(d0.plusDays(1))), 1e-9)
        assertEquals(ema2, engine.weightTrendAt(at(d0.plusDays(2))), 1e-9)
        assertEquals(ema3, engine.weightTrendAt(at(d0.plusDays(3))), 1e-9)
    }

    @Test
    fun `first sample of the log-day is used when multiple weigh-ins exist`() {
        val d0 = LocalDate.of(2026, 1, 1)
        // Morning (earliest) should win over later weigh-ins.
        val samples = listOf(
            sample(d0, 20, 90.0),  // night, later
            sample(d0, 6, 80.0),   // morning, earliest
            sample(d0, 12, 85.0),  // noon
        )
        val engine = DefaultTdeeEngine(samples, emptyList(), profile(), zone)
        // EMA seed on the only day == first (earliest) sample == 80.0
        assertEquals(80.0, engine.weightTrendAt(at(d0)), 1e-9)
    }

    // ---- Empirical TDEE --------------------------------------------------

    /**
     * Build W complete intake days + daily weights such that there is full data.
     * Weight loss should yield TDEE > avg intake.
     */
    @Test
    fun `empirical sign convention - weight loss yields tdee above intake`() {
        val w = 14
        val start = LocalDate.of(2026, 1, 1)
        // asOf is the day after the last full window day.
        // We provide weights for start..start+w (so window start/end EMAs defined),
        // and complete intake for the full window days.
        val samples = ArrayList<WeightSample>()
        val intake = ArrayList<DailyIntake>()
        var kg = 100.0
        for (i in 0..w) {
            samples.add(sample(start.plusDays(i.toLong()), 7, kg))
            kg -= 0.1 // losing weight
        }
        // intake days: the window is [windowStart..windowEnd]; provide complete intake
        // for every day we have a weight (engine will pick the windowed ones).
        for (i in 0..w) {
            intake.add(DailyIntake(start.plusDays(i.toLong()), 2000.0, complete = true))
        }
        // asOf = day after the last weight day -> windowEnd = last weight day.
        val asOf = at(start.plusDays((w + 1).toLong()))
        val engine = DefaultTdeeEngine(samples, intake, profile(smoothing = w, tdeeWindow = w), zone)
        val est = engine.estimateAt(asOf)

        assertEquals(TdeeMethod.EMPIRICAL, est.method)
        assertTrue(est.valueKcal > 2000.0, "losing weight should give TDEE > avg intake (${est.valueKcal})")
    }

    @Test
    fun `incomplete and in-progress intake days excluded from avg intake`() {
        val w = 3
        val start = LocalDate.of(2026, 1, 1)
        val samples = ArrayList<WeightSample>()
        // Flat weight so trend_delta = 0 and TDEE == avg_intake exactly.
        for (i in 0..w) samples.add(sample(start.plusDays(i.toLong()), 7, 80.0))

        // windowEnd = day before asOf. asOf log-day = start+w  -> windowEnd = start+(w-1)
        // window = [start+0 .. start+(w-1)] = days 0,1,2 for w=3.
        val intake = listOf(
            DailyIntake(start, 2000.0, complete = true),
            DailyIntake(start.plusDays(1), 9999.0, complete = false), // incomplete -> excluded
            DailyIntake(start.plusDays(2), 2200.0, complete = true),
            // in-progress current day (start+w) -> excluded by window
            DailyIntake(start.plusDays(w.toLong()), 9999.0, complete = true),
        )
        val asOf = at(start.plusDays(w.toLong()))
        val engine = DefaultTdeeEngine(samples, intake, profile(smoothing = w, tdeeWindow = w), zone)
        val est = engine.estimateAt(asOf)
        // Flat weight -> stored = 0 -> TDEE == avg of complete windowed days = (2000+2200)/2 = 2100.
        // But dataDays = 2 (< w=3) so method is BLEND; check the empirical component via a
        // dedicated full-window scenario below. Here assert the 9999 values never leaked:
        assertTrue(est.valueKcal < 5000.0, "9999 incomplete/in-progress kcal must be excluded (${est.valueKcal})")
    }

    @Test
    fun `flat weight full window shrinks toward mean of complete intake`() {
        val w = 14
        val start = LocalDate.of(2026, 1, 1)
        val samples = (0..w).map { sample(start.plusDays(it.toLong()), 7, 80.0) }
        val intake = (0..w).map { DailyIntake(start.plusDays(it.toLong()), 2500.0, complete = true) }
        val asOf = at(start.plusDays((w + 1).toLong()))
        val engine = DefaultTdeeEngine(samples, intake, profile(smoothing = w, tdeeWindow = w), zone)
        val est = engine.estimateAt(asOf)
        // Flat weight ⇒ the empirical component == mean complete intake (2500). The engine now
        // precision-weight-shrinks the Mifflin prior (formula ≈ 2100 at 80 kg) toward it, so the
        // returned value sits between prior and empirical and leans strongly toward the empirical.
        assertEquals(TdeeMethod.EMPIRICAL, est.method)
        val formula = 2100.0 // 10·80 + 6.25·180 − 5·36 + 5 = 1750 RMR × 1.2
        assertTrue(est.valueKcal > formula && est.valueKcal < 2500.0, "expected shrinkage between prior and empirical (${est.valueKcal})")
        assertTrue(est.valueKcal > (formula + 2500.0) / 2.0, "should lean toward empirical (${est.valueKcal})")
    }

    // ---- Blend boundaries ------------------------------------------------

    @Test
    fun `dataDays zero gives formula method`() {
        val start = LocalDate.of(2026, 1, 1)
        // Weights but NO intake -> no paired days.
        val samples = (0..14).map { sample(start.plusDays(it.toLong()), 7, 80.0) }
        val asOf = at(start.plusDays(15))
        val engine = DefaultTdeeEngine(samples, emptyList(), profile(), zone)
        val est = engine.estimateAt(asOf)
        assertEquals(TdeeMethod.FORMULA, est.method)
        assertTrue(est.calibrating)
    }

    @Test
    fun `partial data gives blend method`() {
        val w = 14
        val start = LocalDate.of(2026, 1, 1)
        val samples = (0..w).map { sample(start.plusDays(it.toLong()), 7, 80.0) }
        // Only 5 complete intake days within window -> dataDays = 5.
        val intake = (0 until 5).map { DailyIntake(start.plusDays(it.toLong()), 2400.0, complete = true) }
        val asOf = at(start.plusDays((w + 1).toLong()))
        val engine = DefaultTdeeEngine(samples, intake, profile(smoothing = w, tdeeWindow = w), zone)
        val est = engine.estimateAt(asOf)
        assertEquals(TdeeMethod.BLEND, est.method)
        assertTrue(est.calibrating)
    }

    @Test
    fun `full window gives empirical and calibrating flips false`() {
        val w = 14
        val start = LocalDate.of(2026, 1, 1)
        val samples = (0..w).map { sample(start.plusDays(it.toLong()), 7, 80.0) }
        val intake = (0..w).map { DailyIntake(start.plusDays(it.toLong()), 2400.0, complete = true) }
        val asOf = at(start.plusDays((w + 1).toLong()))
        val engine = DefaultTdeeEngine(samples, intake, profile(smoothing = w, tdeeWindow = w), zone)
        val est = engine.estimateAt(asOf)
        assertEquals(TdeeMethod.EMPIRICAL, est.method)
        assertFalse(est.calibrating)
    }

    // ---- Precision-weighted shrinkage (W=180, anchored-window / actual-span) ----

    /**
     * A fresh user with ~30 days of paired data produces a genuine BLEND: with W=180 the window
     * is far from full, so the value shrinks the Mifflin prior partway toward the 30-day empirical
     * and lands strictly between them (not pure formula, not pure empirical).
     */
    @Test
    fun `thirty days of paired data gives a genuine blend between formula and empirical`() {
        val start = LocalDate.of(2026, 1, 1)
        // Flat weight ⇒ empirical component == mean intake (3000), well clear of the formula prior.
        val samples = (0..30).map { sample(start.plusDays(it.toLong()), 7, 80.0) }
        val intake = (0..30).map { DailyIntake(start.plusDays(it.toLong()), 3000.0, complete = true) }
        val asOf = at(start.plusDays(31))
        val engine = DefaultTdeeEngine(samples, intake, profile(tdeeWindow = 180), zone)
        val est = engine.estimateAt(asOf)

        val formula = 2100.0 // 10·80 + 6.25·180 − 5·36 + 5 = 1750 RMR × 1.2
        assertEquals(TdeeMethod.BLEND, est.method)
        // Past the 14-day calibration horizon, so no longer flagged calibrating even though the
        // 180-day window is far from full (method stays BLEND).
        assertFalse(est.calibrating)
        assertTrue(est.valueKcal > formula && est.valueKcal < 3000.0,
            "30-day blend must sit strictly between formula prior and empirical (${est.valueKcal})")
        assertTrue(est.valueKcal > 2500.0, "even at 30 days the fast-earning empirical term should dominate (${est.valueKcal})")
    }

    /**
     * With ≥180 days of paired data the window is full: method is EMPIRICAL and the empirical
     * inverse-variance weight overwhelms the formula prior, so the estimate is ~pure empirical.
     */
    @Test
    fun `full 180-day window yields near-pure empirical`() {
        val start = LocalDate.of(2026, 1, 1)
        val samples = (0..200).map { sample(start.plusDays(it.toLong()), 7, 80.0) }
        val intake = (0..200).map { DailyIntake(start.plusDays(it.toLong()), 3000.0, complete = true) }
        val asOf = at(start.plusDays(201))
        val engine = DefaultTdeeEngine(samples, intake, profile(tdeeWindow = 180), zone)
        val est = engine.estimateAt(asOf)

        val formula = 2100.0
        assertEquals(TdeeMethod.EMPIRICAL, est.method)
        assertFalse(est.calibrating)
        // Empirical weight ≫ formula weight ⇒ value hugs the empirical 3000, far from the 2100 prior.
        assertTrue(est.valueKcal > 2980.0, "empirical term should dominate at a full window (${est.valueKcal})")
        assertTrue(3000.0 - est.valueKcal < est.valueKcal - formula,
            "estimate must be far closer to empirical than to the formula prior (${est.valueKcal})")
    }

    /**
     * The posterior SE (uncertaintyKcal) shrinks monotonically as paired data accrues toward a
     * full W=180 window — from the formula-prior SE (=SE_FORMULA) down as the empirical weight grows.
     */
    @Test
    fun `posterior uncertainty shrinks monotonically as data accrues to a full window`() {
        val start = LocalDate.of(2026, 1, 1)
        val samples = (0..200).map { sample(start.plusDays(it.toLong()), 7, 80.0) }
        val asOf = at(start.plusDays(201))

        var prev = Double.MAX_VALUE
        for (days in intArrayOf(0, 1, 5, 15, 30, 60, 120, 180, 200)) {
            val intake = (0 until days).map { DailyIntake(start.plusDays(it.toLong()), 3000.0, complete = true) }
            val engine = DefaultTdeeEngine(samples, intake, profile(tdeeWindow = 180), zone)
            val se = engine.estimateAt(asOf).uncertaintyKcal
            assertTrue(se <= prev + 1e-9, "SE must not increase as data accrues (days=$days: $se > $prev)")
            prev = se
        }
        // Ends well below the 500 kcal formula-prior SE once the window is full.
        assertTrue(prev < 100.0, "a full window should drive SE well under the 500 kcal prior (was $prev)")
    }

    @Test
    fun `uncertainty decreases monotonically as dataDays grows`() {
        val w = 14
        val start = LocalDate.of(2026, 1, 1)
        val samples = (0..w).map { sample(start.plusDays(it.toLong()), 7, 80.0) }
        val asOf = at(start.plusDays((w + 1).toLong()))

        var prev = Double.MAX_VALUE
        for (n in 0..w) {
            val intake = (0 until n).map { DailyIntake(start.plusDays(it.toLong()), 2400.0, complete = true) }
            val engine = DefaultTdeeEngine(samples, intake, profile(smoothing = w, tdeeWindow = w), zone)
            val se = engine.estimateAt(asOf).uncertaintyKcal
            assertTrue(se <= prev + 1e-9, "SE must not increase as dataDays grows (n=$n)")
            prev = se
        }
    }
}
