package com.tdee.app.data

import com.tdee.domain.DailyIntake
import com.tdee.domain.DefaultTdeeEngine
import com.tdee.domain.GoalProjector
import com.tdee.domain.Projection
import com.tdee.domain.TargetCalculator
import com.tdee.domain.Targets
import com.tdee.domain.TdeeEstimate
import com.tdee.domain.TdeeMethod
import com.tdee.domain.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.ZoneId

/**
 * Integration layer between the Room data layer and the domain TDEE engine.
 *
 * All compute functions require that a [UserProfileEntity] exists in the database for the current
 * user (onboarding guarantees this before any of these are called). If no profile is found for
 * [currentUser], they throw [IllegalStateException] with message "No user profile".
 *
 * @param currentUser  provides the id of the active user; today returns a stable local UUID,
 *   later replaced by a real auth-backed impl without changing this class.
 * @param zone  zone used for log-day bucketing; pass [ZoneId.of]("UTC") in tests.
 * @param clock source of "now"; pass [Clock.fixed] in tests for determinism.
 */
class TdeeRepository(
    private val profileDao: UserProfileDao,
    private val weightDao: WeightEntryDao,
    private val foodDao: FoodEntryDao,
    private val trendCacheDao: WeightTrendCacheDao,
    private val currentUser: CurrentUser,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Load all data for the current user and build a [DefaultTdeeEngine]. Caller must already be
     * on a background thread (the suspend DAO calls satisfy this when used with Room's coroutine
     * dispatcher, but we wrap each public function in withContext(IO) to be explicit).
     */
    private suspend fun buildEngine(): Pair<DefaultTdeeEngine, UserProfile> {
        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid)
            ?: throw IllegalStateException("No user profile")
        val profile = profileEntity.toDomain()
        val samples = weightDao.getAll(uid).toWeightSamples()
        val intake = foodDao.getActive(uid).toDailyIntake(zone, profile.dayStartHour)
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
     * weight day through the current log-day (inclusive) for the current user.
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
        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid)
            ?: throw IllegalStateException("No user profile")
        val profile = profileEntity.toDomain()
        val samples = weightDao.getAll(uid).toWeightSamples()
        val intake = foodDao.getActive(uid).toDailyIntake(zone, profile.dayStartHour)

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
                userId = uid,
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

    // -----------------------------------------------------------------------
    // Routing / onboarding helpers
    // -----------------------------------------------------------------------

    /**
     * Reactive profile query for the current user. Emits `null` when no profile exists (→
     * onboarding) and the [UserProfileEntity] once one is saved (→ dashboard). Callers observe
     * this Flow to drive top-level navigation without polling.
     */
    fun observeProfile(): Flow<UserProfileEntity?> =
        profileDao.observeByUserId(currentUser.userId())

    /**
     * Atomically saves [profile] (with `userId` forced to the current user) and inserts a seed
     * weight entry. Call once during onboarding immediately after the user provides their starting
     * weight.
     *
     * The weight entry uses [WeightSource.MANUAL] and is timestamped at [clock]'s current instant.
     */
    suspend fun saveProfileAndSeedWeight(
        profile: UserProfileEntity,
        seedWeightKg: Double,
    ) = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val now = clock.instant()
        val scopedProfile = profile.copy(userId = uid)
        profileDao.upsert(scopedProfile)
        weightDao.insert(
            WeightEntryEntity(
                userId = uid,
                timestamp = now,
                weightKg = seedWeightKg,
                source = WeightSource.MANUAL,
                createdAt = now,
            )
        )
    }

    /**
     * Returns the [DailyIntake] whose log-day equals today, or `null` when:
     * - no profile exists for the current user (no `dayStartHour` available), or
     * - the user has logged no food entries on today's log-day.
     *
     * "Today" is the log-day that contains [clock]'s current instant.
     */
    suspend fun todayConsumed(): DailyIntake? = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid) ?: return@withContext null
        val today = logDay(clock.instant(), zone, profileEntity.dayStartHour)
        foodDao.getActive(uid)
            .toDailyIntake(zone, profileEntity.dayStartHour)
            .firstOrNull { it.date == today }
    }

    // -----------------------------------------------------------------------
    // Food logging
    // -----------------------------------------------------------------------

    /**
     * Inserts a new manual food entry for the current user timestamped at [clock]'s current instant.
     *
     * @param name     display name (also stored as [FoodEntryEntity.rawText]).
     * @param kcal     energy in kcal.
     * @param proteinG protein in grams.
     * @param fatG     fat in grams.
     * @param carbG    carbohydrate in grams.
     * @param grams    serving weight in grams; defaults to 0 when not known.
     */
    suspend fun addFood(
        name: String,
        kcal: Double,
        proteinG: Double,
        fatG: Double,
        carbG: Double,
        grams: Double? = null,
    ) = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val now = clock.instant()
        foodDao.insert(
            FoodEntryEntity(
                userId = uid,
                timestamp = now,
                rawText = name,
                name = name,
                quantity = 1.0,
                unit = "serving",
                grams = grams ?: 0.0,
                kcal = kcal,
                proteinG = proteinG,
                fatG = fatG,
                carbG = carbG,
                sourceDb = FoodSourceDb.MANUAL,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    /**
     * Soft-deletes the food entry with the given [id] by setting its [FoodEntryEntity.deletedAt]
     * to [clock]'s current instant.
     */
    suspend fun softDeleteFood(id: Long) = withContext(Dispatchers.IO) {
        foodDao.softDelete(id, clock.instant())
    }

    /**
     * Returns the current user's non-deleted food entries whose log-day equals today's log-day
     * (computed using the profile's [UserProfileEntity.dayStartHour] and [clock]'s current instant).
     *
     * Returns an empty list when the profile has no entries for today or no profile exists.
     */
    suspend fun todayFoodEntries(): List<FoodEntryEntity> = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid) ?: return@withContext emptyList()
        val dayStart = logDay(clock.instant(), zone, profileEntity.dayStartHour)
        // Log-day window: [dayStart + dayStartHour, dayStart + dayStartHour + 24h)
        val windowStart = dayStart.atStartOfDay(zone).toInstant()
            .plusSeconds(profileEntity.dayStartHour * 3600L)
        val windowEnd = windowStart.plusSeconds(24 * 3600L)
        foodDao.getActiveInRange(uid, windowStart, windowEnd)
    }

    /**
     * Returns the summed macros for today's food entries.
     * All fields are 0.0 when no entries exist for today.
     */
    suspend fun todayConsumedMacros(): ConsumedMacros = withContext(Dispatchers.IO) {
        val entries = todayFoodEntries()
        ConsumedMacros(
            kcal = entries.sumOf { it.kcal },
            proteinG = entries.sumOf { it.proteinG },
            fatG = entries.sumOf { it.fatG },
            carbG = entries.sumOf { it.carbG },
        )
    }

    // -----------------------------------------------------------------------
    // Weight logging
    // -----------------------------------------------------------------------

    /**
     * Inserts a new manual weight entry for the current user, converting [weightLb] to kg
     * (× 0.45359237) and timestamping it at [clock]'s current instant.
     */
    suspend fun addWeight(weightLb: Double) = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val now = clock.instant()
        weightDao.insert(
            WeightEntryEntity(
                userId = uid,
                timestamp = now,
                weightKg = weightLb * 0.45359237,
                source = WeightSource.MANUAL,
                createdAt = now,
            )
        )
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
