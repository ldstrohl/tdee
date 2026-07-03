package com.tdee.domain

/**
 * Pure, stateless weight-pace estimator for goal projection.
 *
 * The naive "current pace" = (EMA_today − EMA_{today−14})/14 has ~zero forward skill: a
 * back-test on a 559-day real log found short-window adherence mean-reverts, so the 14-day
 * rate over-/under-shoots the realized trajectory. Blending it λ:1−λ with a long-run rate
 * (≈90 days) cut the 28-day projection P90 |error| from 7.6 lb to 5.9 lb on that log.
 *
 * All constants below are calibrated on a SINGLE subject's back-test. They are starting points,
 * not universal truths — revisit as more user logs accumulate.
 */
object PaceEstimator {
    /** Lookback (days) for the responsive "recent" pace the caller measures ΔEMA over. */
    const val RECENT_LOOKBACK_DAYS = 14L

    /** Lookback (days) for the stable "long-run" pace the caller measures ΔEMA over. */
    const val LONGRUN_LOOKBACK_DAYS = 90L

    /** Weight on the recent pace in the blend; the long-run pace gets 1−LAMBDA. */
    const val LAMBDA = 0.3

    /**
     * Minimum actual long-run span (days) for the long-run rate to be trusted. Below this the
     * blend degenerates to the recent rate alone (a young log has no meaningful long-run signal).
     */
    const val MIN_LONGRUN_SPAN_DAYS = 28L

    /**
     * P90 half-width growth of a pace projection, in kg per day of horizon. Calibrated on the
     * 559-day back-test: P90 |error| at a +28-day horizon ≈ 5.9 lb ⇒ ≈ 0.095 kg/day
     * (5.9 lb · 0.4536 kg/lb / 28 d). Single subject — revisit with more logs.
     */
    const val CONE_P90_KG_PER_DAY = 0.095

    /**
     * λ-blended expected pace (kg/day). [recent] and [longRun] are ΔEMA/Δdays rates already
     * computed by the caller; [longRunSpanDays] is the actual span the long-run rate covers
     * (may be < [LONGRUN_LOOKBACK_DAYS] when the log is young). Falls back to [recent] alone
     * when [longRunSpanDays] < [MIN_LONGRUN_SPAN_DAYS].
     */
    fun expectedPace(recent: Double, longRun: Double, longRunSpanDays: Long): Double =
        if (longRunSpanDays < MIN_LONGRUN_SPAN_DAYS) recent
        else LAMBDA * recent + (1 - LAMBDA) * longRun

    /**
     * P90 half-width (kg) of an expected-pace projection at [horizonDays]. Linear in horizon —
     * back-test error growth is ~linear because the dominant term is a random-walk in the pace
     * itself (adherence drift), not fixed estimator noise. Zero at a zero horizon.
     */
    fun coneHalfWidthKg(horizonDays: Long): Double =
        CONE_P90_KG_PER_DAY * horizonDays
}
