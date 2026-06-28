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
import java.time.LocalDate

// ---------------------------------------------------------------------------
// Form state
// ---------------------------------------------------------------------------

data class AddFoodFormState(
    val name: String = "",
    val kcal: String = "",
    val proteinG: String = "",
    val fatG: String = "",
    val carbG: String = "",
    val grams: String = "",
    val selectedDate: LocalDate = LocalDate.now(),
) {
    val kcalDouble: Double? get() = kcal.toDoubleOrNull()?.takeIf { it >= 0 }

    /**
     * Save is enabled when name is non-blank and kcal is a valid non-negative number.
     * Macros and grams are optional and default to 0 / null when blank.
     */
    val canSave: Boolean get() = name.isNotBlank() && kcalDouble != null
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class AddFoodViewModel(
    private val repo: TdeeRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _form = MutableStateFlow(AddFoodFormState(selectedDate = today))
    val form: StateFlow<AddFoodFormState> = _form.asStateFlow()

    private val _saved = MutableStateFlow(false)
    /** Flips to true after a successful save. Observe to navigate away. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    // -----------------------------------------------------------------------
    // Field updaters
    // -----------------------------------------------------------------------

    fun setName(v: String) = _form.update { it.copy(name = v) }
    fun setKcal(v: String) = _form.update { it.copy(kcal = v) }
    fun setProteinG(v: String) = _form.update { it.copy(proteinG = v) }
    fun setFatG(v: String) = _form.update { it.copy(fatG = v) }
    fun setCarbG(v: String) = _form.update { it.copy(carbG = v) }
    fun setGrams(v: String) = _form.update { it.copy(grams = v) }

    /**
     * Sets the log date. Silently clamps future dates to [today] so the UI cannot
     * schedule food entries in the future.
     */
    fun setSelectedDate(date: LocalDate) {
        _form.update { it.copy(selectedDate = minOf(date, today)) }
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    fun save() {
        val f = _form.value
        if (!f.canSave) return

        viewModelScope.launch {
            repo.addFood(
                name = f.name.trim(),
                kcal = f.kcalDouble!!,
                proteinG = f.proteinG.toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                fatG = f.fatG.toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                carbG = f.carbG.toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                grams = f.grams.toDoubleOrNull()?.takeIf { it >= 0 },
                loggedDate = f.selectedDate,
            )
            _saved.value = true
        }
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                AddFoodViewModel(app.container.repository)
            }
        }

        /**
         * Factory that pre-selects [initialDate] as the log date. The date is clamped to
         * ≤ today by [setSelectedDate] so past-date navigation from the dashboard is applied
         * immediately but future dates are not injectable.
         */
        fun factory(initialDate: LocalDate): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                AddFoodViewModel(app.container.repository).also { it.setSelectedDate(initialDate) }
            }
        }
    }
}
