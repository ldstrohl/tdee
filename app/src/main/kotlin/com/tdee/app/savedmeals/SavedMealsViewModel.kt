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

class SavedMealsViewModel(private val repo: TdeeRepository) : ViewModel() {

    val meals: StateFlow<List<SavedMealEntity>> = repo.observeSavedMeals()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun logToday(savedMealId: Long) {
        viewModelScope.launch { repo.logSavedMeal(savedMealId) }
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
    }
}
