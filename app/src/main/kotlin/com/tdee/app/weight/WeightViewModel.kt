package com.tdee.app.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.HealthConnectSource
import com.tdee.app.data.HealthConnectSyncManager
import com.tdee.app.data.TdeeRepository
import com.tdee.app.insights.KG_TO_LB
import com.tdee.app.insights.WeightPointLb
import com.tdee.app.insights.WeightRange
import com.tdee.app.insights.toLb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Health Connect state for the weight screen's sync affordance. */
enum class HcAvailability { UNKNOWN, CONNECTED, NEEDS_SETUP, UNAVAILABLE }

data class WeightUiState(
    val allPoints: List<WeightPointLb> = emptyList(),
    val visiblePoints: List<WeightPointLb> = emptyList(),
    val selectedRange: WeightRange = WeightRange.M3,
    /** Goal weight in lb for the dashed goal line, or null when no goal is set. */
    val goalLb: Double? = null,
    val isLoading: Boolean = true,
    val hc: HcAvailability = HcAvailability.UNKNOWN,
    val syncing: Boolean = false,
    /** Last sync result message, shown until the next sync. */
    val syncStatus: String? = null,
)

/**
 * Drives the Weight hub: the weight trend chart, a Health Connect "sync now" affordance, and
 * (via navigation) manual entry. The trend chart reuses the Insights `WeightTrendChart` composable
 * and the shared lb-conversion helpers; only the goal line is drawn here (no prediction overlay —
 * that lives in Insights).
 */
class WeightViewModel(
    private val repo: TdeeRepository,
    private val syncManager: HealthConnectSyncManager,
    private val source: HealthConnectSource,
) : ViewModel() {

    private val _state = MutableStateFlow(WeightUiState())
    val state: StateFlow<WeightUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun setRange(range: WeightRange) {
        val cur = _state.value
        _state.value = cur.copy(selectedRange = range, visiblePoints = slice(cur.allPoints, range))
    }

    /** Re-fetch the chart + HC state (e.g. on screen resume after manual entry or HC changes). */
    fun reload() = load()

    /** Pull the latest weigh-ins from Health Connect, then refresh the chart on success. */
    fun syncFromHealthConnect() {
        if (_state.value.syncing) return
        _state.value = _state.value.copy(syncing = true, syncStatus = null)
        viewModelScope.launch {
            val imported = runCatching { syncManager.sync(fullHistory = false) }.getOrNull()
            val status = when {
                imported == null -> "Couldn't sync. Try again."
                imported == 0 -> "Up to date — no new weigh-ins."
                imported == 1 -> "Imported 1 weigh-in."
                else -> "Imported $imported weigh-ins."
            }
            _state.value = _state.value.copy(syncing = false, syncStatus = status)
            if (imported != null && imported > 0) load(preserveSyncStatus = status)
        }
    }

    private fun load(preserveSyncStatus: String? = null) {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val points = runCatching { repo.weightSeries().map { it.toLb() } }.getOrDefault(emptyList())
            val goalLb = runCatching { repo.weightProjection()?.goalKg?.times(KG_TO_LB) }.getOrNull()
            val hc = runCatching {
                when {
                    !source.isAvailable() -> HcAvailability.UNAVAILABLE
                    source.hasReadPermission() -> HcAvailability.CONNECTED
                    else -> HcAvailability.NEEDS_SETUP
                }
            }.getOrDefault(HcAvailability.UNAVAILABLE)
            val range = _state.value.selectedRange
            _state.value = _state.value.copy(
                allPoints = points,
                visiblePoints = slice(points, range),
                goalLb = goalLb,
                isLoading = false,
                hc = hc,
                syncStatus = preserveSyncStatus ?: _state.value.syncStatus,
            )
        }
    }

    private fun slice(all: List<WeightPointLb>, range: WeightRange): List<WeightPointLb> {
        if (all.isEmpty()) return all
        val days = range.days ?: return all
        val cutoff = all.last().date.minusDays(days.toLong())
        return all.filter { !it.date.isBefore(cutoff) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                WeightViewModel(
                    repo = app.container.repository,
                    syncManager = app.container.healthConnectSyncManager,
                    source = app.container.healthConnectSource,
                )
            }
        }
    }
}
