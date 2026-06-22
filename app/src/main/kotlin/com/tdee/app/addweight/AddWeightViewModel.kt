package com.tdee.app.addweight

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

class AddWeightViewModel(
    private val repo: TdeeRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _weightLb = MutableStateFlow("")
    val weightLb: StateFlow<String> = _weightLb.asStateFlow()

    private val _selectedDate = MutableStateFlow(today)
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _saved = MutableStateFlow(false)
    /** Flips to true after a successful save. Observe to navigate away. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** Save is enabled when weightLb is a valid positive number. */
    val canSave: Boolean get() = _weightLb.value.toDoubleOrNull()?.let { it > 0 } == true

    fun setWeightLb(v: String) {
        _weightLb.value = v
    }

    /**
     * Sets the log date. Silently clamps future dates to [today] so the UI cannot
     * schedule weight entries in the future.
     */
    fun setSelectedDate(date: LocalDate) {
        _selectedDate.value = minOf(date, today)
    }

    fun save() {
        val lb = _weightLb.value.toDoubleOrNull()?.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.addWeight(lb, loggedDate = _selectedDate.value)
            _saved.value = true
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                AddWeightViewModel(app.container.repository)
            }
        }
    }
}
