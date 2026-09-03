package com.tdee.app.addfood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.Macro
import com.tdee.app.data.NewFoodItem
import com.tdee.app.data.ProductLookup
import com.tdee.app.data.ProductLookupService
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.scaledBy
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
    /** Per-item scale multiplier, e.g. "1.5" for "50% more than the estimate". */
    val factor: String = "1",
    /** Macros that were LLM-estimated rather than sourced from Open Food Facts (barcode scans only). */
    val estimatedMacros: Set<Macro> = emptySet(),
    /** True for a row added by a barcode lookup. Such rows survive a later [ParseConfirmViewModel.parse]. */
    val fromBarcode: Boolean = false,
) {
    val kcalDouble: Double? get() = kcal.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

    /** Parsed multiplier; invalid/blank/non-positive/non-finite input falls back to 1.0 (no scaling). */
    val factorDouble: Double get() = factor.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: 1.0

    /** Savable when the name is non-blank and kcal is a valid non-negative number. */
    val isValid: Boolean get() = name.isNotBlank() && kcalDouble != null

    companion object {
        fun from(
            parsed: ParsedFoodItem,
            estimatedMacros: Set<Macro> = emptySet(),
            fromBarcode: Boolean = false,
        ) = EditableFoodItem(
            name = parsed.name,
            kcal = if (parsed.kcal > 0) parsed.kcal.toString() else "",
            proteinG = if (parsed.proteinG > 0) parsed.proteinG.toString() else "",
            fatG = if (parsed.fatG > 0) parsed.fatG.toString() else "",
            carbG = if (parsed.carbG > 0) parsed.carbG.toString() else "",
            grams = parsed.grams?.takeIf { it > 0 }?.toString() ?: "",
            estimatedMacros = estimatedMacros,
            fromBarcode = fromBarcode,
        )
    }
}

data class ParseConfirmState(
    val text: String = "",
    val items: List<EditableFoodItem> = emptyList(),
    val parsing: Boolean = false,
    /** Non-null when the last parse failed; shown as a dismissible banner. Cleared on next parse. */
    val parseError: String? = null,
    /** Meal name suggested by the parser, if any; prefills the meal-name field. */
    val mealName: String? = null,
    /** Non-null when the last saveAsMeal/saveMealAndAdd call was rejected (blank name/no items). */
    val mealSaveError: String? = null,
    /** True when this screen is appending items to an already-logged meal (see [ParseConfirmViewModel.factory]). */
    val appendMode: Boolean = false,
) {
    /** Save All is enabled when at least one item is valid. Invalid items are skipped on save. */
    val canSave: Boolean get() = items.any { it.isValid }

    /** Totals computed over valid items only, scaled by each item's factor (matches saveAll). */
    val totalKcal: Double
        get() = items.filter { it.isValid }.sumOf { (it.kcalDouble ?: 0.0) * it.factorDouble }
    val totalProteinG: Double
        get() = items.filter { it.isValid }
            .sumOf { item ->
                (item.proteinG.toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() } ?: 0.0) * item.factorDouble
            }
    val totalFatG: Double
        get() = items.filter { it.isValid }
            .sumOf { item ->
                (item.fatG.toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() } ?: 0.0) * item.factorDouble
            }
    val totalCarbG: Double
        get() = items.filter { it.isValid }
            .sumOf { item ->
                (item.carbG.toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() } ?: 0.0) * item.factorDouble
            }
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
 *
 * @param productLookup barcode → product lookup, used by [lookupBarcode]. Defaults to null, in
 *   which case the scan affordance is simply not shown (the screen checks [scanningAvailable]).
 */
