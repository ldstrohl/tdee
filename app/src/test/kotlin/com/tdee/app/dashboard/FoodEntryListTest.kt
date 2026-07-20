package com.tdee.app.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [formatFactor]: trims trailing zeros for a clean ×N display suffix. */
class FoodEntryListTest {

    @Test
    fun `formatFactor trims trailing zeros`() {
        assertEquals("2", formatFactor(2.0))
        assertEquals("1.5", formatFactor(1.5))
        assertEquals("0.5", formatFactor(0.5))
    }
}
