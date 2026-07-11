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
) {
    val canSave: Boolean
        get() = name.isNotBlank() && kcal.toDoubleOrNull()?.let { it >= 0 } == true
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

    init {
        viewModelScope.launch {
            val entry = repo.getFoodEntry(foodId) ?: return@launch
            _state.value = EditFoodEntryState(
                name = entry.name,
                kcal = entry.kcal.toString(),
                proteinG = if (entry.proteinG > 0) entry.proteinG.toString() else "",
                fatG = if (entry.fatG > 0) entry.fatG.toString() else "",
                carbG = if (entry.carbG > 0) entry.carbG.toString() else "",
                grams = if (entry.grams > 0) entry.grams.toString() else "",
            )
        }
    }

    fun setName(v: String) = _state.value.let { _state.value = it.copy(name = v) }
    fun setKcal(v: String) = _state.value.let { _state.value = _state.value.copy(kcal = v) }
    fun setProteinG(v: String) = _state.value.let { _state.value = _state.value.copy(proteinG = v) }
    fun setFatG(v: String) = _state.value.let { _state.value = _state.value.copy(fatG = v) }
    fun setCarbG(v: String) = _state.value.let { _state.value = _state.value.copy(carbG = v) }
    fun setGrams(v: String) = _state.value.let { _state.value = _state.value.copy(grams = v) }

    fun save() {
        val s = _state.value
        val kcalVal = s.kcal.toDoubleOrNull()?.takeIf { it >= 0 } ?: return
        if (s.name.isBlank()) return
        viewModelScope.launch {
            repo.updateFood(
                id = foodId,
                name = s.name.trim(),
                kcal = kcalVal,
                proteinG = s.proteinG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0,
                fatG = s.fatG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0,
                carbG = s.carbG.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: 0.0,
                grams = s.grams.toDoubleOrNull()?.takeIf { v -> v >= 0 },
            )
            _saved.value = true
        }
    }

    /** Re-logs the STORED entry (not unsaved form edits) as a standalone copy on [date], scaled by [factor]. */
    fun logToDate(date: LocalDate, factor: Double) {
        viewModelScope.launch {
            repo.repeatEntry(foodId, targetDate = date, factor = factor)
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
