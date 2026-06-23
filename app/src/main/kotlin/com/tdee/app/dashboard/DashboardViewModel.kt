package com.tdee.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.ConsumedMacros
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.data.TdeeRepository
import com.tdee.domain.Targets
import com.tdee.domain.TdeeMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Units helpers — kg→lb conversion lives here, nowhere else
// ---------------------------------------------------------------------------

internal fun kgToLb(kg: Double): Double = kg * 2.2046226

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

/** Snapshot of today's consumed macros derived reactively from [todayFoods]. */
data class ConsumedTotals(
    val kcal: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbG: Int,
) {
    companion object {
        val Empty = ConsumedTotals(0, 0, 0, 0)
    }
}

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
        /**
         * Total calories consumed today derived reactively from todayFoods.
         * Null when the food list is empty (no entries logged today).
         */
        val todayConsumedKcal: Int?,
        /** Macro targets — protein/fat/carb in grams. */
        val macroTargets: Targets,
        /** Consumed macro totals derived reactively from todayFoods. */
        val consumedTotals: ConsumedTotals,
        /** True when a weekly check-in is due (no period yet, or latest ≥ 7 days old). */
        val checkinDue: Boolean,
    ) : DashboardUiState
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class DashboardViewModel(private val repo: TdeeRepository) : ViewModel() {

    // One-shot state for TDEE/targets/trend (not reactive — these require heavy engine compute).
    private val _loadedBase = MutableStateFlow<LoadedBase?>(null)

    /**
     * Reactive list of today's food entries. Room re-emits on every insert or soft-delete,
     * so the dashboard updates without polling or re-navigation. The window is fixed at
     * collection time — see [TdeeRepository.observeTodayFoodEntries] for the MVP caveat on
     * midnight rollovers.
     */
    val todayFoods: StateFlow<List<FoodEntryEntity>> = repo.observeTodayFoodEntries()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The primary UI state, combining the one-shot loaded base with the reactive food list.
     * Whenever todayFoods emits a new list, consumed totals are re-derived and the state
     * is updated automatically — no polling or re-navigation needed.
     */
    val state: StateFlow<DashboardUiState> = combine(_loadedBase, todayFoods) { base, foods ->
        if (base == null) {
            DashboardUiState.Loading
        } else {
            val consumed = foods.toConsumedTotals()
            DashboardUiState.Loaded(
                tdeeKcal = base.tdeeKcal,
                tdeeMethod = base.tdeeMethod,
                calibrating = base.calibrating,
                trendWeightLb = base.trendWeightLb,
                calorieTargetKcal = base.calorieTargetKcal,
                todayConsumedKcal = if (foods.isEmpty()) null else consumed.kcal,
                macroTargets = base.macroTargets,
                consumedTotals = consumed,
                checkinDue = base.checkinDue,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardUiState.Loading)

    init {
        load()
    }

    /**
     * Re-runs the one-shot TDEE/targets/checkin-due load. Call when returning to the dashboard
     * after a check-in or manual target edit so the displayed targets reflect the new active
     * period (the reactive food list updates on its own; these engine-derived values do not).
     */
    fun reload() = load()

    fun deleteFood(id: Long) {
        viewModelScope.launch { repo.softDeleteFood(id) }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val estimateDeferred = async { repo.currentEstimate() }
                val trendKgDeferred = async { repo.currentTrendKg() }
                val targetsDeferred = async { repo.activeTargets() }
                val checkinDueDeferred = async { repo.checkinDue() }

                val estimate = estimateDeferred.await()
                val trendKg = trendKgDeferred.await()
                val targets = targetsDeferred.await()
                val checkinDue = checkinDueDeferred.await()

                _loadedBase.value = LoadedBase(
                    tdeeKcal = estimate.valueKcal.toInt(),
                    tdeeMethod = estimate.method,
                    calibrating = estimate.calibrating,
                    trendWeightLb = kgToLb(trendKg),
                    calorieTargetKcal = targets.calorieTargetKcal.toInt(),
                    macroTargets = targets,
                    checkinDue = checkinDue,
                )
            } catch (e: Exception) {
                // Stay in Loading on error — safety net for race conditions only.
                _loadedBase.value = null
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

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Intermediate holder for one-shot TDEE/targets data (no food-derived fields). */
private data class LoadedBase(
    val tdeeKcal: Int,
    val tdeeMethod: TdeeMethod,
    val calibrating: Boolean,
    val trendWeightLb: Double,
    val calorieTargetKcal: Int,
    val macroTargets: Targets,
    val checkinDue: Boolean,
)

private fun List<FoodEntryEntity>.toConsumedTotals() = ConsumedTotals(
    kcal = sumOf { it.kcal }.toInt(),
    proteinG = sumOf { it.proteinG }.toInt(),
    fatG = sumOf { it.fatG }.toInt(),
    carbG = sumOf { it.carbG }.toInt(),
)
