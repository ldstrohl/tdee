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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

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
     * Reactive stream of today's non-deleted food entries for the current user.
     *
     * Room re-emits the list automatically whenever any food_entry row changes (insert or
     * soft-delete), so collectors on the dashboard stay up-to-date without polling or
     * re-navigation.
     *
     * The today window ([windowStart, windowEnd)) is computed once when this Flow is collected,
     * using the profile's [UserProfileEntity.dayStartHour] and [clock]'s current instant. This
     * is acceptable for MVP — no mid-session midnight-rollover handling is performed.
     *
     * Emits an empty list when no profile exists for the current user.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeTodayFoodEntries(): Flow<List<FoodEntryEntity>> {
        val uid = currentUser.userId()
        return profileDao.observeByUserId(uid).flatMapLatest { profileEntity ->
            if (profileEntity == null) {
                emptyFlow()
            } else {
                val dayStart = logDay(clock.instant(), zone, profileEntity.dayStartHour)
                val windowStart = dayStart.atStartOfDay(zone).toInstant()
                    .plusSeconds(profileEntity.dayStartHour * 3600L)
                val windowEnd = windowStart.plusSeconds(24 * 3600L)
                foodDao.observeActiveInRange(uid, windowStart, windowEnd)
            }
        }
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

    // -----------------------------------------------------------------------
    // Chart data methods
    // -----------------------------------------------------------------------

    /**
     * Returns one [DayWeightPoint] per log-day from the first weight measurement through today.
     *
     * [DayWeightPoint.rawKg] is the first-of-day (earliest timestamp) raw weight for that day,
     * or null if no measurement was recorded on that day.
     * [DayWeightPoint.emaKg] is the engine EMA at that day — always present.
     *
     * Note: builds a [DefaultTdeeEngine] and queries it once per day — O(N²) in the number of
     * days. Acceptable for MVP; cache via [recomputeTrendCache] amortizes this for display use.
     *
     * Returns an empty list when no weight samples exist.
     */
    suspend fun weightSeries(): List<DayWeightPoint> = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid)
            ?: throw IllegalStateException("No user profile")
        val profile = profileEntity.toDomain()
        val weightEntities = weightDao.getAll(uid)
        if (weightEntities.isEmpty()) return@withContext emptyList()

        val samples = weightEntities.toWeightSamples()
        val intake = foodDao.getActive(uid).toDailyIntake(zone, profile.dayStartHour)
        val engine = DefaultTdeeEngine(samples, intake, profile, zone)

        // Build first-of-day raw weight map using same bucketing rule as the engine:
        // group by log-day, pick the entry with the earliest timestamp within each day.
        val rawByDay: Map<LocalDate, Double> = weightEntities
            .groupBy { logDay(it.timestamp, zone, profile.dayStartHour) }
            .mapValues { (_, entries) -> entries.minBy { it.timestamp }.weightKg }

        val firstDay = rawByDay.keys.min()
        val today = logDay(clock.instant(), zone, profile.dayStartHour)

        val result = mutableListOf<DayWeightPoint>()
        var day = firstDay
        while (!day.isAfter(today)) {
            val dayInstant = day.atStartOfDay(zone).toInstant()
                .plusSeconds(profile.dayStartHour.toLong() * 3600)
            val emaKg = engine.weightTrendAt(dayInstant)
            result.add(DayWeightPoint(date = day, rawKg = rawByDay[day], emaKg = emaKg))
            day = day.plusDays(1)
        }
        result
    }

    /**
     * Returns one [DayExpenditurePoint] per log-day from the first weight measurement through today.
     *
     * [DayExpenditurePoint.intakeKcal] is the logged kcal for that day if the day had at least one
     * food entry (complete=true in the engine's DailyIntake), or null otherwise. Never zero-filled.
     * [DayExpenditurePoint.tdeeKcal] and [calibrating] come from [DefaultTdeeEngine.estimateAt].
     *
     * Returns an empty list when no weight samples exist.
     */
    suspend fun expenditureSeries(): List<DayExpenditurePoint> = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid)
            ?: throw IllegalStateException("No user profile")
        val profile = profileEntity.toDomain()
        val weightEntities = weightDao.getAll(uid)
        if (weightEntities.isEmpty()) return@withContext emptyList()

        val samples = weightEntities.toWeightSamples()
        val intakeList = foodDao.getActive(uid).toDailyIntake(zone, profile.dayStartHour)
        val engine = DefaultTdeeEngine(samples, intakeList, profile, zone)

        val intakeByDay: Map<LocalDate, DailyIntake> = intakeList.associateBy { it.date }
        val firstDay = samples.minOf { logDay(it.t, zone, profile.dayStartHour) }
        val today = logDay(clock.instant(), zone, profile.dayStartHour)

        val result = mutableListOf<DayExpenditurePoint>()
        var day = firstDay
        while (!day.isAfter(today)) {
            val dayInstant = day.atStartOfDay(zone).toInstant()
                .plusSeconds(profile.dayStartHour.toLong() * 3600)
            val estimate = engine.estimateAt(dayInstant)
            val intake = intakeByDay[day]
            result.add(
                DayExpenditurePoint(
                    date = day,
                    intakeKcal = if (intake?.complete == true) intake.kcal else null,
                    tdeeKcal = estimate.valueKcal,
                    calibrating = estimate.calibrating,
                )
            )
            day = day.plusDays(1)
        }
        result
    }

    /**
     * Returns macro and calorie summary for the given [window].
     *
     * For [ChartWindow.TODAY]: returns today's running totals.
     * For all other windows: returns per-day averages over complete logging days only within the
     * window. [MacroSummary.completeDays] = days with at least one entry; [MacroSummary.totalDays]
     * = calendar days in the window.
     *
     * If no complete days exist in the window (non-TODAY), all macro/calorie fields are 0.0
     * and [MacroSummary.completeDays] = 0.
     */
    suspend fun macroSummary(window: ChartWindow): MacroSummary = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid)
            ?: throw IllegalStateException("No user profile")
        val profile = profileEntity.toDomain()
        val targets = proposedTargets()

        if (window == ChartWindow.TODAY) {
            val macros = todayConsumedMacros()
            return@withContext MacroSummary(
                proteinG = macros.proteinG,
                fatG = macros.fatG,
                carbG = macros.carbG,
                kcal = macros.kcal,
                completeDays = 1,
                totalDays = 1,
                targets = targets,
            )
        }

        val today = logDay(clock.instant(), zone, profile.dayStartHour)
        val windowStart: LocalDate = when (window) {
            ChartWindow.M1  -> today.minusMonths(1)
            ChartWindow.M3  -> today.minusMonths(3)
            ChartWindow.M6  -> today.minusMonths(6)
            ChartWindow.Y1  -> today.minusYears(1)
            ChartWindow.ALL -> {
                val allEntries = foodDao.getActive(uid)
                if (allEntries.isEmpty()) today
                else allEntries.minOf { logDay(it.timestamp, zone, profile.dayStartHour) }
            }
            ChartWindow.TODAY -> today // unreachable; handled above
        }

        val totalDays = (windowStart.until(today, java.time.temporal.ChronoUnit.DAYS) + 1).toInt()

        val intakeList = foodDao.getActive(uid).toDailyIntake(zone, profile.dayStartHour)
        val completeDaysInWindow = intakeList.filter { di ->
            di.complete && !di.date.isBefore(windowStart) && !di.date.isAfter(today)
        }

        if (completeDaysInWindow.isEmpty()) {
            return@withContext MacroSummary(
                proteinG = 0.0,
                fatG = 0.0,
                carbG = 0.0,
                kcal = 0.0,
                completeDays = 0,
                totalDays = totalDays,
                targets = targets,
            )
        }

        // Per-day averages over complete days only require the food entries for those days.
        val foodEntries = foodDao.getActive(uid).filter { it.deletedAt == null }
        val entriesByDay = foodEntries.groupBy { logDay(it.timestamp, zone, profile.dayStartHour) }
        val windowDates = completeDaysInWindow.map { it.date }.toSet()

        var totalProteinG = 0.0
        var totalFatG = 0.0
        var totalCarbG = 0.0
        var totalKcal = 0.0

        for (date in windowDates) {
            val dayEntries = entriesByDay[date] ?: continue
            totalProteinG += dayEntries.sumOf { it.proteinG }
            totalFatG += dayEntries.sumOf { it.fatG }
            totalCarbG += dayEntries.sumOf { it.carbG }
            totalKcal += dayEntries.sumOf { it.kcal }
        }

        val n = windowDates.size.toDouble()
        MacroSummary(
            proteinG = totalProteinG / n,
            fatG = totalFatG / n,
            carbG = totalCarbG / n,
            kcal = totalKcal / n,
            completeDays = windowDates.size,
            totalDays = totalDays,
            targets = targets,
        )
    }

    /**
     * Returns a [WeightProjection] when the user has set a goal weight, or null otherwise.
     *
     * [WeightProjection.goalPace] projects arrival using the profile's [UserProfile.goalRateKgPerWeek].
     * [WeightProjection.currentPace] projects arrival using the recent EMA slope:
     *   rate = (EMA_today − EMA_(today − tdeeWindowDays)) / tdeeWindowDays kg/day.
     *
     * A flat or adverse current pace produces [Projection.Unreachable] for [currentPace].
     */
    suspend fun weightProjection(): WeightProjection? = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid)
            ?: throw IllegalStateException("No user profile")
        val profile = profileEntity.toDomain()
        val goalKg = profile.goalWeightKg ?: return@withContext null

        val samples = weightDao.getAll(uid).toWeightSamples()
        val intakeList = foodDao.getActive(uid).toDailyIntake(zone, profile.dayStartHour)
        val engine = DefaultTdeeEngine(samples, intakeList, profile, zone)

        val now = clock.instant()
        val currentTrendKg = engine.weightTrendAt(now)

        // Goal pace: use the profile's weekly rate converted to daily.
        val goalRateKgPerDay = profile.goalRateKgPerWeek / 7.0
        val goalPace = GoalProjector.projectAtRate(
            trendNowKg = currentTrendKg,
            goalKg = goalKg,
            rateKgPerDay = goalRateKgPerDay,
            asOf = now,
            zone = zone,
        )

        // Current pace: slope of EMA over the last tdeeWindowDays.
        val today = logDay(now, zone, profile.dayStartHour)
        val windowStart = today.minusDays(profile.tdeeWindowDays.toLong())
        val startInstant = windowStart.atStartOfDay(zone).toInstant()
            .plusSeconds(profile.dayStartHour.toLong() * 3600)
        val emaToday = engine.weightTrendAt(now)
        val emaStart = engine.weightTrendAt(startInstant)
        val currentRateKgPerDay = (emaToday - emaStart) / profile.tdeeWindowDays.toDouble()

        val currentPace = GoalProjector.projectAtRate(
            trendNowKg = currentTrendKg,
            goalKg = goalKg,
            rateKgPerDay = currentRateKgPerDay,
            asOf = now,
            zone = zone,
        )

        WeightProjection(
            currentTrendKg = currentTrendKg,
            goalKg = goalKg,
            goalPace = goalPace,
            currentPace = currentPace,
        )
    }

    // -----------------------------------------------------------------------
    // Dev / sample-data seeder (for QA and chart development only)
    // -----------------------------------------------------------------------

    /**
     * Inserts approximately 60 days of backdated sample data for the current user.
     * Intended for development and QA use only — call only when BuildConfig.DEBUG is true
     * at the call site. This method itself has no debug guard so it can be unit-tested.
     *
     * Seeded data:
     *   - 60 daily weight entries: a gentle downward trend starting ~90 kg with ±0.5 kg noise.
     *   - Food entries on ~80 % of days (random skip, reproducible via fixed seed), each with
     *     three meals totalling ~2200 kcal/day with a small random variation.
     *
     * Timestamps are backdated relative to [clock]'s current instant; day 0 = today,
     * day 59 = 59 days ago. Weights are recorded at 07:00 local (zone), meals at 08:00,
     * 13:00, and 19:00.
     *
     * Existing data is NOT cleared before seeding — call on a fresh user or wipe manually.
     */
    suspend fun seedSampleData() = withContext(Dispatchers.IO) {
        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid)
            ?: throw IllegalStateException("No user profile")
        val profile = profileEntity.toDomain()

        val rng = Random(seed = 42L)
        val today = logDay(clock.instant(), zone, profile.dayStartHour)
        val totalDays = 60
        val startWeightKg = 90.0
        val dailyTrendKg = -0.05 // ~0.35 kg/week loss

        for (dayOffset in (totalDays - 1) downTo 0) {
            val date = today.minusDays(dayOffset.toLong())
            val trendWeight = startWeightKg + dailyTrendKg * (totalDays - 1 - dayOffset)
            val noise = (rng.nextDouble() - 0.5) * 1.0 // ±0.5 kg
            val weightKg = trendWeight + noise

            val weightTs = date.atStartOfDay(zone).toInstant().plusSeconds(7 * 3600L)
            weightDao.insert(
                WeightEntryEntity(
                    userId = uid,
                    timestamp = weightTs,
                    weightKg = weightKg,
                    source = WeightSource.MANUAL,
                    createdAt = weightTs,
                )
            )

            // Skip food on ~20 % of days to create incomplete-day gaps for testing.
            if (rng.nextDouble() < 0.80) {
                val baseKcal = 2200.0 + (rng.nextDouble() - 0.5) * 200.0
                val mealKcals = listOf(baseKcal * 0.25, baseKcal * 0.40, baseKcal * 0.35)
                val mealHours = listOf(8L, 13L, 19L)

                for ((kcal, hour) in mealKcals.zip(mealHours)) {
                    val mealTs = date.atStartOfDay(zone).toInstant().plusSeconds(hour * 3600L)
                    foodDao.insert(
                        FoodEntryEntity(
                            userId = uid,
                            timestamp = mealTs,
                            rawText = "sample meal",
                            name = "Sample Meal",
                            quantity = 1.0,
                            unit = "serving",
                            grams = 300.0,
                            kcal = kcal,
                            proteinG = kcal * 0.20 / 4.0,  // 20 % protein
                            fatG = kcal * 0.30 / 9.0,      // 30 % fat
                            carbG = kcal * 0.50 / 4.0,     // 50 % carbs
                            sourceDb = FoodSourceDb.MANUAL,
                            createdAt = mealTs,
                            updatedAt = mealTs,
                        )
                    )
                }
            }
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
