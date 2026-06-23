package com.tdee.app.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.CheckinProposal
import com.tdee.app.data.TdeeRepository
import com.tdee.domain.Targets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

/**
 * Editable target fields, held as strings so the user can clear/retype freely.
 * Prefilled from [CheckinProposal.proposedTargets] when the proposal loads.
 *
 * A field is valid when it parses to a finite, non-negative number. [canSave] requires all four.
 */
data class CheckinFormState(
    val calorieKcal: String = "",
    val proteinG: String = "",
    val fatG: String = "",
    val carbG: String = "",
) {
    private fun String.parsed(): Double? =
        toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    val calorieValue: Double? get() = calorieKcal.parsed()
    val proteinValue: Double? get() = proteinG.parsed()
    val fatValue: Double? get() = fatG.parsed()
    val carbValue: Double? get() = carbG.parsed()

    /** True only when every field is a valid non-negative number. */
    val canSave: Boolean
        get() = calorieValue != null && proteinValue != null &&
            fatValue != null && carbValue != null

    /** The edited targets, or null when any field is invalid. */
    fun toTargets(): Targets? {
        if (!canSave) return null
        return Targets(
            calorieTargetKcal = calorieValue!!,
            proteinG = proteinValue!!,
            fatG = fatValue!!,
            carbG = carbValue!!,
        )
    }
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * Drives the Module 8 check-in screen.
 *
 * On init it loads [TdeeRepository.proposeCheckin] (current TDEE + 7-day summary + current vs
 * proposed targets) and prefills the editable target fields from `proposedTargets`.
 *
 * This one screen serves BOTH Module-8 paths through the single [TdeeRepository.commitTargets]
 * write:
 *   - **Accept check-in** — the user leaves the prefilled proposed values and taps save.
 *   - **Manual target edit** — the user changes any prefilled number and taps save; the edited
 *     value is what gets committed. There is no in-period immutability; the edit is effective
 *     immediately. The app never calls commit on its own — only this explicit user save does.
 *
 * `tdeeAtCheckinKcal` is always recorded from the proposal's [CheckinProposal.tdeeKcal] (an
 * immutable snapshot of what we believed when targets were set), regardless of whether the user
 * edited the targets.
 */
class CheckinViewModel(private val repo: TdeeRepository) : ViewModel() {

    /** The loaded proposal (summary + current/proposed targets), null while loading. */
    private val _proposal = MutableStateFlow<CheckinProposal?>(null)
    val proposal: StateFlow<CheckinProposal?> = _proposal.asStateFlow()

    /** Editable target fields, prefilled from the proposal's proposed targets. */
    private val _form = MutableStateFlow(CheckinFormState())
    val form: StateFlow<CheckinFormState> = _form.asStateFlow()

    /** Flips to true after a successful commit. Observe to navigate away. */
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            val p = repo.proposeCheckin()
            _proposal.value = p
            _form.value = CheckinFormState(
                calorieKcal = fmt(p.proposedTargets.calorieTargetKcal),
                proteinG = fmt(p.proposedTargets.proteinG),
                fatG = fmt(p.proposedTargets.fatG),
                carbG = fmt(p.proposedTargets.carbG),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Field updaters
    // -----------------------------------------------------------------------

    fun setCalorie(v: String) = _form.update { it.copy(calorieKcal = v) }
    fun setProtein(v: String) = _form.update { it.copy(proteinG = v) }
    fun setFat(v: String) = _form.update { it.copy(fatG = v) }
    fun setCarb(v: String) = _form.update { it.copy(carbG = v) }

    // -----------------------------------------------------------------------
    // Accept / save
    // -----------------------------------------------------------------------

    /**
     * Commits the (possibly edited) targets effective immediately, snapshotting the proposal's
     * TDEE. No-op until the proposal has loaded and all fields are valid.
     */
    fun accept() {
        val p = _proposal.value ?: return
        val targets = _form.value.toTargets() ?: return
        viewModelScope.launch {
            repo.commitTargets(targets, tdeeAtCheckinKcal = p.tdeeKcal)
            _saved.value = true
        }
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                CheckinViewModel(app.container.repository)
            }
        }
    }
}

/** Prefill formatting: whole numbers for calories/macros (targets are derived in whole grams). */
private fun fmt(v: Double): String = v.toInt().toString()
