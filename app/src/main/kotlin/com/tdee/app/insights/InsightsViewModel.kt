package com.tdee.app.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.ChartWindow
import com.tdee.app.data.DayExpenditurePoint
import com.tdee.app.data.DayWeightPoint
import com.tdee.app.data.MacroSummary
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.WeightProjection
import com.tdee.domain.Projection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

internal const val KG_TO_LB = 2.2046226

// ---------------------------------------------------------------------------
// Display types (all weights in lb)
// ---------------------------------------------------------------------------

/** A single chart data point with weights in lb. */
data class WeightPointLb(
    val date: LocalDate,
    val rawLb: Double?,
    val emaLb: Double,
)

/** Projection info in lb display units. */
sealed interface ProjectionUi {
    data class Ready(
        val goalLb: Double,
        val currentTrendLb: Double,
        val goalPace: PaceUi,
        val currentPace: PaceUi,
        val expectedPace: PaceUi,
        /** λ-blended expected rate in lb/day; drives the P90 cone geometry at draw time. */
        val expectedRateLbPerDay: Double,
    ) : ProjectionUi

    data object NoGoal : ProjectionUi
}

sealed interface PaceUi {
    data class Reachable(val date: LocalDate, val rateLbPerDay: Double) : PaceUi
    data class Unreachable(val reason: String) : PaceUi
}

// ---------------------------------------------------------------------------
// Range selection
// ---------------------------------------------------------------------------

enum class WeightRange(val label: String, val days: Int?) {
    M1("1 mo", 30),
    M3("3 mo", 90),
    M6("6 mo", 180),
    Y1("1 yr", 365),
    ALL("All", null),
}

// ---------------------------------------------------------------------------
// Expenditure range selection (1mo/3mo/6mo/1yr/all — no TODAY)
// ---------------------------------------------------------------------------

enum class ExpenditureRange(val label: String, val days: Int?) {
    M1("1 mo", 30),
    M3("3 mo", 90),
    M6("6 mo", 180),
    Y1("1 yr", 365),
    ALL("All", null),
}

// ---------------------------------------------------------------------------
// Macro window selection (Today/1mo/3mo/6mo/1yr/all)
// ---------------------------------------------------------------------------

enum class MacroWindow(val label: String, val chartWindow: ChartWindow) {
    TODAY("Today", ChartWindow.TODAY),
    M1("1 mo", ChartWindow.M1),
    M3("3 mo", ChartWindow.M3),
    M6("6 mo", ChartWindow.M6),
    Y1("1 yr", ChartWindow.Y1),
    ALL("All", ChartWindow.ALL),
}

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

