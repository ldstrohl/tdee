package com.tdee.app.savedmeals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.SavedMealEntity
import com.tdee.app.data.TdeeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class SavedMealsViewModel(
    private val repo: TdeeRepository,
    private val logDate: LocalDate = LocalDate.now(),
) : ViewModel() {

    val meals: StateFlow<List<SavedMealEntity>> = repo.observeSavedMeals()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Logs the saved meal onto [logDate] (today when no date was passed via [factory]), scaled
     * by [factor] (1.0 = no change).
     */
    fun logToDate(savedMealId: Long, factor: Double = 1.0) {
        viewModelScope.launch { repo.logSavedMeal(savedMealId, logDate, factor) }
    }

    fun delete(savedMealId: Long) {
        viewModelScope.launch { repo.deleteSavedMeal(savedMealId) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                SavedMealsViewModel(app.container.repository)
            }
        }

        /** Factory that logs saved meals onto [initialDate] instead of today. */
        fun factory(initialDate: LocalDate): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                SavedMealsViewModel(app.container.repository, logDate = initialDate)
            }
        }
    }
}
