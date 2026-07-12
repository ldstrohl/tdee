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
import com.tdee.domain.kgToLb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.time.LocalDate

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

    data class Error(val message: String) : DashboardUiState

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
        /** Days since the most recent weigh-in, or null if the user has no weight entries yet. */
        val daysSinceLastWeighIn: Long?,
    ) : DashboardUiState
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class DashboardViewModel(
    private val repo: TdeeRepository,
    initialDate: LocalDate = LocalDate.now(),
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    // One-shot state for TDEE/targets/trend (not reactive — these require heavy engine compute).
    private val _loadedBase = MutableStateFlow<LoadedBase?>(null)

    /** Error message from the most recent failed [load], cleared at the start of each attempt. */
    private val _loadError = MutableStateFlow<String?>(null)

    /** The current date ceiling; used to clamp future navigation. */
    private fun today(): LocalDate = clock()

    /** The currently viewed log-day. Defaults to today; can be navigated by the user. */
    val selectedDate = MutableStateFlow(initialDate)

    /**
     * Reactive list of food entries for [selectedDate]. Room re-emits on every insert or
     * soft-delete, so the dashboard updates without polling or re-navigation. Switching the
     * selected date instantly updates the list via flatMapLatest.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayFoods: StateFlow<List<FoodEntryEntity>> = selectedDate
        .flatMapLatest { repo.observeFoodEntriesForDate(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The primary UI state, combining the one-shot loaded base with the reactive food list.
     * Whenever dayFoods emits a new list, consumed totals are re-derived and the state
     * is updated automatically — no polling or re-navigation needed.
     */
    val state: StateFlow<DashboardUiState> = combine(_loadedBase, dayFoods, _loadError) { base, foods, error ->
        if (base == null) {
            if (error != null) DashboardUiState.Error(error) else DashboardUiState.Loading
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
                daysSinceLastWeighIn = base.daysSinceLastWeighIn,
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

    fun deleteMeal(mealId: String) {
        viewModelScope.launch { repo.softDeleteMeal(mealId) }
    }

    fun saveMealFromGroup(mealId: String, name: String) {
        viewModelScope.launch { repo.saveMealFromGroup(name, mealId) }
    }

    fun saveMealFromEntry(entryId: Long, name: String) {
        viewModelScope.launch { repo.saveMealFromEntry(name, entryId) }
    }

    fun renameMeal(mealId: String, name: String) {
        viewModelScope.launch { repo.renameMeal(mealId, name) }
    }

    fun renameFood(id: Long, name: String) {
        viewModelScope.launch { repo.renameFood(id, name) }
    }

    fun logMealToDate(mealId: String, date: LocalDate, factor: Double) {
        viewModelScope.launch { repo.repeatMeal(mealId, targetDate = date, factor = factor) }
    }

    // -----------------------------------------------------------------------
    // Date navigation
    // -----------------------------------------------------------------------

    /** Clamps [d] to [today] (prevents future-date navigation) and updates [selectedDate]. */
    fun setSelectedDate(d: LocalDate) { selectedDate.value = minOf(d, today()) }

    /** Moves [selectedDate] one day into the past. */
    fun prevDay() { setSelectedDate(selectedDate.value.minusDays(1)) }

    /**
     * Moves [selectedDate] one day into the future, clamped to [today].
     * No-op when already viewing today.
     */
    fun nextDay() { setSelectedDate(selectedDate.value.plusDays(1)) }

    /** Resets [selectedDate] to today. */
    fun goToToday() { selectedDate.value = today() }

    private fun load() {
        _loadError.value = null
        viewModelScope.launch {
            try {
                // supervisorScope: if one fetch fails, it must not cancel the sibling fetches
                // before their own (possible) failures are consumed below.
                val (snapshot, checkinDue, daysSinceLastWeighIn) = supervisorScope {
                    val snapshotDeferred = async { repo.dashboardSnapshot() }
                    val checkinDueDeferred = async { repo.checkinDue() }
                    val daysSinceLastWeighInDeferred = async { repo.daysSinceLastWeighIn() }

                    Triple(
                        snapshotDeferred.await(),
                        checkinDueDeferred.await(),
                        daysSinceLastWeighInDeferred.await(),
                    )
                }

                _loadedBase.value = LoadedBase(
                    tdeeKcal = snapshot.estimate.valueKcal.toInt(),
                    tdeeMethod = snapshot.estimate.method,
                    calibrating = snapshot.estimate.calibrating,
                    trendWeightLb = kgToLb(snapshot.trendKg),
                    calorieTargetKcal = snapshot.targets.calorieTargetKcal.toInt(),
                    macroTargets = snapshot.targets,
                    checkinDue = checkinDue,
                    daysSinceLastWeighIn = daysSinceLastWeighIn,
                )
            } catch (e: Exception) {
                _loadedBase.value = null
                _loadError.value = e.message ?: "Failed to load"
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
    val daysSinceLastWeighIn: Long?,
)

private fun List<FoodEntryEntity>.toConsumedTotals() = ConsumedTotals(
    kcal = sumOf { it.kcal }.toInt(),
    proteinG = sumOf { it.proteinG }.toInt(),
    fatG = sumOf { it.fatG }.toInt(),
    carbG = sumOf { it.carbG }.toInt(),
)
