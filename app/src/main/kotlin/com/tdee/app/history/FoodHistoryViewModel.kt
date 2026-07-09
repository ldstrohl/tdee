package com.tdee.app.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.data.TdeeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class FoodHistoryViewModel(private val repo: TdeeRepository) : ViewModel() {

    val selectedDate: MutableStateFlow<LocalDate> = MutableStateFlow(LocalDate.now())

    private val _entries = MutableStateFlow<List<FoodEntryEntity>>(emptyList())
    val entries: StateFlow<List<FoodEntryEntity>> = _entries.asStateFlow()

    init {
        load()
    }

    fun setDate(date: LocalDate) {
        selectedDate.value = date
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _entries.value = repo.foodEntriesForDate(selectedDate.value)
        }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            repo.softDeleteFood(id)
            load()
        }
    }

    fun deleteMeal(mealId: String) {
        viewModelScope.launch {
            repo.softDeleteMeal(mealId)
            load()
        }
    }

    fun repeatMeal(mealId: String, factor: Double = 1.0) {
        viewModelScope.launch {
            repo.repeatMeal(mealId, targetDate = null, factor = factor)
        }
    }

    fun repeatEntry(id: Long, factor: Double = 1.0) {
        viewModelScope.launch {
            repo.repeatEntry(id, targetDate = null, factor = factor)
        }
    }

    fun renameMeal(mealId: String, name: String) {
        viewModelScope.launch {
            repo.renameMeal(mealId, name)
            load()
        }
    }

    fun renameEntry(id: Long, name: String) {
        viewModelScope.launch {
            repo.renameFood(id, name)
            load()
        }
    }

    fun editFood(id: Long) {
        // Navigation is handled in the composable via callback.
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                FoodHistoryViewModel(app.container.repository)
            }
        }
    }
}
