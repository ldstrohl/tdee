package com.tdee.app.data

import com.tdee.domain.DefaultTdeeEngine
import com.tdee.domain.GoalProjector
import com.tdee.domain.Projection
import com.tdee.domain.TargetCalculator
import com.tdee.domain.Targets
import com.tdee.domain.TdeeEstimate
import com.tdee.domain.TdeeMethod
import com.tdee.domain.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.ZoneId

/**
 * Integration layer between the Room data layer and the domain TDEE engine.
 *
 * All compute functions require that a [UserProfileEntity] exists in the database
 * (onboarding guarantees this before any of these are called). If no profile is
 * found, they throw [IllegalStateException] with message "No user profile".
 *
 * @param zone  zone used for log-day bucketing; pass [ZoneId.of]("UTC") in tests.
 * @param clock source of "now"; pass [Clock.fixed] in tests for determinism.
 */
class TdeeRepository(
    private val profileDao: UserProfileDao,
    private val weightDao: WeightEntryDao,
    private val foodDao: FoodEntryDao,
    private val trendCacheDao: WeightTrendCacheDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Load all data and build a [DefaultTdeeEngine]. Caller must already be on a
     * background thread (the suspend DAO calls satisfy this when used with Room's
     * coroutine dispatcher, but we wrap each public function in withContext(IO)
     * to be explicit).
     */
    private suspend fun buildEngine(): Pair<DefaultTdeeEngine, UserProfile> {
        val profileEntity = profileDao.get()
            ?: throw IllegalStateException("No user profile")
        val profile = profileEntity.toDomain()
        val samples = weightDao.getAll().toWeightSamples()
        val intake = foodDao.getActive().toDailyIntake(zone, profile.dayStartHour)
        return DefaultTdeeEngine(samples, intake, profile, zone) to profile
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Returns the current TDEE estimate evaluated at [clock]'s current instant. */
    suspend fun currentEstimate(): TdeeEstimate = withContext(Dispatchers.IO) {
        val (engine, _) = buildEngine()
        engine.estimateAt(clock.instant())
    }

    /** Returns the current EMA trend weight in kg evaluated at [clock]'s current instant. */
    suspend fun currentTrendKg(): Double = withContext(Dispatchers.IO) {
        val (engine, _) = buildEngine()
        engine.weightTrendAt(clock.instant())
    }

    /**
     * Derives daily calorie/macro targets from the current estimate and profile goal.
     * Builds the engine once internally to avoid redundant DB reads.
     */
    suspend fun proposedTargets(): Targets = withContext(Dispatchers.IO) {
        val (engine, profile) = buildEngine()
        val now = clock.instant()
        val estimate = engine.estimateAt(now)
        val trendKg = engine.weightTrendAt(now)
        TargetCalculator.targets(estimate, trendKg, profile)
    }

    /**
     * Projects when [goalKg] is reached at [scenarioIntakeKcal] daily intake.
     *
     * @param scenarioIntakeKcal hypothetical daily intake in kcal (caller converts lb→kg before
     *   passing goalKg if needed — this function expects SI units throughout).
     * @param goalKg target body weight in kg.
     */
    suspend fun project(scenarioIntakeKcal: Double, goalKg: Double): Projection =
        withContext(Dispatchers.IO) {
            val (engine, profile) = buildEngine()
            val now = clock.instant()
            val estimate = engine.estimateAt(now)
            val trendKg = engine.weightTrendAt(now)
            GoalProjector.project(scenarioIntakeKcal, estimate, trendKg, goalKg, now, zone, profile)
        }

    /**
     * Rebuilds [WeightTrendCacheEntity] rows for every log-day from the first aggregated
     * weight day through the current log-day (inclusive).
     *
     * Each row stores the EMA trend weight and TDEE estimate that the engine produces
     * when queried at an instant that maps to that log-day (specifically: midnight of
     * that date in [zone] shifted forward by [UserProfile.dayStartHour] hours, which
     * lands exactly at the start of the log-day boundary).
     *
     * Note: this is O(N²) — the engine rebuilds its full EMA series for each day.
     * That cost is intentional for MVP; the cache exists precisely to amortize it for
     * display consumers.
     *
     * No-op if there are no weight samples.
     */
    suspend fun recomputeTrendCache() = withContext(Dispatchers.IO) {
        val profileEntity = profileDao.get()
            ?: throw IllegalStateException("No user profile")
        val profile = profileEntity.toDomain()
        val samples = weightDao.getAll().toWeightSamples()
        val intake = foodDao.getActive().toDailyIntake(zone, profile.dayStartHour)

        if (samples.isEmpty()) return@withContext

        val engine = DefaultTdeeEngine(samples, intake, profile, zone)

        // Determine iteration range: first measured day through current log-day.
        val firstDay = samples.minOf { it.t }
            .minusSeconds(profile.dayStartHour.toLong() * 3600)
            .atZone(zone)
            .toLocalDate()
        val currentDay = clock.instant()
            .minusSeconds(profile.dayStartHour.toLong() * 3600)
            .atZone(zone)
            .toLocalDate()

        var day = firstDay
        while (!day.isAfter(currentDay)) {
            // Convert the log-day back to an Instant: the start of the log-day boundary
            // is midnight of `day` in `zone` plus dayStartHour hours.
            val dayInstant = day.atStartOfDay(zone).toInstant()
                .plusSeconds(profile.dayStartHour.toLong() * 3600)

            val estimate = engine.estimateAt(dayInstant)
            val emaKg = engine.weightTrendAt(dayInstant)

            val cacheRow = WeightTrendCacheEntity(
                date = day,
                emaKg = emaKg,
                tdeeEstimate = estimate.valueKcal,
                tdeeMethod = estimate.method.toDb(),
                uncertaintyKcal = estimate.uncertaintyKcal,
                calibrating = estimate.calibrating,
            )
            trendCacheDao.upsert(cacheRow)
            day = day.plusDays(1)
        }
    }
}

// ---------------------------------------------------------------------------
// Domain → DB enum mapping
// ---------------------------------------------------------------------------

private fun TdeeMethod.toDb(): TdeeMethodDb = when (this) {
    TdeeMethod.FORMULA -> TdeeMethodDb.FORMULA
    TdeeMethod.BLEND -> TdeeMethodDb.BLEND
    TdeeMethod.EMPIRICAL -> TdeeMethodDb.EMPIRICAL
}
