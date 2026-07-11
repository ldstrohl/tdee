package com.tdee.app.editmeal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.data.TdeeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Drives the Edit Meal screen. Reactively lists [mealId]'s entries; supports scaling the whole
 * meal or a single item in place, and re-logging the meal to another day.
 */
class EditMealViewModel(
    private val repo: TdeeRepository,
    private val mealId: String,
) : ViewModel() {

    val entries: StateFlow<List<FoodEntryEntity>> =
        repo.observeMealEntries(mealId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _loggedToDate = MutableStateFlow<LocalDate?>(null)
    /** Set to the target date after a successful "log to another day"; drives a confirmation label. */
    val loggedToDate: StateFlow<LocalDate?> = _loggedToDate.asStateFlow()

    /** Scales every entry in the meal by [factor] in place. */
    fun scaleMeal(factor: Double) {
        viewModelScope.launch { repo.scaleMeal(mealId, factor) }
    }

    /** Scales a single entry by [factor] in place. */
    fun scaleItem(id: Long, factor: Double) {
        viewModelScope.launch { repo.scaleFood(id, factor) }
    }

    /** Re-logs the meal as a new group on [date], scaled by [factor]; the original is unchanged. */
    fun logToDate(date: LocalDate, factor: Double) {
        viewModelScope.launch {
            repo.repeatMeal(mealId, targetDate = date, factor = factor)
            _loggedToDate.value = date
        }
    }

    companion object {
        fun factory(mealId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                EditMealViewModel(app.container.repository, mealId)
            }
        }
    }
}
