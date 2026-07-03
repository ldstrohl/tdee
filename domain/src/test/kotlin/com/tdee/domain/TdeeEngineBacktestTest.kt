package com.tdee.domain

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Back-test of [DefaultTdeeEngine] against a synthetic 18-month log (559 contiguous days,
 * `TDEESampleData.csv`: daily calories, weigh-ins on ~27 % of days, ~200→~176 lb). The fixture is
 * generated (energy-balance-consistent), not real personal data.
 *
 * The engine's empirical TDEE is, by construction, `avgIntake − Δema·density/W` over a trailing
 * window — so over the WHOLE period it should reconcile with a hand-computed energy balance
 * (mean intake − total fat-mass change·density / days). These tests confirm that reconciliation on
 * real data, that estimates stay physiologically plausible, and that the carry-forward EMA stays
 * stable despite 73 % missing weigh-in days. Diagnostics print so the numbers can be eyeballed.
 */
class TdeeEngineBacktestTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val kgPerLb = 1.0 / 2.2046226
    private val density = 7700.0 // kcal/kg, engine default

    private data class Row(val date: LocalDate, val kcal: Double, val weightLb: Double?)

    private fun loadRows(): List<Row> {
        val text = checkNotNull(javaClass.getResourceAsStream("/TDEESampleData.csv")) {
            "TDEESampleData.csv missing from test resources"
        }.bufferedReader().readText()
        val fmt = DateTimeFormatter.ofPattern("M/d/yyyy")
        return text.lineSequence()
            .drop(1) // header
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val c = line.split(",")
                Row(
                    date = LocalDate.parse(c[0].trim(), fmt),
                    kcal = c[1].trim().toDouble(),
                    weightLb = c.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }?.toDouble(),
                )
            }
            .toList()
    }

    private fun build(rows: List<Row>): Triple<DefaultTdeeEngine, List<WeightSample>, List<DailyIntake>> {
        // Weigh-in at 07:00 UTC; meals implicitly cover the day. dayStartHour = 0 ⇒ log-day = date.
        val samples = rows.mapNotNull { r ->
            r.weightLb?.let {
                WeightSample(
                    t = r.date.atStartOfDay(zone).toInstant().plusSeconds(7 * 3600),
                    kg = it * kgPerLb,
                    quality = SampleQuality.DEVICE,
                )
            }
        }
        val intake = rows.map { DailyIntake(it.date, it.kcal, complete = true) }
        // Early-phase formula seed params (sex/age/height) only affect FORMULA/BLEND days; the
        // late-period EMPIRICAL estimate this test validates does not use them.
        val profile = UserProfile(
            sex = Sex.MALE,
            birthYear = 1983,
            heightCm = 178.0,
            activityLevel = ActivityLevel.SEDENTARY,
            goalRateKgPerWeek = -0.45,
        )
        return Triple(DefaultTdeeEngine(samples, intake, profile, zone), samples, intake)
    }

    private fun asOf(day: LocalDate): Instant =
        day.atStartOfDay(zone).toInstant().plusSeconds(20 * 3600) // evaluate late in the day

    @Test
    fun `engine reconciles with whole-period energy balance and stays plausible`() {
        val rows = loadRows()
        val (engine, samples, intake) = build(rows)
        val firstDay = rows.first().date
        val lastDay = rows.last().date
        val nDays = intake.size

        // Ground truth: energy balance across the whole period.
        // mean intake − (fat-mass change · density) / days, using the engine's own EMA endpoints
        // for the mass change (robust to single-day weigh-in noise).
        val emaFirst = engine.weightTrendAt(asOf(samples.first().t.atZone(zone).toLocalDate()))
        val emaLast = engine.weightTrendAt(asOf(lastDay))
        val meanIntake = intake.map { it.kcal }.average()
        val storedPerDay = (emaLast - emaFirst) * density / nDays
        val groundTruthTdee = meanIntake - storedPerDay

        // Engine: average its estimate over the EMPIRICAL-regime days (after a full window builds).
        val estimates = mutableListOf<Double>()
        val methods = HashMap<TdeeMethod, Int>()
        var day = firstDay
        while (!day.isAfter(lastDay)) {
            val est = engine.estimateAt(asOf(day))
            methods.merge(est.method, 1, Int::plus)
            if (est.method == TdeeMethod.EMPIRICAL) estimates.add(est.valueKcal)
            day = day.plusDays(1)
        }
        val engineMeanEmpirical = estimates.average()
        val minEst = estimates.min()
        val maxEst = estimates.max()

        println("──── TDEE engine back-test (${nDays} days, ${samples.size} weigh-ins) ────")
        println("weight EMA: %.1f kg → %.1f kg (%.1f → %.1f lb)".format(
            emaFirst, emaLast, emaFirst / kgPerLb, emaLast / kgPerLb))
        println("mean intake: %.0f kcal/day".format(meanIntake))
        println("ground-truth TDEE (energy balance): %.0f kcal/day".format(groundTruthTdee))
        println("engine mean EMPIRICAL estimate:     %.0f kcal/day".format(engineMeanEmpirical))
        println("engine EMPIRICAL range: %.0f … %.0f kcal".format(minEst, maxEst))
        println("method day counts: $methods")
        val pctDiff = 100.0 * (engineMeanEmpirical - groundTruthTdee) / groundTruthTdee
        println("engine vs ground truth: %+.1f%%".format(pctDiff))

        // 1. The engine's mean empirical estimate reconciles with whole-period energy balance.
        assertTrue(
            Math.abs(pctDiff) < 8.0,
            "engine mean empirical (%.0f) should be within 8%% of energy-balance ground truth (%.0f); was %+.1f%%"
                .format(engineMeanEmpirical, groundTruthTdee, pctDiff),
        )
        // 2. All empirical estimates are physiologically plausible (no sparse-weight blowups).
        assertTrue(minEst > 1200.0, "implausibly low TDEE estimate: %.0f".format(minEst))
        assertTrue(maxEst < 4500.0, "implausibly high TDEE estimate: %.0f".format(maxEst))
        // 3. The engine reaches the EMPIRICAL regime and spends most of the period there.
        assertTrue(
            (methods[TdeeMethod.EMPIRICAL] ?: 0) > nDays / 2,
            "expected the engine to be EMPIRICAL for most of the period; methods=$methods",
        )
    }

    @Test
    fun `carry-forward EMA stays stable despite sparse weigh-ins`() {
        val rows = loadRows()
        val (engine, _, _) = build(rows)
        val firstDay = rows.first().date
        val lastDay = rows.last().date

        var prev = engine.weightTrendAt(asOf(firstDay))
        var maxJump = 0.0
        var day = firstDay.plusDays(1)
        while (!day.isAfter(lastDay)) {
            val ema = engine.weightTrendAt(asOf(day))
            maxJump = maxOf(maxJump, Math.abs(ema - prev))
            prev = ema
            day = day.plusDays(1)
        }
        println("max day-over-day EMA jump: %.3f kg".format(maxJump))
        // A 14-day EMA on real weigh-ins should never move more than ~0.7 kg in a single day,
        // even when a weigh-in lands after a multi-day gap. Spikes would signal bad gap handling.
        assertTrue(maxJump < 0.7, "EMA jumped %.3f kg in one day — gap handling looks unstable".format(maxJump))
    }
}
