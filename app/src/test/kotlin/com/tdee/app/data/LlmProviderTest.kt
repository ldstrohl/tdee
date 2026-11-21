package com.tdee.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmProviderTest {

    @Test
    fun `removed Gemini aliases fall back to the default model`() {
        // These were presets until Google's free tier stopped serving them (503 / zero quota).
        listOf("gemini-flash-latest", "gemini-pro-latest", "", null).forEach { stored ->
            assertEquals(LlmProvider.GEMINI.defaultModel, LlmProvider.GEMINI.resolveModel(stored))
        }
    }

    @Test
    fun `a stored preset is kept`() {
        LlmProvider.entries.forEach { p ->
            p.models.forEach { assertEquals(it, p.resolveModel(it)) }
        }
    }

    @Test
    fun `every default is one of its presets`() {
        LlmProvider.entries.forEach { assert(it.defaultModel in it.models) }
    }
}
