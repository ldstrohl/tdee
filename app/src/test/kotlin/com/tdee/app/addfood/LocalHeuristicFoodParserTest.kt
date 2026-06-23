package com.tdee.app.addfood

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LocalHeuristicFoodParser] — the local placeholder parser.
 *
 * Pure JVM (no Android), so no Robolectric runner is required.
 */
class LocalHeuristicFoodParserTest {

    private val parser = LocalHeuristicFoodParser()

    @Test
    fun `splits on the word and`() = runTest {
        val items = parser.parse("2 eggs and oatmeal")
        assertEquals(listOf("2 eggs", "oatmeal"), items.map { it.name })
    }

    @Test
    fun `splits on commas`() = runTest {
        val items = parser.parse("apple, banana, toast")
        assertEquals(listOf("apple", "banana", "toast"), items.map { it.name })
    }

    @Test
    fun `splits on both commas and and`() = runTest {
        val items = parser.parse("apple, banana and toast")
        assertEquals(listOf("apple", "banana", "toast"), items.map { it.name })
    }

    @Test
    fun `drops blank items`() = runTest {
        val items = parser.parse("apple, , and banana")
        assertEquals(listOf("apple", "banana"), items.map { it.name })
    }

    @Test
    fun `empty text yields empty list`() = runTest {
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("   ").isEmpty())
    }

    @Test
    fun `each item has zero macros and needs confirmation`() = runTest {
        val item = parser.parse("oatmeal").single()
        assertEquals("oatmeal", item.name)
        assertEquals(0.0, item.kcal, 0.0)
        assertEquals(0.0, item.proteinG, 0.0)
        assertEquals(0.0, item.fatG, 0.0)
        assertEquals(0.0, item.carbG, 0.0)
        assertTrue(item.needsConfirmation)
    }

    @Test
    fun `does not split the substring and inside a word`() = runTest {
        val items = parser.parse("sandwich")
        assertEquals(listOf("sandwich"), items.map { it.name })
    }
}
