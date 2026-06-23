package com.tdee.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

// ---------------------------------------------------------------------------
// Units helpers (all conversions live here — nowhere else)
// ---------------------------------------------------------------------------

internal fun lbToKg(lb: Double): Double = lb * 0.45359237
internal fun ftInToCm(ft: Int, inch: Int): Double = (ft * 12 + inch) * 2.54
internal fun lbPerWeekToKgPerWeek(lbPerWeek: Double): Double = lbPerWeek * 0.45359237

// ---------------------------------------------------------------------------
// Goal selection
// ---------------------------------------------------------------------------

enum class GoalSelection { MAINTAIN, CUT, BULK }

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

data class OnboardingFormState(
    val sex: Sex? = null,
    val birthYear: String = "",
    val heightFt: String = "",
    val heightIn: String = "",
    val currentWeightLb: String = "",
    val activityLevel: ActivityLevel? = null,
    val goal: GoalSelection = GoalSelection.MAINTAIN,
    /** lb/week; only used when goal is CUT or BULK */
    val goalRateLbPerWeek: String = "0.5",
    /** Optional; lb */
    val goalWeightLb: String = "",
    /** Optional override; g/kg. Empty = use default 2.0 */
    val proteinGPerKg: String = "",
    /** Optional override; whole percent 0..100. Empty = use default 25%. Stored as 0..1 fraction. */
    val fatPct: String = "",
) {
    // -----------------------------------------------------------------------
    // Derived validation
    // -----------------------------------------------------------------------

    val birthYearInt: Int? get() {
        val y = birthYear.toIntOrNull() ?: return null
        return if (y in 1900..2020) y else null
    }

    val heightFtInt: Int? get() = heightFt.toIntOrNull()?.takeIf { it in 0..8 }
    val heightInInt: Int? get() = heightIn.toIntOrNull()?.takeIf { it in 0..11 }
    val currentWeightLbDouble: Double? get() = currentWeightLb.toDoubleOrNull()?.takeIf { it > 0 }

    val goalRateLbPerWeekDouble: Double? get() {
        if (goal == GoalSelection.MAINTAIN) return 0.0
        val v = goalRateLbPerWeek.toDoubleOrNull() ?: return null
        return if (v > 0) v else null
    }

    val goalWeightLbDouble: Double? get() = goalWeightLb.toDoubleOrNull()?.takeIf { it > 0 }

    val proteinGPerKgDouble: Double? get() {
        if (proteinGPerKg.isBlank()) return 2.0 // default
        return proteinGPerKg.toDoubleOrNull()?.takeIf { it > 0 }
    }

    /** Input is a whole percent (0..100); returns the stored 0..1 fraction. */
    val fatPctDouble: Double? get() {
        if (fatPct.isBlank()) return 0.25 // default
        return fatPct.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { it / 100.0 }
    }

    /**
     * Human-readable names of required fields that are not yet validly filled.
     * Empty when the form is complete. Drives the "Still needed: …" helper line.
     */
    val missingRequiredFields: List<String> get() = buildList {
        if (sex == null) add("Biological sex")
        if (birthYearInt == null) add("Birth year")
        if (heightFtInt == null || heightInInt == null) add("Height")
        if (currentWeightLbDouble == null) add("Current weight")
        if (activityLevel == null) add("Activity level")
        if (goalRateLbPerWeekDouble == null) add("Goal rate")
    }

    val canSave: Boolean get() =
        missingRequiredFields.isEmpty() &&
        proteinGPerKgDouble != null &&
        fatPctDouble != null
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class OnboardingViewModel(private val repo: TdeeRepository) : ViewModel() {

    private val _form = MutableStateFlow(OnboardingFormState())
    val form: StateFlow<OnboardingFormState> = _form.asStateFlow()

    private val _saved = MutableStateFlow(false)
    /** Flips to true after a successful save. Observe this to navigate away. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    // -----------------------------------------------------------------------
    // Form field updaters
    // -----------------------------------------------------------------------

    fun setSex(sex: Sex) = _form.update { it.copy(sex = sex) }
    fun setBirthYear(v: String) = _form.update { it.copy(birthYear = v) }
    fun setHeightFt(v: String) = _form.update { it.copy(heightFt = v) }
    fun setHeightIn(v: String) = _form.update { it.copy(heightIn = v) }
    fun setCurrentWeightLb(v: String) = _form.update { it.copy(currentWeightLb = v) }
    fun setActivityLevel(level: ActivityLevel) = _form.update { it.copy(activityLevel = level) }
    fun setGoal(goal: GoalSelection) = _form.update { it.copy(goal = goal) }
    fun setGoalRateLbPerWeek(v: String) = _form.update { it.copy(goalRateLbPerWeek = v) }
    fun setGoalWeightLb(v: String) = _form.update { it.copy(goalWeightLb = v) }
    fun setProteinGPerKg(v: String) = _form.update { it.copy(proteinGPerKg = v) }
    fun setFatPct(v: String) = _form.update { it.copy(fatPct = v) }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    fun save() {
        val f = _form.value
        if (!f.canSave) return

        viewModelScope.launch {
            val now = Instant.now()

            val currentWeightKg = lbToKg(f.currentWeightLbDouble!!)
            val heightCm = ftInToCm(f.heightFtInt!!, f.heightInInt!!)

            val rawRate = f.goalRateLbPerWeekDouble!!
            val goalRateKgPerWeek = when (f.goal) {
                GoalSelection.MAINTAIN -> 0.0
                GoalSelection.CUT -> -lbPerWeekToKgPerWeek(rawRate)
                GoalSelection.BULK -> lbPerWeekToKgPerWeek(rawRate)
            }

            val goalWeightKg = f.goalWeightLbDouble?.let { lbToKg(it) }

            val profile = UserProfileEntity(
                userId = "placeholder", // overwritten by saveProfileAndSeedWeight
                sex = f.sex!!,
                birthYear = f.birthYearInt!!,
                heightCm = heightCm,
                activityLevel = f.activityLevel!!,
                goalRateKgPerWeek = goalRateKgPerWeek,
                goalWeightKg = goalWeightKg,
                proteinGPerKg = f.proteinGPerKgDouble!!,
                fatPctOfCalories = f.fatPctDouble!!,
                dayStartHour = 0,
                smoothingWindowDays = 14,
                tdeeWindowDays = 14,
                createdAt = now,
                updatedAt = now,
            )

            repo.saveProfileAndSeedWeight(profile, seedWeightKg = currentWeightKg)
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
                OnboardingViewModel(app.container.repository)
            }
        }
    }
}