class ParseConfirmViewModel(
    private val parser: FoodParser,
    private val repo: TdeeRepository,
    private val targetMealId: String? = null,
    private val productLookup: ProductLookupService? = null,
) : ViewModel() {

    /** True when barcode scanning/lookup is available; the screen uses this to show/hide it. */
    val scanningAvailable: Boolean get() = productLookup != null

    private val _state = MutableStateFlow(ParseConfirmState(appendMode = targetMealId != null))
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
        _state.update { it.copy(text = v, mealSaveError = null) }
    }

    fun setSelectedDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun parse() {
        val text = _state.value.text
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(parsing = true, parseError = null, mealSaveError = null) }
            when (val result = parser.parse(text)) {
                is ParseResult.Success -> _state.update {
                    it.copy(
                        parsing = false,
                        parseError = null,
                        // Re-parsing replaces what the last parse produced, but must not discard
                        // scanned rows: they came from a barcode, not from this text box, and
                        // silently dropping them would lose work the user cannot get back.
                        items = it.items.filter { existing -> existing.fromBarcode } +
                            result.items.map { p -> EditableFoodItem.from(p) },
                        mealName = result.mealName,
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

    /**
     * Looks up [barcode] and appends the result to the item list (a user may scan several items
     * into one meal). Blank input is a no-op. Reuses [ParseConfirmState.parsing] for the loading
     * state and [ParseConfirmState.parseError] for not-found/failure messages.
     */
    fun lookupBarcode(barcode: String) {
        // Strip non-digits: KeyboardType.Number still permits "-" and ".", and a stray character
        // silently turns a valid barcode into a not-found. Covers the scanner path too.
        val digits = barcode.filter { it.isDigit() }
        val lookup = productLookup
        if (digits.isEmpty() || lookup == null) return
        viewModelScope.launch {
            _state.update { it.copy(parsing = true, parseError = null) }
            when (val result = lookup.lookup(digits)) {
                is ProductLookup.Found -> _state.update {
                    it.copy(
                        parsing = false,
                        parseError = null,
                        items = it.items + EditableFoodItem.from(result.item, result.gaps, fromBarcode = true),
                    )
                }
                is ProductLookup.NotFound -> _state.update {
                    it.copy(
                        parsing = false,
                        parseError = "No product found for $digits. Add it by hand or describe it above.",
                    )
                }
                is ProductLookup.Failure -> _state.update {
                    it.copy(parsing = false, parseError = result.message)
                }
            }
        }
    }

    /** Called when the scanner UI itself fails to launch/complete (not a cancel — that's silent). */
    fun scannerFailed() {
        _state.update { it.copy(parseError = "Couldn't open the scanner. Try again or enter the barcode below.") }
    }

    // -----------------------------------------------------------------------
    // Item editing
    // -----------------------------------------------------------------------

    private fun updateItem(index: Int, transform: (EditableFoodItem) -> EditableFoodItem) {
        _mealSaved.value = false
        _state.update { s ->
            if (index !in s.items.indices) return@update s
            s.copy(
                items = s.items.toMutableList().also { it[index] = transform(it[index]) },
                mealSaveError = null,
            )
        }
    }

    fun setName(index: Int, v: String) = updateItem(index) { it.copy(name = v) }
    fun setKcal(index: Int, v: String) = updateItem(index) { it.copy(kcal = v) }
    fun setProteinG(index: Int, v: String) = updateItem(index) { it.copy(proteinG = v) }
    fun setFatG(index: Int, v: String) = updateItem(index) { it.copy(fatG = v) }
    fun setCarbG(index: Int, v: String) = updateItem(index) { it.copy(carbG = v) }
    fun setGrams(index: Int, v: String) = updateItem(index) { it.copy(grams = v) }
    fun setFactor(index: Int, v: String) = updateItem(index) { it.copy(factor = v) }

    fun addItem() = _state.update { it.copy(items = it.items + EditableFoodItem(), mealSaveError = null) }

    fun removeItem(index: Int) = _state.update { s ->
        if (index !in s.items.indices) return@update s
        s.copy(
            items = s.items.toMutableList().also { it.removeAt(index) },
            mealSaveError = null,
        )
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    fun saveAll(mealName: String? = null) {
        val valid = _state.value.items.filter { it.isValid }
        if (valid.isEmpty()) return
        viewModelScope.launch {
            val foodItems = validItems()
            if (targetMealId != null) {
                val added = repo.addItemsToMeal(targetMealId, foodItems)
                if (added) {
                    _saved.value = true
                } else {
                    // Meal vanished underneath us (e.g. soft-deleted elsewhere). Surface the error
                    // and stay put — `saved` must never flip, or the screen navigates away.
                    _state.update { it.copy(mealSaveError = "That meal no longer exists. Nothing was added.") }
                }
            } else {
                val date = selectedDate.value.takeUnless { it == LocalDate.now() }
                repo.addFoodGroup(foodItems, date, mealName?.trim()?.takeIf { it.isNotBlank() })
                _saved.value = true
            }
        }
    }

    /** Saves each valid item individually (no meal grouping), then navigates away. */
    fun saveAllIndividually() {
        val valid = _state.value.items.filter { it.isValid }
        if (valid.isEmpty()) return
        viewModelScope.launch {
            val foodItems = validItems()
            val date = selectedDate.value.takeUnless { it == LocalDate.now() }
            repo.addFoodItems(foodItems, date)
            _saved.value = true
        }
    }

    /** Saves the current valid items to the saved-meals library under [name]. */
    fun saveAsMeal(name: String) {
        if (name.isBlank()) {
            _state.update { it.copy(mealSaveError = "Enter a meal name before saving.") }
            return
        }
        val items = validItems()
        if (items.isEmpty()) {
            _state.update { it.copy(mealSaveError = "Add at least one item before saving.") }
            return
        }
        viewModelScope.launch {
            repo.saveMeal(name.trim(), items)
            _mealSaved.value = true
            _state.update { it.copy(mealSaveError = null) }
        }
    }

    /** Saves to the library AND adds the group with [name] as the meal name, then navigates away. */
    fun saveMealAndAdd(name: String) {
        if (name.isBlank()) {
            _state.update { it.copy(mealSaveError = "Enter a meal name before saving.") }
            return
        }
        val items = validItems()
        if (items.isEmpty()) {
            _state.update { it.copy(mealSaveError = "Add at least one item before saving.") }
            return
        }
        viewModelScope.launch {
            val trimmed = name.trim()
            val date = selectedDate.value.takeUnless { it == LocalDate.now() }
            repo.saveMeal(trimmed, items)
            repo.addFoodGroup(items, date, trimmed)
            _mealSaved.value = true
            _saved.value = true
            _state.update { it.copy(mealSaveError = null) }
        }
    }

    private fun validItems(): List<NewFoodItem> =
        _state.value.items.filter { it.isValid }.map { item ->
            NewFoodItem(
                name = item.name.trim(),
                kcal = item.kcalDouble!!,
                proteinG = item.proteinG.toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() } ?: 0.0,
                fatG = item.fatG.toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() } ?: 0.0,
                carbG = item.carbG.toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() } ?: 0.0,
                grams = item.grams.toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() },
            ).scaledBy(item.factorDouble)
        }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                ParseConfirmViewModel(
                    app.container.foodParser,
                    app.container.repository,
                    productLookup = app.container.productLookupService,
                )
            }
        }

        /** Factory that pre-selects [initialDate] as the log day for the parse-confirm flow. */
        fun factory(initialDate: LocalDate): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                ParseConfirmViewModel(
                    app.container.foodParser,
                    app.container.repository,
                    productLookup = app.container.productLookupService,
                ).also {
                    it.selectedDate.value = initialDate
                }
            }
        }

        /** Factory for appending parsed items to the already-logged meal [targetMealId]. */
        fun factory(targetMealId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                ParseConfirmViewModel(
                    app.container.foodParser,
                    app.container.repository,
                    targetMealId,
                    app.container.productLookupService,
                )
            }
        }
    }
}
