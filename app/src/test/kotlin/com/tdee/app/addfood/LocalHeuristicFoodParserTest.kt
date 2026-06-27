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

    private suspend fun items(text: String) =
        (parser.parse(text) as ParseResult.Success).items

    @Test
    fun `splits on the word and`() = runTest {
        val items = items("2 eggs and oatmeal")
        assertEquals(listOf("2 eggs", "oatmeal"), items.map { it.name })
    }

    @Test
    fun `splits on commas`() = runTest {
        val items = items("apple, banana, toast")
        assertEquals(listOf("apple", "banana", "toast"), items.map { it.name })
    }

    @Test
    fun `splits on both commas and and`() = runTest {
        val items = items("apple, banana and toast")
        assertEquals(listOf("apple", "banana", "toast"), items.map { it.name })
    }

    @Test
    fun `drops blank items`() = runTest {
        val items = items("apple, , and banana")
        assertEquals(listOf("apple", "banana"), items.map { it.name })
    }

    @Test
    fun `empty text yields empty list`() = runTest {
        assertTrue(items("").isEmpty())
        assertTrue(items("   ").isEmpty())
    }

    @Test
    fun `each item has zero macros and needs confirmation`() = runTest {
        val item = items("oatmeal").single()
        assertEquals("oatmeal", item.name)
        assertEquals(0.0, item.kcal, 0.0)
        assertEquals(0.0, item.proteinG, 0.0)
        assertEquals(0.0, item.fatG, 0.0)
        assertEquals(0.0, item.carbG, 0.0)
        assertTrue(item.needsConfirmation)
    }

    @Test
    fun `does not split the substring and inside a word`() = runTest {
        val items = items("sandwich")
        assertEquals(listOf("sandwich"), items.map { it.name })
    }
}
