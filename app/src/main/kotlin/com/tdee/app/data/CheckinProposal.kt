package com.tdee.app.data

import com.tdee.domain.Targets

/**
 * Everything a check-in screen needs to show the user before they accept new targets.
 *
 * A check-in (weekly prompt OR on-demand) recomputes the empirical TDEE and proposes fresh
 * calorie/macro targets; this is the read-model that drives that screen. Accepting it calls
 * [TdeeRepository.commitTargets] with [proposedTargets] (or user-edited values).
 *
 * @property tdeeKcal current TDEE estimate in kcal (the value the proposal is built from).
 * @property calibrating true while the engine has fewer than a full window of paired data days;
 *   the UI surfaces this so an early, low-confidence proposal reads as provisional.
 * @property last7AvgIntakeKcal mean kcal over COMPLETE intake days in the trailing 7 log-days
 *   (the in-progress current day excluded), or null when there are no complete days in that window.
 * @property trendChangeLb EMA trend-weight change over the last 7 days in lb
 *   (EMA(today) − EMA(today − 7 days)); negative = losing.
 * @property currentTargets the active period's targets, or null when no period exists yet.
 * @property proposedTargets the live [TdeeRepository.proposedTargets] the engine recommends now.
 */
data class CheckinProposal(
    val tdeeKcal: Double,
    val calibrating: Boolean,
    val last7AvgIntakeKcal: Double?,
    val trendChangeLb: Double,
    val currentTargets: Targets?,
    val proposedTargets: Targets,
)

/** Map a persisted target period to the domain [Targets] the dashboard/check-in consume. */
fun TargetPeriodEntity.toTargets(): Targets = Targets(
    calorieTargetKcal = calorieTarget,
    proteinG = proteinTargetG,
    fatG = fatTargetG,
    carbG = carbTargetG,
)
