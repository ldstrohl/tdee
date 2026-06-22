package com.tdee.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.data.TdeeRepository
import com.tdee.domain.Targets
import com.tdee.domain.TdeeMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Units helpers — kg→lb conversion lives here, nowhere else
// ---------------------------------------------------------------------------

internal fun kgToLb(kg: Double): Double = kg * 2.2046226

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

/**
 * State exposed by [DashboardViewModel].
 *
 * Limitation: [todayConsumedKcal] reflects total calories only. Per-macro consumed
 * values are not yet available from the repository (DailyIntake only carries total
 * kcal), so [macroTargets] shows targets only — labeled "target" in the UI.
 */
sealed interface DashboardUiState {
    data object Loading : DashboardUiState

    data class Loaded(
        /** TDEE estimate, rounded to nearest kcal. */
        val tdeeKcal: Int,
        val tdeeMethod: TdeeMethod,
        /** True while the engine has fewer than a full window of paired data days. */
        val calibrating: Boolean,
        /** Current EMA trend weight in lb (kg × 2.2046226). */
        val trendWeightLb: Double,
        /** Calorie target for today in kcal. */
        val calorieTargetKcal: Int,
        /** Total calories consumed today, or null when no food entries exist for today. */
        val todayConsumedKcal: Int?,
        /** Macro targets — protein/fat/carb in grams. Today's per-macro consumed is
         *  not available from the repo; the UI shows targets only. */
        val macroTargets: Targets,
    ) : DashboardUiState
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class DashboardViewModel(private val repo: TdeeRepository) : ViewModel() {

    private val _state = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    /**
     * Reactive list of today's food entries. Room re-emits on every insert or soft-delete,
     * so the dashboard updates without polling or re-navigation. The window is fixed at
     * collection time — see [TdeeRepository.observeTodayFoodEntries] for the MVP caveat on
     * midnight rollovers.
     */
    val todayFoods: StateFlow<List<FoodEntryEntity>> = repo.observeTodayFoodEntries()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        load()
    }

    fun deleteFood(id: Long) {
        viewModelScope.launch { repo.softDeleteFood(id) }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                // Run independent repo calls concurrently.
                val estimateDeferred = async { repo.currentEstimate() }
                val trendKgDeferred = async { repo.currentTrendKg() }
                val targetsDeferred = async { repo.proposedTargets() }
                val consumedDeferred = async { repo.todayConsumed() }

                val estimate = estimateDeferred.await()
                val trendKg = trendKgDeferred.await()
                val targets = targetsDeferred.await()
                val consumed = consumedDeferred.await()

                _state.value = DashboardUiState.Loaded(
                    tdeeKcal = estimate.valueKcal.toInt(),
                    tdeeMethod = estimate.method,
                    calibrating = estimate.calibrating,
                    trendWeightLb = kgToLb(trendKg),
                    calorieTargetKcal = targets.calorieTargetKcal.toInt(),
                    todayConsumedKcal = consumed?.kcal?.toInt(),
                    macroTargets = targets,
                )
            } catch (e: Exception) {
                // If profile is transiently missing or data load fails, stay in Loading.
                // Routing guarantees a profile exists before this screen is shown, so
                // this path is a safety net for race conditions only.
                _state.value = DashboardUiState.Loading
            }
        }
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                DashboardViewModel(app.container.repository)
            }
        }
    }
}
