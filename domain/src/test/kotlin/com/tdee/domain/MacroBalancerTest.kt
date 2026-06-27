package com.tdee.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacroBalancerTest {

    @Test
    fun `editing one macro holds calories and rebalances the other two`() {
        val current = MacroGrams(proteinG = 150.0, fatG = 60.0, carbG = 200.0)
        val result = MacroBalancer.rebalance(2000.0, current, locked = setOf(Macro.PROTEIN))

        assertEquals(150.0, result.proteinG, 1e-9) // locked stays put
        assertEquals(2000.0, result.totalKcal(), 1e-6) // calories held
        // remaining 1400 kcal split between fat (540) and carb (800) in proportion
        assertEquals(1400.0 * 540.0 / 1340.0 / 9.0, result.fatG, 1e-6)
        assertEquals(1400.0 * 800.0 / 1340.0 / 4.0, result.carbG, 1e-6)
    }

    @Test
    fun `two locked macros leave the third to absorb the remainder`() {
        val current = MacroGrams(proteinG = 150.0, fatG = 70.0, carbG = 0.0)
        val result = MacroBalancer.rebalance(2000.0, current, locked = setOf(Macro.PROTEIN, Macro.FAT))

        assertEquals(150.0, result.proteinG, 1e-9)
        assertEquals(70.0, result.fatG, 1e-9)
        // remaining 2000 - 600 - 630 = 770 kcal -> carbs
        assertEquals(770.0 / 4.0, result.carbG, 1e-9)
        assertEquals(2000.0, result.totalKcal(), 1e-6)
    }

    @Test
    fun `all three locked returns input unchanged for the caller to surface mismatch`() {
        val current = MacroGrams(proteinG = 100.0, fatG = 50.0, carbG = 100.0) // 1250 kcal
        val result = MacroBalancer.rebalance(2000.0, current, locked = setOf(Macro.PROTEIN, Macro.FAT, Macro.CARB))

        assertEquals(current, result)
        assertTrue(result.totalKcal() < 2000.0) // mismatch visible to caller
    }

    @Test
    fun `locked macros exceeding target drive free macros to zero`() {
        val current = MacroGrams(proteinG = 300.0, fatG = 100.0, carbG = 50.0) // P1200 + F900 locked
        val result = MacroBalancer.rebalance(2000.0, current, locked = setOf(Macro.PROTEIN, Macro.FAT))

        assertEquals(0.0, result.carbG, 1e-9)
        assertEquals(2100.0, result.totalKcal(), 1e-6) // over target; caller surfaces
    }

    @Test
    fun `zero current free macros split the remainder equally`() {
        val current = MacroGrams(proteinG = 100.0, fatG = 0.0, carbG = 0.0) // lock protein = 400 kcal
        val result = MacroBalancer.rebalance(1000.0, current, locked = setOf(Macro.PROTEIN))

        // remaining 600 split equally -> 300 kcal each
        assertEquals(300.0 / 9.0, result.fatG, 1e-9)
        assertEquals(300.0 / 4.0, result.carbG, 1e-9)
        assertEquals(1000.0, result.totalKcal(), 1e-6)
    }

    @Test
    fun `nothing locked redistributes the target by current ratio`() {
        val current = MacroGrams(proteinG = 100.0, fatG = 40.0, carbG = 150.0) // 1360 kcal
        val result = MacroBalancer.rebalance(1360.0, current, locked = emptySet())

        assertEquals(1360.0, result.totalKcal(), 1e-6)
        // same total and same ratios -> grams unchanged
        assertEquals(current.proteinG, result.proteinG, 1e-6)
        assertEquals(current.fatG, result.fatG, 1e-6)
        assertEquals(current.carbG, result.carbG, 1e-6)
    }
}
