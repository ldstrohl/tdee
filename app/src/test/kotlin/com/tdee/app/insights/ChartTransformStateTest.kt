package com.tdee.app.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Unit tests for [ChartTransformState] — the pinch-zoom / pan math behind the full-screen chart.
 * Pure float/date math, so plain JUnit (no Robolectric needed).
 */
class ChartTransformStateTest {

    private val dataMin = LocalDate.of(2026, 4, 1)
    private val dataMax = LocalDate.of(2026, 6, 30) // 90-day span
    private val plotPx = 800f

    private fun spanDays(window: Pair<LocalDate, LocalDate>): Long =
        ChronoUnit.DAYS.between(window.first, window.second)

    @Test
    fun `initial state shows the full data window`() {
        val t = ChartTransformState()
        assertEquals(1f, t.scale)
        assertEquals(0f, t.panFraction)
        val w = t.visibleWindow(dataMin, dataMax)
        assertEquals(dataMin, w.first)
        assertEquals(dataMax, w.second)
    }

    @Test
    fun `zooming in shrinks the window and is centered`() {
        val t = ChartTransformState()
        t.onGesture(panXpx = 0f, zoom = 2f, plotWidthPx = plotPx)
        assertEquals(2f, t.scale)
        // Window halves to ~45 days, centered → starts ~1/4 into the span.
        val w = t.visibleWindow(dataMin, dataMax)
        assertEquals(45.0, spanDays(w).toDouble(), 1.0)
        assertTrue("centered window starts after dataMin", w.first.isAfter(dataMin))
        assertTrue("centered window ends before dataMax", w.second.isBefore(dataMax))
    }

    @Test
    fun `scale is clamped to the max`() {
        val t = ChartTransformState()
        repeat(20) { t.onGesture(0f, 4f, plotPx) }
        assertEquals(ChartTransformState.MAX_SCALE, t.scale)
    }

    @Test
    fun `scale never drops below one`() {
        val t = ChartTransformState()
        t.onGesture(0f, 0.1f, plotPx)
        assertEquals(1f, t.scale)
        val w = t.visibleWindow(dataMin, dataMax)
        assertEquals(dataMin, w.first)
        assertEquals(dataMax, w.second)
    }

    @Test
    fun `panning right reveals earlier dates and is clamped to the left edge`() {
        val t = ChartTransformState()
        t.onGesture(0f, 3f, plotPx) // zoom to 3x first
        val before = t.visibleWindow(dataMin, dataMax).first
        // Drag content right repeatedly (positive panX) → window walks toward dataMin.
        repeat(20) { t.onGesture(plotPx, 1f, plotPx) }
        val after = t.visibleWindow(dataMin, dataMax)
        assertTrue("panned window moved earlier or stayed", !after.first.isAfter(before))
        assertEquals("clamped to data start", dataMin, after.first)
        assertEquals(0f, t.panFraction)
    }

    @Test
    fun `panning left is clamped to the right edge`() {
        val t = ChartTransformState()
        t.onGesture(0f, 3f, plotPx)
        repeat(20) { t.onGesture(-plotPx, 1f, plotPx) }
        val w = t.visibleWindow(dataMin, dataMax)
        assertEquals("clamped to data end", dataMax, w.second)
        // panFraction maxes at 1 - 1/scale.
        assertEquals(1f - 1f / t.scale, t.panFraction, 1e-4f)
    }

    @Test
    fun `reset returns to the full window`() {
        val t = ChartTransformState()
        t.onGesture(0f, 5f, plotPx)
        t.onGesture(plotPx, 1f, plotPx)
        t.reset()
        assertEquals(1f, t.scale)
        assertEquals(0f, t.panFraction)
        val w = t.visibleWindow(dataMin, dataMax)
        assertEquals(dataMin, w.first)
        assertEquals(dataMax, w.second)
    }
}
