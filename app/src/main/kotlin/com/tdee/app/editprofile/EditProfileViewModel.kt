package com.tdee.app.editprofile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.app.onboarding.GoalSelection
import com.tdee.app.onboarding.ftInToCm
import com.tdee.app.onboarding.lbPerWeekToKgPerWeek
import com.tdee.app.onboarding.lbToKg
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Reverse-conversion helpers (canonical → display)
// ---------------------------------------------------------------------------

private fun kgToLb(kg: Double): Double = kg / 0.45359237

/** Returns (ft, inch) from cm. */
private fun cmToFtIn(cm: Double): Pair<Int, Int> {
    val totalInches = (cm / 2.54).roundToInt()
    return totalInches / 12 to totalInches % 12
}

private fun kgPerWeekToLbPerWeek(kgPerWeek: Double): Double = abs(kgPerWeek) / 0.45359237

/** Stored 0..1 fraction → whole-percent display string (e.g. 0.25 → "25", 0.305 → "30.5"). */
private fun fatPctToPercentString(fraction: Double): String {
    val pct = fraction * 100.0
    return if (pct == pct.roundToInt().toDouble()) pct.roundToInt().toString() else pct.toString()
}

// ---------------------------------------------------------------------------
// UI state (mirrors OnboardingFormState minus currentWeightLb)
// ---------------------------------------------------------------------------

data class EditProfileFormState(
    val sex: Sex? = null,
    val birthYear: String = "",
    val heightFt: String = "",
    val heightIn: String = "",
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
    /** True while the initial load from DB is in flight. */
    val loading: Boolean = true,
) {
    val birthYearInt: Int? get() {
        val y = birthYear.toIntOrNull() ?: return null
        return if (y in 1900..2020) y else null
    }

    val heightFtInt: Int? get() = heightFt.toIntOrNull()?.takeIf { it in 0..8 }
    val heightInInt: Int? get() = heightIn.toIntOrNull()?.takeIf { it in 0..11 }

    val goalRateLbPerWeekDouble: Double? get() {
        if (goal == GoalSelection.MAINTAIN) return 0.0
        val v = goalRateLbPerWeek.toDoubleOrNull() ?: return null
        return if (v > 0) v else null
    }

    val goalWeightLbDouble: Double? get() = goalWeightLb.toDoubleOrNull()?.takeIf { it > 0 }

    val proteinGPerKgDouble: Double? get() {
        if (proteinGPerKg.isBlank()) return 2.0
        return proteinGPerKg.toDoubleOrNull()?.takeIf { it > 0 }
    }

    /** Input is a whole percent (0..100); returns the stored 0..1 fraction. */
    val fatPctDouble: Double? get() {
        if (fatPct.isBlank()) return 0.25
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
        if (activityLevel == null) add("Activity level")
        if (goalRateLbPerWeekDouble == null) add("Goal rate")
    }

    val canSave: Boolean get() =
        !loading &&
        missingRequiredFields.isEmpty() &&
        proteinGPerKgDouble != null &&
        fatPctDouble != null
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class EditProfileViewModel(private val repo: TdeeRepository) : ViewModel() {

    private val _form = MutableStateFlow(EditProfileFormState())
    val form: StateFlow<EditProfileFormState> = _form.asStateFlow()

    private val _saved = MutableStateFlow(false)
    /** Flips to true after a successful save. Observe to navigate away. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** Snapshot of the entity as loaded from DB; used to preserve userId/createdAt on save. */
    private var loadedEntity: UserProfileEntity? = null

    init {
        viewModelScope.launch {
            val entity = repo.observeProfile().filterNotNull().first()
            loadedEntity = entity
            _form.value = entity.toFormState()
        }
    }

    // -----------------------------------------------------------------------
    // Form field updaters
    // -----------------------------------------------------------------------

    fun setSex(sex: Sex) = _form.update { it.copy(sex = sex) }
    fun setBirthYear(v: String) = _form.update { it.copy(birthYear = v) }
    fun setHeightFt(v: String) = _form.update { it.copy(heightFt = v) }
    fun setHeightIn(v: String) = _form.update { it.copy(heightIn = v) }
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
        val base = loadedEntity ?: return

        viewModelScope.launch {
            val heightCm = ftInToCm(f.heightFtInt!!, f.heightInInt!!)

            val rawRate = f.goalRateLbPerWeekDouble!!
            val goalRateKgPerWeek = when (f.goal) {
                GoalSelection.MAINTAIN -> 0.0
                GoalSelection.CUT -> -lbPerWeekToKgPerWeek(rawRate)
                GoalSelection.BULK -> lbPerWeekToKgPerWeek(rawRate)
            }

            val goalWeightKg = f.goalWeightLbDouble?.let { lbToKg(it) }

            val updated = base.copy(
                sex = f.sex!!,
                birthYear = f.birthYearInt!!,
                heightCm = heightCm,
                activityLevel = f.activityLevel!!,
                goalRateKgPerWeek = goalRateKgPerWeek,
                goalWeightKg = goalWeightKg,
                proteinGPerKg = f.proteinGPerKgDouble!!,
                fatPctOfCalories = f.fatPctDouble!!,
                // userId, createdAt, dayStartHour, smoothingWindowDays, tdeeWindowDays preserved from base
            )

            repo.updateProfile(updated)
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
                EditProfileViewModel(app.container.repository)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Entity → form state conversion
// ---------------------------------------------------------------------------

private fun UserProfileEntity.toFormState(): EditProfileFormState {
    val (ft, inch) = cmToFtIn(heightCm)

    val goal = when {
        goalRateKgPerWeek > 0.0 -> GoalSelection.BULK
        goalRateKgPerWeek < 0.0 -> GoalSelection.CUT
        else -> GoalSelection.MAINTAIN
    }

    val rateLbPerWeek = when (goal) {
        GoalSelection.MAINTAIN -> "0.5"
        else -> "%.2f".format(kgPerWeekToLbPerWeek(goalRateKgPerWeek))
    }

    val goalWeightLb = goalWeightKg?.let { "%.1f".format(kgToLb(it)) } ?: ""

    return EditProfileFormState(
        sex = sex,
        birthYear = birthYear.toString(),
        heightFt = ft.toString(),
        heightIn = inch.toString(),
        activityLevel = activityLevel,
        goal = goal,
        goalRateLbPerWeek = rateLbPerWeek,
        goalWeightLb = goalWeightLb,
        proteinGPerKg = if (proteinGPerKg == 2.0) "" else proteinGPerKg.toString(),
        // Stored as a 0..1 fraction; display as a whole percent. Default (0.25) shows blank.
        fatPct = if (fatPctOfCalories == 0.25) "" else fatPctToPercentString(fatPctOfCalories),
        loading = false,
    )
}
