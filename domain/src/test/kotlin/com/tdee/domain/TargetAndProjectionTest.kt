package com.tdee.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class TargetAndProjectionTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun profile(goalRate: Double = 0.0) = UserProfile(
        sex = Sex.MALE,
        birthYear = 1990,
        heightCm = 180.0,
        activityLevel = ActivityLevel.SEDENTARY,
        goalRateKgPerWeek = goalRate,
    )

    private fun estimate(value: Double) =
        TdeeEstimate(value, TdeeMethod.EMPIRICAL, uncertaintyKcal = 100.0, calibrating = false)

    // ---- Targets ---------------------------------------------------------

    @Test
    fun `macros sum back to calorie target`() {
        val tdee = estimate(2500.0)
        val t = TargetCalculator.targets(tdee, bodyweightKg = 80.0, profile = profile(goalRate = -0.5))
        val reconstructed = t.proteinG * 4 + t.fatG * 9 + t.carbG * 4
        assertEquals(t.calorieTargetKcal, reconstructed, 1e-6)
    }

    @Test
    fun `deficit goal yields calorie target below tdee`() {
        val tdee = estimate(2500.0)
        val t = TargetCalculator.targets(tdee, bodyweightKg = 80.0, profile = profile(goalRate = -0.5))
        assertTrue(t.calorieTargetKcal < tdee.valueKcal)
    }

    @Test
    fun `surplus goal yields calorie target above tdee`() {
        val tdee = estimate(2500.0)
        val t = TargetCalculator.targets(tdee, bodyweightKg = 80.0, profile = profile(goalRate = 0.5))
        assertTrue(t.calorieTargetKcal > tdee.valueKcal)
    }

    @Test
    fun `protein target tracks bodyweight`() {
        val tdee = estimate(2500.0)
        val t = TargetCalculator.targets(tdee, bodyweightKg = 80.0, profile = profile())
        assertEquals(160.0, t.proteinG, 1e-9) // 2.0 g/kg * 80
    }

    // ---- Projection ------------------------------------------------------

    @Test
    fun `deficit projection gives negative rate and future date`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val tdee = estimate(2500.0)
        val p = GoalProjector.project(
            scenarioIntakeKcal = 2000.0,
            tdee = tdee,
            trendNowKg = 90.0,
            goalKg = 85.0,
            asOf = asOf,
            zone = zone,
            profile = profile(),
        )
        val r = assertIs<Projection.Reachable>(p)
        assertTrue(r.rateKgPerDay < 0.0)
        assertTrue(r.predictedDate.isAfter(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `surplus while cutting is unreachable`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val tdee = estimate(2500.0)
        // Want to lose weight (goal < now) but eating above TDEE -> gaining.
        val p = GoalProjector.project(
            scenarioIntakeKcal = 3000.0,
            tdee = tdee,
            trendNowKg = 90.0,
            goalKg = 85.0,
            asOf = asOf,
            zone = zone,
            profile = profile(),
        )
        assertIs<Projection.Unreachable>(p)
    }

    @Test
    fun `zero rate is unreachable`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val tdee = estimate(2500.0)
        val p = GoalProjector.project(
            scenarioIntakeKcal = 2500.0, // == TDEE -> rate 0
            tdee = tdee,
            trendNowKg = 90.0,
            goalKg = 85.0,
            asOf = asOf,
            zone = zone,
            profile = profile(),
        )
        assertIs<Projection.Unreachable>(p)
    }

    @Test
    fun `surplus projection toward gain goal is reachable with positive rate`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val tdee = estimate(2500.0)
        val p = GoalProjector.project(
            scenarioIntakeKcal = 3000.0,
            tdee = tdee,
            trendNowKg = 70.0,
            goalKg = 75.0,
            asOf = asOf,
            zone = zone,
            profile = profile(),
        )
        val r = assertIs<Projection.Reachable>(p)
        assertTrue(r.rateKgPerDay > 0.0)
    }

    // ---- projectAtRate ---------------------------------------------------

    @Test
    fun `projectAtRate deficit toward lower goal is reachable with negative rate`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        // losing 0.5 kg/week ≈ -0.07143 kg/day
        val rate = -0.5 / 7.0
        val p = GoalProjector.projectAtRate(
            trendNowKg = 90.0,
            goalKg = 85.0,
            rateKgPerDay = rate,
            asOf = asOf,
            zone = zone,
        )
        val r = assertIs<Projection.Reachable>(p)
        assertEquals(rate, r.rateKgPerDay, 1e-9)
        assertTrue(r.predictedDate.isAfter(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `projectAtRate predicted date is approximately correct`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        // Losing exactly 1 kg/day: 5 kg gap → should land on 2026-01-06
        val p = GoalProjector.projectAtRate(
            trendNowKg = 90.0,
            goalKg = 85.0,
            rateKgPerDay = -1.0,
            asOf = asOf,
            zone = zone,
        )
        val r = assertIs<Projection.Reachable>(p)
        assertEquals(LocalDate.of(2026, 1, 6), r.predictedDate)
    }

    @Test
    fun `projectAtRate zero rate is unreachable`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val p = GoalProjector.projectAtRate(
            trendNowKg = 90.0,
            goalKg = 85.0,
            rateKgPerDay = 0.0,
            asOf = asOf,
            zone = zone,
        )
        assertIs<Projection.Unreachable>(p)
    }

    @Test
    fun `projectAtRate wrong-sign rate is unreachable`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        // Goal is below trend but rate is positive (gaining) — wrong direction
        val p = GoalProjector.projectAtRate(
            trendNowKg = 90.0,
            goalKg = 85.0,
            rateKgPerDay = 0.1,
            asOf = asOf,
            zone = zone,
        )
        assertIs<Projection.Unreachable>(p)
    }

    @Test
    fun `projectAtRate already at goal is unreachable`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val p = GoalProjector.projectAtRate(
            trendNowKg = 85.0,
            goalKg = 85.0,
            rateKgPerDay = -0.1,
            asOf = asOf,
            zone = zone,
        )
        assertIs<Projection.Unreachable>(p)
    }

    @Test
    fun `projectAtRate positive rate toward gain goal is reachable`() {
        val asOf = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val p = GoalProjector.projectAtRate(
            trendNowKg = 70.0,
            goalKg = 75.0,
            rateKgPerDay = 0.5 / 7.0,
            asOf = asOf,
            zone = zone,
        )
        val r = assertIs<Projection.Reachable>(p)
        assertTrue(r.rateKgPerDay > 0.0)
        assertTrue(r.predictedDate.isAfter(LocalDate.of(2026, 1, 1)))
    }
}
