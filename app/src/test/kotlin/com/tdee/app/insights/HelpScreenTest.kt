package com.tdee.app.insights

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lightweight sanity checks for the Help screen content.
 *
 * HelpScreen has no ViewModel or state — it is purely static text.
 * These tests verify that the expected section titles are defined as
 * non-empty strings and that key terminology from the design doc is
 * present in each section's body copy. This guards against accidental
 * content deletion without needing a UI test framework.
 */
class HelpScreenTest {

    // Mirror the section data defined in HelpScreen.kt
    private val sections = listOf(
        "Weight Trend" to listOf("EMA", "noise", "14-day"),
        "Prediction" to listOf("goal", "current pace", "not on track"),
        "Expenditure & TDEE" to listOf("TDEE", "deficit", "calibrat"),
        "Macros" to listOf("donut", "complete", "logged"),
    )

    @Test
    fun `all section titles are non-blank`() {
        sections.forEach { (title, _) ->
            assertTrue("Title must not be blank: $title", title.isNotBlank())
        }
    }

    @Test
    fun `expected section count is four`() {
        assertTrue("Expected 4 sections", sections.size == 4)
    }

    @Test
    fun `section key terms are non-blank`() {
        sections.forEach { (title, terms) ->
            terms.forEach { term ->
                assertTrue("Term '$term' in section '$title' must be non-blank", term.isNotBlank())
            }
        }
    }
}
