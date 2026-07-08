package com.tdee.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class UnitsTest {

    @Test
    fun `kgToLb multiplies by KG_TO_LB`() {
        assertEquals(80.0 * KG_TO_LB, kgToLb(80.0), 1e-9)
    }

    @Test
    fun `lbToKg is the inverse of kgToLb`() {
        val kg = 72.5
        assertEquals(kg, lbToKg(kgToLb(kg)), 1e-9)
    }

    @Test
    fun `lbToKg divides by KG_TO_LB`() {
        assertEquals(176.0 / KG_TO_LB, lbToKg(176.0), 1e-9)
    }
}
