package com.tdee.app.data

import com.tdee.domain.DailyIntake
import com.tdee.domain.DefaultTdeeEngine
import com.tdee.domain.GoalProjector
import com.tdee.domain.PaceEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the per-day chart read-models and the goal projection from a freshly built
 * [DefaultTdeeEngine]. Extracted verbatim from [TdeeRepository]; the repository owns the public
 * [TdeeRepository.weightSeries], [TdeeRepository.expenditureSeries], and
 * [TdeeRepository.weightProjection] seams and delegates here.
 *
 * @param logDayStart shared log-day-start helper owned by [TdeeRepository]
 *   (midnight of `date` in [zone] shifted forward by `dayStartHour` hours).
 */
internal class ChartSeriesBuilder(
    private val profileDao: UserProfileDao,
    private val weightDao: WeightEntryDao,
    private val foodDao: FoodEntryDao,
    private val currentUser: CurrentUser,
    private val zone: ZoneId,
    private val clock: Clock,
    private val logDayStart: (LocalDate, Int) -> Instant,
) {

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
            val dayInstant = logDayStart(day, profile.dayStartHour)
            val emaKg = engine.weightTrendAt(dayInstant)
            result.add(DayWeightPoint(date = day, rawKg = rawByDay[day], emaKg = emaKg))
            day = day.plusDays(1)
        }
        result
    }

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
            val dayInstant = logDayStart(day, profile.dayStartHour)
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

        // Current pace: slope of the EMA trend over a short recent lookback so it reflects the
        // latest trend, not the 180-day empirical TDEE averaging window.
        val paceLookbackDays = 14L
        val today = logDay(now, zone, profile.dayStartHour)
        val windowStart = today.minusDays(paceLookbackDays)
        val startInstant = logDayStart(windowStart, profile.dayStartHour)
        val emaToday = currentTrendKg
        val emaStart = engine.weightTrendAt(startInstant)
        val currentRateKgPerDay = (emaToday - emaStart) / paceLookbackDays.toDouble()

        val currentPace = GoalProjector.projectAtRate(
            trendNowKg = currentTrendKg,
            goalKg = goalKg,
            rateKgPerDay = currentRateKgPerDay,
            asOf = now,
            zone = zone,
        )

        // Expected pace: λ-blend of the responsive recent rate (above) and a stable long-run rate
        // measured over ~90 days (or the whole log if younger), per PaceEstimator. The long-run
        // window is anchored to the first weigh-in day, and its actual span is passed to
        // expectedPace() so a young log (< MIN_LONGRUN_SPAN_DAYS) degenerates to the recent rate.
        val firstWeighInDay = samples.minOfOrNull { logDay(it.t, zone, profile.dayStartHour) }
        val longRunSpan = if (firstWeighInDay == null) 0L
            else minOf(
                PaceEstimator.LONGRUN_LOOKBACK_DAYS,
                java.time.temporal.ChronoUnit.DAYS.between(firstWeighInDay, today),
            )
        val longRunRateKgPerDay = if (longRunSpan >= 1L) {
            val lrStart = today.minusDays(longRunSpan)
            val lrStartInstant = logDayStart(lrStart, profile.dayStartHour)
            (emaToday - engine.weightTrendAt(lrStartInstant)) / longRunSpan.toDouble()
        } else currentRateKgPerDay
        val expectedRateKgPerDay = PaceEstimator.expectedPace(
            recent = currentRateKgPerDay,
            longRun = longRunRateKgPerDay,
            longRunSpanDays = longRunSpan,
        )
        val expectedPace = GoalProjector.projectAtRate(
            trendNowKg = currentTrendKg,
            goalKg = goalKg,
            rateKgPerDay = expectedRateKgPerDay,
            asOf = now,
            zone = zone,
        )

        WeightProjection(
            currentTrendKg = currentTrendKg,
            goalKg = goalKg,
            goalPace = goalPace,
            currentPace = currentPace,
            expectedPace = expectedPace,
            expectedRateKgPerDay = expectedRateKgPerDay,
        )
    }
}
