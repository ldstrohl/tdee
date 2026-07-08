package com.tdee.app.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for the trend chart's x-domain extension helpers ([furthestReachableDate],
 * [extendedDataMax]) that let the full-screen chart's prediction overlay fit in the zoom window.
 * Pure date logic, so plain JUnit (no Robolectric needed).
 */
class TrendXDomainTest {

    private val lastData = LocalDate.of(2026, 7, 3)
    private val goalDate = LocalDate.of(2026, 9, 1)
    private val currentDate = LocalDate.of(2026, 10, 15)
    private val expectedDate = LocalDate.of(2026, 11, 20)

    private fun ready(
        goalPace: PaceUi,
        currentPace: PaceUi,
        expectedPace: PaceUi = PaceUi.Unreachable("n/a"),
        expectedRateLbPerDay: Double = -0.1,
    ) = ProjectionUi.Ready(
        goalLb = 170.0,
        currentTrendLb = 180.0,
        goalPace = goalPace,
        currentPace = currentPace,
        expectedPace = expectedPace,
        expectedRateLbPerDay = expectedRateLbPerDay,
    )

    private fun reachable(date: LocalDate) = PaceUi.Reachable(date, rateLbPerDay = -0.1)

    @Test
    fun `furthest reachable ignores the current pace (not drawn)`() {
        val p = ready(reachable(goalDate), reachable(currentDate))
        assertEquals(goalDate, furthestReachableDate(p))
    }

    @Test
    fun `furthest reachable date includes the expected pace`() {
        val p = ready(reachable(goalDate), reachable(currentDate), reachable(expectedDate))
        assertEquals(expectedDate, furthestReachableDate(p))
    }

    @Test
    fun `furthest reachable ignores unreachable paces`() {
        val p = ready(reachable(goalDate), PaceUi.Unreachable("gaining"))
        assertEquals(goalDate, furthestReachableDate(p))
    }

    @Test
    fun `furthest reachable is null when neither pace is reachable`() {
        val p = ready(PaceUi.Unreachable("x"), PaceUi.Unreachable("y"))
        assertNull(furthestReachableDate(p))
    }

    @Test
    fun `furthest reachable is null for NoGoal`() {
        assertNull(furthestReachableDate(ProjectionUi.NoGoal))
    }

    @Test
    fun `extendedDataMax returns last data date when prediction is off`() {
        val p = ready(reachable(goalDate), reachable(currentDate))
        assertEquals(lastData, extendedDataMax(lastData, p, predictionOn = false))
    }

    @Test
    fun `extendedDataMax extends to furthest reachable date when prediction on`() {
        val p = ready(reachable(goalDate), reachable(currentDate))
        assertEquals(goalDate, extendedDataMax(lastData, p, predictionOn = true))
    }

    @Test
    fun `extendedDataMax keeps last data date when projection ends before it`() {
        val past = LocalDate.of(2026, 6, 1)
        val p = ready(reachable(past), reachable(past))
        assertEquals(lastData, extendedDataMax(lastData, p, predictionOn = true))
    }

    @Test
    fun `extendedDataMax keeps last data date when no pace is reachable`() {
        val p = ready(PaceUi.Unreachable("x"), PaceUi.Unreachable("y"))
        assertEquals(lastData, extendedDataMax(lastData, p, predictionOn = true))
    }

    @Test
    fun `extendedDataMax keeps last data date for NoGoal`() {
        assertEquals(lastData, extendedDataMax(lastData, ProjectionUi.NoGoal, predictionOn = true))
    }

    // --- coneEndDate: cone extends past the nominal date to the slow edge's goal crossing ---
    // P90 cone growth c = 0.095 kg/d ≈ 0.209 lb/d.

    @Test
    fun `coneEndDate is the slow-edge goal crossing when the rate outruns the cone growth`() {
        // hGoal = 20 d at r = −0.5 lb/d ⇒ slow edge crosses goal at
        // ceil(20·0.5/(0.5−c)) = ceil(34.42) = 35 d.
        val nominal = lastData.plusDays(20)
        val p = ready(
            reachable(goalDate), reachable(currentDate),
            expectedPace = PaceUi.Reachable(nominal, rateLbPerDay = -0.5),
            expectedRateLbPerDay = -0.5,
        )
        assertEquals(lastData.plusDays(35), coneEndDate(p, lastData))
    }

    @Test
    fun `coneEndDate caps at nominal plus 90 days when the slow edge never crosses`() {
        // |r| = 0.1 lb/d ≤ c ≈ 0.209 lb/d ⇒ slow edge never re-crosses the goal.
        val nominal = lastData.plusDays(20)
        val p = ready(
            reachable(goalDate), reachable(currentDate),
            expectedPace = PaceUi.Reachable(nominal, rateLbPerDay = -0.1),
        )
        assertEquals(nominal.plusDays(90), coneEndDate(p, lastData))
    }

    @Test
    fun `coneEndDate is null when the expected pace is unreachable`() {
        val p = ready(reachable(goalDate), reachable(currentDate))
        assertNull(coneEndDate(p, lastData))
    }

    @Test
    fun `coneEndDate is null for NoGoal`() {
        assertNull(coneEndDate(ProjectionUi.NoGoal, lastData))
    }

    @Test
    fun `extendedDataMax extends to the cone end when it is furthest`() {
        // Expected pace is the furthest projection; the cone must extend past it.
        val nominal = lastData.plusDays(200)
        val p = ready(
            reachable(goalDate), reachable(currentDate),
            expectedPace = PaceUi.Reachable(nominal, rateLbPerDay = -0.1),
        )
        assertEquals(nominal.plusDays(90), extendedDataMax(lastData, p, predictionOn = true))
    }
}
