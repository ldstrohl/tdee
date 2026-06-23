package com.tdee.app.addfood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.TdeeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Editable item state
// ---------------------------------------------------------------------------

/**
 * One editable confirmation row. All numeric fields are held as strings so the user can clear and
 * retype them; they are prefilled from a [ParsedFoodItem] (0 today via [LocalHeuristicFoodParser],
 * real macros once the Worker-backed parser lands).
 */
data class EditableFoodItem(
    val name: String = "",
    val kcal: String = "",
    val proteinG: String = "",
    val fatG: String = "",
    val carbG: String = "",
    val grams: String = "",
) {
    val kcalDouble: Double? get() = kcal.toDoubleOrNull()?.takeIf { it >= 0 }

    /** Savable when the name is non-blank and kcal is a valid non-negative number. */
    val isValid: Boolean get() = name.isNotBlank() && kcalDouble != null

    companion object {
        fun from(parsed: ParsedFoodItem) = EditableFoodItem(
            name = parsed.name,
            kcal = if (parsed.kcal > 0) parsed.kcal.toString() else "",
            proteinG = if (parsed.proteinG > 0) parsed.proteinG.toString() else "",
            fatG = if (parsed.fatG > 0) parsed.fatG.toString() else "",
            carbG = if (parsed.carbG > 0) parsed.carbG.toString() else "",
            grams = parsed.grams?.takeIf { it > 0 }?.toString() ?: "",
        )
    }
}

data class ParseConfirmState(
    val text: String = "",
    val items: List<EditableFoodItem> = emptyList(),
    val parsing: Boolean = false,
) {
    /** Save All is enabled when at least one item is valid. Invalid items are skipped on save. */
    val canSave: Boolean get() = items.any { it.isValid }
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * Drives the natural-language parse → confirm → save flow.
 *
 * [parse] runs the input text through [FoodParser] (today the local placeholder, later the
 * Worker client behind the same seam) and loads the result into an editable item list. The user
 * edits any field, adds or removes items, then [saveAll] writes each VALID item via
 * [TdeeRepository.addFood] for today's log-day (invalid items — blank name or invalid kcal — are
 * silently skipped). [saved] flips to true once writes complete so the screen can navigate away.
 */
class ParseConfirmViewModel(
    private val parser: FoodParser,
    private val repo: TdeeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ParseConfirmState())
    val state: StateFlow<ParseConfirmState> = _state.asStateFlow()

    private val _saved = MutableStateFlow(false)
    /** Flips to true after a successful save. Observe to navigate away. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    fun setText(v: String) = _state.update { it.copy(text = v) }

    fun parse() {
        val text = _state.value.text
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(parsing = true) }
            val parsed = parser.parse(text)
            _state.update {
                it.copy(
                    parsing = false,
                    items = parsed.map { p -> EditableFoodItem.from(p) },
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Item editing
    // -----------------------------------------------------------------------

    private fun updateItem(index: Int, transform: (EditableFoodItem) -> EditableFoodItem) {
        _state.update { s ->
            if (index !in s.items.indices) return@update s
            s.copy(items = s.items.toMutableList().also { it[index] = transform(it[index]) })
        }
    }

    fun setName(index: Int, v: String) = updateItem(index) { it.copy(name = v) }
    fun setKcal(index: Int, v: String) = updateItem(index) { it.copy(kcal = v) }
    fun setProteinG(index: Int, v: String) = updateItem(index) { it.copy(proteinG = v) }
    fun setFatG(index: Int, v: String) = updateItem(index) { it.copy(fatG = v) }
    fun setCarbG(index: Int, v: String) = updateItem(index) { it.copy(carbG = v) }
    fun setGrams(index: Int, v: String) = updateItem(index) { it.copy(grams = v) }

    fun addItem() = _state.update { it.copy(items = it.items + EditableFoodItem()) }

    fun removeItem(index: Int) = _state.update { s ->
        if (index !in s.items.indices) return@update s
        s.copy(items = s.items.toMutableList().also { it.removeAt(index) })
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    fun saveAll() {
        val valid = _state.value.items.filter { it.isValid }
        if (valid.isEmpty()) return
        viewModelScope.launch {
            valid.forEach { item ->
                repo.addFood(
                    name = item.name.trim(),
                    kcal = item.kcalDouble!!,
                    proteinG = item.proteinG.toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                    fatG = item.fatG.toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                    carbG = item.carbG.toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                    grams = item.grams.toDoubleOrNull()?.takeIf { it >= 0 },
                )
            }
            _saved.value = true
        }
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                ParseConfirmViewModel(app.container.foodParser, app.container.repository)
            }
        }
    }
}
