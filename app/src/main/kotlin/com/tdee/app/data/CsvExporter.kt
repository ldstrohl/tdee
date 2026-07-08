package com.tdee.app.data

import com.tdee.domain.DefaultTdeeEngine
import com.tdee.domain.kgToLb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the CSV data dump for the current user. Extracted verbatim from [TdeeRepository]; the
 * repository owns the single public [TdeeRepository.exportCsv] seam and delegates here.
 *
 * @param logDayStart shared log-day-start helper owned by [TdeeRepository]
 *   (midnight of `date` in [zone] shifted forward by `dayStartHour` hours).
 */
internal class CsvExporter(
    private val profileDao: UserProfileDao,
    private val weightDao: WeightEntryDao,
    private val foodDao: FoodEntryDao,
    private val currentUser: CurrentUser,
    private val zone: ZoneId,
    private val logDayStart: (LocalDate, Int) -> Instant,
) {

    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        val header =
            "date,weight_lb,smoothed_weight_lb,calories_kcal,protein_g,fat_g,carb_g,tdee_kcal"

        val uid = currentUser.userId()
        val profileEntity = profileDao.get(uid) ?: return@withContext header + "\n"
        val profile = profileEntity.toDomain()

        val weightEntities = weightDao.getAll(uid)
        val foodEntities = foodDao.getActive(uid).filter { it.deletedAt == null }

        if (weightEntities.isEmpty() && foodEntities.isEmpty()) {
            return@withContext header + "\n"
        }

        // First-of-day raw weight (kg), same bucketing the engine/weightSeries use.
        val rawByDay: Map<LocalDate, Double> = weightEntities
            .groupBy { logDay(it.timestamp, zone, profile.dayStartHour) }
            .mapValues { (_, entries) -> entries.minBy { it.timestamp }.weightKg }

        // Per-day food macro sums, aggregated by the shared logDay rule.
        data class MacroSum(val kcal: Double, val proteinG: Double, val fatG: Double, val carbG: Double)
        val macrosByDay: Map<LocalDate, MacroSum> = foodEntities
            .groupBy { logDay(it.timestamp, zone, profile.dayStartHour) }
            .mapValues { (_, entries) ->
                MacroSum(
                    kcal = entries.sumOf { it.kcal },
                    proteinG = entries.sumOf { it.proteinG },
                    fatG = entries.sumOf { it.fatG },
                    carbG = entries.sumOf { it.carbG },
                )
            }

        // Engine for EMA + TDEE per day (kg-based domain).
        val samples = weightEntities.toWeightSamples()
        val intake = foodEntities.toDailyIntake(zone, profile.dayStartHour)
        val engine = DefaultTdeeEngine(samples, intake, profile, zone)

        val dates = (rawByDay.keys + macrosByDay.keys).toSortedSet()

        fun num1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
        fun whole(v: Double) = Math.round(v).toString()

        val sb = StringBuilder(header).append("\n")
        for (date in dates) {
            val dayInstant = logDayStart(date, profile.dayStartHour)
            val emaKg = engine.weightTrendAt(dayInstant)
            val tdee = engine.estimateAt(dayInstant).valueKcal

            val rawKg = rawByDay[date]
            val macros = macrosByDay[date]

            sb.append(date.toString()).append(',')
                .append(if (rawKg != null) num1(kgToLb(rawKg)) else "").append(',')
                .append(num1(kgToLb(emaKg))).append(',')
                .append(if (macros != null) whole(macros.kcal) else "").append(',')
                .append(if (macros != null) whole(macros.proteinG) else "").append(',')
                .append(if (macros != null) whole(macros.fatG) else "").append(',')
                .append(if (macros != null) whole(macros.carbG) else "").append(',')
                .append(whole(tdee))
                .append('\n')
        }
        sb.toString()
    }
}
