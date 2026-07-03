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

    private fun ready(goalPace: PaceUi, currentPace: PaceUi) = ProjectionUi.Ready(
        goalLb = 170.0,
        currentTrendLb = 180.0,
        goalPace = goalPace,
        currentPace = currentPace,
    )

    private fun reachable(date: LocalDate) = PaceUi.Reachable(date, rateLbPerDay = -0.1)

    @Test
    fun `furthest reachable date is the max of the two reachable paces`() {
        val p = ready(reachable(goalDate), reachable(currentDate))
        assertEquals(currentDate, furthestReachableDate(p))
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
        assertEquals(currentDate, extendedDataMax(lastData, p, predictionOn = true))
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
}
