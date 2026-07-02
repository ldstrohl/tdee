package com.tdee.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [NewFoodItem.scaledBy] and the [List]-of-[NewFoodItem] overload.
 *
 * Pure JVM (no Android), so no Robolectric runner is required.
 */
class NewFoodItemScalingTest {

    @Test
    fun `scaledBy 1point5 scales kcal, macros, and grams`() {
        val item = NewFoodItem(
            name = "Rice", kcal = 200.0, proteinG = 4.0, fatG = 1.0, carbG = 44.0, grams = 150.0,
        )

        val scaled = item.scaledBy(1.5)

        assertEquals("Rice", scaled.name)
        assertEquals(300.0, scaled.kcal, 0.001)
        assertEquals(6.0, scaled.proteinG, 0.001)
        assertEquals(1.5, scaled.fatG, 0.001)
        assertEquals(66.0, scaled.carbG, 0.001)
        assertEquals(225.0, scaled.grams!!, 0.001)
    }

    @Test
    fun `scaledBy 1point5 leaves null grams null`() {
        val item = NewFoodItem(
            name = "Banana", kcal = 105.0, proteinG = 1.3, fatG = 0.4, carbG = 27.0, grams = null,
        )

        val scaled = item.scaledBy(1.5)

        assertNull(scaled.grams)
    }

    @Test
    fun `scaledBy 1point0 is an exact no-op`() {
        val item = NewFoodItem(
            name = "Chicken", kcal = 250.0, proteinG = 30.0, fatG = 5.0, carbG = 0.0, grams = 120.0,
        )

        assertEquals(item, item.scaledBy(1.0))
    }

    @Test
    fun `List of NewFoodItem scaledBy scales every item`() {
        val items = listOf(
            NewFoodItem("A", 100.0, 10.0, 2.0, 20.0, 50.0),
            NewFoodItem("B", 200.0, 20.0, 4.0, 40.0, null),
        )

        val scaled = items.scaledBy(2.0)

        assertEquals(200.0, scaled[0].kcal, 0.001)
        assertEquals(100.0, scaled[0].grams!!, 0.001)
        assertEquals(400.0, scaled[1].kcal, 0.001)
        assertNull(scaled[1].grams)
    }
}
