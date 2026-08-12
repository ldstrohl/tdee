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
import kotlinx.coroutines.launch
import java.time.LocalDate

data class EditFoodEntryState(
    val name: String = "",
    val kcal: String = "",
    val proteinG: String = "",
    val fatG: String = "",
    val carbG: String = "",
    val grams: String = "",
    /** Editable ABSOLUTE multiplier vs. the item's native serving (displayed macros = base × scale). */
    val scale: String = "1",
) {
    private val parsedScale: Double? = scale.toDoubleOrNull()
    val scaleValid: Boolean
        get() = parsedScale != null && parsedScale > 0 && parsedScale.isFinite()

    /** Parsed [scale], falling back to 1.0 when invalid (e.g. for dialogs that need a Double). */
    val scaleFactor: Double
        get() = parsedScale?.takeIf { scaleValid } ?: 1.0

    val canSave: Boolean
        get() = name.isNotBlank() && kcal.toDoubleOrNull()?.let { it >= 0 } == true && scaleValid
}

/** Formats a factor/macro for display, rounding to 2dp to absorb floating-point compounding
 * error and trimming trailing zeros (2.0 -> "2", 1.5 -> "1.5"). Mirrors
 * [com.tdee.app.ui.MealMultiplierDialog]'s presetLabel. */
private fun formatNum(v: Double): String {
    val rounded = Math.round(v * 100) / 100.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

/**
 * Drives the single-entry food edit screen. Loads the entry with [foodId] on init and
 * pre-fills String fields; [save] writes updated name/macros/grams and flips [saved].
 */
class EditFoodEntryViewModel(
    private val repo: TdeeRepository,
    private val foodId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(EditFoodEntryState())
    val state: StateFlow<EditFoodEntryState> = _state.asStateFlow()

    private val _saved = MutableStateFlow(false)
    /** Flips to true after a successful save. Observe to navigate away. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _loggedToDate = MutableStateFlow<LocalDate?>(null)
    /** Set to the target date after a successful "log to another day"; drives a confirmation label. */
    val loggedToDate: StateFlow<LocalDate?> = _loggedToDate.asStateFlow()

    // Native (×1) values the displayed, scaled fields are derived from. Updated whenever the
    // user edits a macro/kcal/grams field directly (that field's base is redefined from the
    // typed, currently-scaled value); read whenever the scale field changes to recompute display.
    private var baseKcal = 0.0
    private var baseProtein = 0.0
    private var baseFat = 0.0
    private var baseCarb = 0.0
    private var baseGrams = 0.0

    // The entry's scaleFactor as last persisted to the DB (set on load, refreshed on successful
    // save). Used by [logToDate] instead of the live [EditFoodEntryState.scaleFactor], since that
    // field tracks unsaved edits to the scale text box.
    private var storedScaleFactor = 1.0

    init {
        viewModelScope.launch {
            val entry = repo.getFoodEntry(foodId) ?: return@launch
            val scale = entry.scaleFactor.takeIf { it > 0 } ?: 1.0
            storedScaleFactor = scale
            baseKcal = entry.kcal / scale
            baseProtein = entry.proteinG / scale
            baseFat = entry.fatG / scale
            baseCarb = entry.carbG / scale
            baseGrams = entry.grams / scale
            _state.value = EditFoodEntryState(
                name = entry.name,
                kcal = entry.kcal.toString(),
                proteinG = if (entry.proteinG > 0) entry.proteinG.toString() else "",
                fatG = if (entry.fatG > 0) entry.fatG.toString() else "",
                carbG = if (entry.carbG > 0) entry.carbG.toString() else "",
                grams = if (entry.grams > 0) entry.grams.toString() else "",
                scale = formatNum(scale),
            )
        }
    }

    fun setName(v: String) = _state.value.let { _state.value = it.copy(name = v) }

    fun setKcal(v: String) {
        v.toDoubleOrNull()?.takeIf { it >= 0 }?.let { baseKcal = it / _state.value.scaleFactor }
        _state.value = _state.value.copy(kcal = v)
    }

    fun setProteinG(v: String) {
        v.toDoubleOrNull()?.takeIf { it >= 0 }?.let { baseProtein = it / _state.value.scaleFactor }
        _state.value = _state.value.copy(proteinG = v)
    }

    fun setFatG(v: String) {
        v.toDoubleOrNull()?.takeIf { it >= 0 }?.let { baseFat = it / _state.value.scaleFactor }
        _state.value = _state.value.copy(fatG = v)
    }

    fun setCarbG(v: String) {
        v.toDoubleOrNull()?.takeIf { it >= 0 }?.let { baseCarb = it / _state.value.scaleFactor }
        _state.value = _state.value.copy(carbG = v)
    }

    fun setGrams(v: String) {
        v.toDoubleOrNull()?.takeIf { it >= 0 }?.let { baseGrams = it / _state.value.scaleFactor }
        _state.value = _state.value.copy(grams = v)
    }

    /** Edits the scale field; when it parses to a valid factor > 0, recomputes the displayed
     * macro/kcal/grams fields as base × factor. An invalid factor updates only the field itself
     * (leaves the displayed macros alone; [EditFoodEntryState.canSave] goes false). */
    fun setScale(v: String) {
        val f = v.toDoubleOrNull()
        if (f != null && f > 0 && f.isFinite()) {
            _state.value = _state.value.copy(
                scale = v,
                kcal = formatNum(baseKcal * f),
                proteinG = if (baseProtein > 0) formatNum(baseProtein * f) else "",
                fatG = if (baseFat > 0) formatNum(baseFat * f) else "",
                carbG = if (baseCarb > 0) formatNum(baseCarb * f) else "",
                grams = if (baseGrams > 0) formatNum(baseGrams * f) else "",
            )
        } else {
            _state.value = _state.value.copy(scale = v)
        }
    }

    fun save() {
        val s = _state.value
        val kcalVal = s.kcal.toDoubleOrNull()?.takeIf { it >= 0 } ?: return
        if (s.name.isBlank() || !s.scaleValid) return
        viewModelScope.launch {
            repo.updateFood(
                id = foodId,
                name = s.name.trim(),
                kcal = kcalVal,
                proteinG = s.proteinG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0,
                fatG = s.fatG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0,
                carbG = s.carbG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0,
                grams = s.grams.toDoubleOrNull()?.takeIf { v -> v >= 0 },
                scaleFactor = s.scaleFactor,
            )
            storedScaleFactor = s.scaleFactor
            _saved.value = true
        }
    }

    /**
     * Re-logs the STORED entry (not unsaved form edits) as a standalone copy on [date].
     * [confirmedFactor] is the ABSOLUTE multiplier vs. the item's original serving (what the
     * scale dialog shows/confirms, matching what's on screen); it's converted here to the
     * RELATIVE factor [TdeeRepository.repeatEntry] expects (relative to the STORED entry that
     * [TdeeRepository.repeatEntry] re-reads from the DB) by dividing by [storedScaleFactor] — the
     * last-persisted scale, NOT the live (possibly unsaved-edited) [EditFoodEntryState.scaleFactor].
     */
    fun logToDate(date: LocalDate, confirmedFactor: Double) {
        viewModelScope.launch {
            val relativeFactor = confirmedFactor / storedScaleFactor
            repo.repeatEntry(foodId, targetDate = date, factor = relativeFactor)
            _loggedToDate.value = date
        }
    }

    companion object {
        fun factory(foodId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                EditFoodEntryViewModel(app.container.repository, foodId)
            }
        }
    }
}