data class InsightsUiState(
    val allPoints: List<WeightPointLb> = emptyList(),
    val visiblePoints: List<WeightPointLb> = emptyList(),
    val selectedRange: WeightRange = WeightRange.M3,
    val predictionOn: Boolean = false,
    val projection: ProjectionUi = ProjectionUi.NoGoal,
    val isLoading: Boolean = true,
    // Expenditure — independent of trend range/prediction
    val allExpenditurePoints: List<DayExpenditurePoint> = emptyList(),
    val visibleExpenditurePoints: List<DayExpenditurePoint> = emptyList(),
    val expenditureRange: ExpenditureRange = ExpenditureRange.M3,
    // Macro donut — independent of trend and expenditure
    val macroSummary: MacroSummary? = null,
    val macroWindow: MacroWindow = MacroWindow.TODAY,
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class InsightsViewModel(private val repo: TdeeRepository) : ViewModel() {

    private val _state = MutableStateFlow(InsightsUiState())
    val state: StateFlow<InsightsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun setRange(range: WeightRange) {
        val current = _state.value
        _state.value = current.copy(
            selectedRange = range,
            visiblePoints = slice(current.allPoints, range),
        )
    }

    /** Toggle prediction without touching the selected range. */
    fun setPrediction(on: Boolean) {
        _state.value = _state.value.copy(predictionOn = on)
    }

    /** Change the expenditure chart range independently of the trend range. */
    fun setExpenditureRange(range: ExpenditureRange) {
        val current = _state.value
        _state.value = current.copy(
            expenditureRange = range,
            visibleExpenditurePoints = sliceExpenditure(current.allExpenditurePoints, range),
        )
    }

    /** Change the macro window independently of the trend and expenditure ranges. */
    fun setMacroWindow(window: MacroWindow) {
        _state.value = _state.value.copy(macroWindow = window)
        viewModelScope.launch {
            try {
                val summary = repo.macroSummary(window.chartWindow)
                _state.value = _state.value.copy(macroSummary = summary)
            } catch (_: Exception) {
                // Keep previous summary on error.
            }
        }
    }

    /**
     * Seeds sample data then reloads the chart.
     * Call site must guard with BuildConfig.DEBUG; this method itself has no guard
     * so it can be called from tests.
     */
    fun seedAndReload() {
        viewModelScope.launch {
            try {
                repo.seedSampleData()
            } catch (_: Exception) {
                // Best-effort seed; reload regardless.
            }
            load()
        }
    }

    /** Re-fetches data from the repository (e.g. after seeding). */
    fun reload() {
        load()
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private fun load() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val rawSeries = repo.weightSeries()
                val allPoints = rawSeries.map { it.toLb() }
                val projection = buildProjectionUi(repo.weightProjection())
                val allExpenditure = repo.expenditureSeries()
                val macroWindow = _state.value.macroWindow
                val macroSummary = repo.macroSummary(macroWindow.chartWindow)
                val range = _state.value.selectedRange
                val expRange = _state.value.expenditureRange
                _state.value = InsightsUiState(
                    allPoints = allPoints,
                    visiblePoints = slice(allPoints, range),
                    selectedRange = range,
                    predictionOn = _state.value.predictionOn,
                    projection = projection,
                    isLoading = false,
                    allExpenditurePoints = allExpenditure,
                    visibleExpenditurePoints = sliceExpenditure(allExpenditure, expRange),
                    expenditureRange = expRange,
                    macroSummary = macroSummary,
                    macroWindow = macroWindow,
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    private fun slice(all: List<WeightPointLb>, range: WeightRange): List<WeightPointLb> {
        if (all.isEmpty()) return all
        val days = range.days ?: return all
        val cutoff = all.last().date.minusDays(days.toLong())
        return all.filter { !it.date.isBefore(cutoff) }
    }

    private fun sliceExpenditure(
        all: List<DayExpenditurePoint>,
        range: ExpenditureRange,
    ): List<DayExpenditurePoint> {
        if (all.isEmpty()) return all
        val days = range.days ?: return all
        val cutoff = all.last().date.minusDays(days.toLong())
        return all.filter { !it.date.isBefore(cutoff) }
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as TdeeApplication
                InsightsViewModel(app.container.repository)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Conversion helpers (internal so tests can use them)
// ---------------------------------------------------------------------------

internal fun DayWeightPoint.toLb() = WeightPointLb(
    date = date,
    rawLb = rawKg?.let { it * KG_TO_LB },
    emaLb = emaKg * KG_TO_LB,
)

internal fun buildProjectionUi(wp: WeightProjection?): ProjectionUi {
    if (wp == null) return ProjectionUi.NoGoal
    return ProjectionUi.Ready(
        goalLb = wp.goalKg * KG_TO_LB,
        currentTrendLb = wp.currentTrendKg * KG_TO_LB,
        goalPace = wp.goalPace.toPaceUi(),
        currentPace = wp.currentPace.toPaceUi(),
        expectedPace = wp.expectedPace.toPaceUi(),
        expectedRateLbPerDay = wp.expectedRateKgPerDay * KG_TO_LB,
    )
}

private fun Projection.toPaceUi(): PaceUi = when (this) {
    is Projection.Reachable -> PaceUi.Reachable(
        date = predictedDate,
        rateLbPerDay = rateKgPerDay * KG_TO_LB,
    )
    is Projection.Unreachable -> PaceUi.Unreachable(reason)
}
