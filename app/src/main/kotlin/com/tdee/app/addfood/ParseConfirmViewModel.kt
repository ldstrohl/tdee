package com.tdee.app.addfood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.NewFoodItem
import com.tdee.app.data.TdeeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

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
    /** Non-null when the last parse failed; shown as a dismissible banner. Cleared on next parse. */
    val parseError: String? = null,
) {
    /** Save All is enabled when at least one item is valid. Invalid items are skipped on save. */
    val canSave: Boolean get() = items.any { it.isValid }

    /** Totals computed over valid items only (matching what saveAll will persist). */
    val totalKcal: Double
        get() = items.filter { it.isValid }.sumOf { it.kcalDouble ?: 0.0 }
    val totalProteinG: Double
        get() = items.filter { it.isValid }
            .sumOf { item -> item.proteinG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0 }
    val totalFatG: Double
        get() = items.filter { it.isValid }
            .sumOf { item -> item.fatG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0 }
    val totalCarbG: Double
        get() = items.filter { it.isValid }
            .sumOf { item -> item.carbG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0 }
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
 * [TdeeRepository.addFoodGroup] for the selected log-day. [saved] flips to true once writes
 * complete so the screen can navigate away.
 *
 * [saveAsMeal] saves the current valid items to the saved-meals library without navigating away.
 * [mealSaved] flips to true briefly to show a confirmation message.
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

    private val _mealSaved = MutableStateFlow(false)
    /** Flips to true after saveAsMeal completes. Resets to false on next edit. */
    val mealSaved: StateFlow<Boolean> = _mealSaved.asStateFlow()

    /** Selected log-day; defaults to today. */
    val selectedDate = MutableStateFlow<LocalDate>(LocalDate.now())

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    fun setText(v: String) {
        _mealSaved.value = false
        _state.update { it.copy(text = v) }
    }

    fun setSelectedDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun parse() {
        val text = _state.value.text
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(parsing = true, parseError = null) }
            when (val result = parser.parse(text)) {
                is ParseResult.Success -> _state.update {
                    it.copy(
                        parsing = false,
                        parseError = null,
                        items = result.items.map { p -> EditableFoodItem.from(p) },
                    )
                }
                is ParseResult.Failure -> _state.update {
                    it.copy(
                        parsing = false,
                        parseError = result.message,
                        items = emptyList(),
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Item editing
    // -----------------------------------------------------------------------

    private fun updateItem(index: Int, transform: (EditableFoodItem) -> EditableFoodItem) {
        _mealSaved.value = false
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

    fun saveAll(mealName: String? = null) {
        val valid = _state.value.items.filter { it.isValid }
        if (valid.isEmpty()) return
        viewModelScope.launch {
            val foodItems = validItems()
            val date = selectedDate.value.takeUnless { it == LocalDate.now() }
            repo.addFoodGroup(foodItems, date, mealName?.trim()?.takeIf { it.isNotBlank() })
            _saved.value = true
        }
    }

    /** Saves the current valid items to the saved-meals library under [name]. */
    fun saveAsMeal(name: String) {
        if (name.isBlank()) return
        val items = validItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            repo.saveMeal(name.trim(), items)
            _mealSaved.value = true
        }
    }

    /** Saves to the library AND adds the group with [name] as the meal name, then navigates away. */
    fun saveMealAndAdd(name: String) {
        if (name.isBlank()) return
        val items = validItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val trimmed = name.trim()
            val date = selectedDate.value.takeUnless { it == LocalDate.now() }
            repo.saveMeal(trimmed, items)
            repo.addFoodGroup(items, date, trimmed)
            _mealSaved.value = true
            _saved.value = true
        }
    }

    private fun validItems(): List<NewFoodItem> =
        _state.value.items.filter { it.isValid }.map { item ->
            NewFoodItem(
                name = item.name.trim(),
                kcal = item.kcalDouble!!,
                proteinG = item.proteinG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0,
                fatG = item.fatG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0,
                carbG = item.carbG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0,
                grams = item.grams.toDoubleOrNull()?.takeIf { v -> v >= 0 },
            )
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

        /** Factory that pre-selects [initialDate] as the log day for the parse-confirm flow. */
        fun factory(initialDate: LocalDate): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                ParseConfirmViewModel(app.container.foodParser, app.container.repository).also {
                    it.selectedDate.value = initialDate
                }
            }
        }
    }
}
