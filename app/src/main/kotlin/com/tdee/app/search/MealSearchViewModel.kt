package com.tdee.app.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.MealSearchItem
import com.tdee.app.data.MealSearchResult
import com.tdee.app.data.TdeeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class MealSearchViewModel(
    private val repo: TdeeRepository,
    private val logDate: LocalDate = LocalDate.now(),
    debounceMillis: Long = 200,
) : ViewModel() {

    val query = MutableStateFlow("")

    fun setQuery(q: String) {
        query.value = q
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<MealSearchResult>> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(debounceMillis)
        .mapLatest { repo.searchMeals(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Key of the most recently logged result, so the UI can show a brief "Added." confirmation. */
    val justLogged = MutableStateFlow<String?>(null)

    fun log(result: MealSearchResult, factor: Double) {
        viewModelScope.launch {
            when (result) {
                is MealSearchResult.Saved ->
                    repo.logSavedMeal(result.savedMealId, loggedDate = logDate, factor = factor)
                is MealSearchResult.LoggedMeal ->
                    repo.repeatMeal(result.mealId, targetDate = logDate, factor = factor)
                is MealSearchResult.LoggedEntry ->
                    repo.repeatEntry(result.entryId, targetDate = logDate, factor = factor)
            }
            justLogged.value = result.key
        }
    }

    /** Logs a single item from within a matched meal as a standalone food entry, scaled by [factor]. */
    fun logItem(item: MealSearchItem, factor: Double) {
        viewModelScope.launch {
            repo.addFood(
                name = item.name,
                kcal = item.kcal * factor,
                proteinG = item.proteinG * factor,
                fatG = item.fatG * factor,
                carbG = item.carbG * factor,
                grams = item.grams?.let { it * factor },
                mealId = null,
                loggedDate = logDate,
            )
            justLogged.value = item.name
        }
    }

    companion object {
        /** Factory that logs search results onto [initialDate] instead of today. */
        fun factory(initialDate: LocalDate): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                MealSearchViewModel(app.container.repository, logDate = initialDate)
            }
        }
    }
}
