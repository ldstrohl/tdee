package com.tdee.app.insights

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.ChartWindow
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.DayWeightPoint
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.data.FoodSourceDb
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.app.data.WeightEntryEntity
import com.tdee.app.data.WeightProjection
import com.tdee.app.data.WeightSource
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Projection
import com.tdee.domain.Sex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [InsightsViewModel].
 *
 * Verifies:
 *   1. Range slicing (1mo returns ~last 30 days)
 *   2. kg→lb conversion in the exposed series
 *   3. Prediction state toggling independently of range
 *   4. Projection surfaced (dates + reachable/unreachable)
 *
 * Uses Robolectric + in-memory Room + fake CurrentUser + fixed Clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsightsViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    // Fixed "now" = 2026-06-22T12:00:00Z → log-day = 2026-06-22
    private val fixedNow = Instant.parse("2026-06-22T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val today = LocalDate.of(2026, 6, 22)

    private val userId = "insights-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        repo = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            targetDao = db.targetPeriodDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            currentUser = fakeCurrentUser,
            zone = zone,
            clock = fixedClock,
        )
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private suspend fun seedProfile(goalWeightKg: Double? = 75.0) {
        db.userProfileDao().upsert(
            UserProfileEntity(
                userId = userId,
                sex = Sex.MALE,
                birthYear = 1990,
                heightCm = 175.0,
                activityLevel = ActivityLevel.MODERATE,
                goalRateKgPerWeek = -0.25,
                goalWeightKg = goalWeightKg,
                dayStartHour = 0,
                smoothingWindowDays = 14,
                tdeeWindowDays = 14,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
        )
    }

    private suspend fun insertWeight(date: LocalDate, weightKg: Double) {
        val ts = date.atStartOfDay(zone).toInstant().plusSeconds(8 * 3600)
        db.weightEntryDao().insert(
            WeightEntryEntity(
                userId = userId,
                timestamp = ts,
                weightKg = weightKg,
                source = WeightSource.MANUAL,
                createdAt = ts,
            )
        )
    }

    /**
     * Seeds [count] days of weight data ending on today.
     * Weight starts at [startKg] and decreases by [dailyDeltaKg] per day.
     */
    private suspend fun seedWeightHistory(
        count: Int,
        startKg: Double = 80.0,
        dailyDeltaKg: Double = -0.05,
    ) {
        for (i in 0 until count) {
            val date = today.minusDays((count - 1 - i).toLong())
            insertWeight(date, startKg + dailyDeltaKg * i)
        }
    }

    /** Waits for the state to leave the Loading state. */
    private suspend fun awaitLoaded(vm: InsightsViewModel): InsightsUiState =
        vm.state.filter { !it.isLoading }.first()

    // -----------------------------------------------------------------------
    // 1. Range slicing
    // -----------------------------------------------------------------------

    @Test
    fun `1mo range returns only the last 30 days of data`() = runTest {
        seedProfile()
        // Seed 100 days so there is data both inside and outside the 30-day window.
        seedWeightHistory(count = 100)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        vm.setRange(WeightRange.M1)
        val state = vm.state.value

        // The visible window should start no earlier than today - 30 days.
        val cutoff = today.minusDays(30)
        assertTrue(
            "All visible points should be within the 1-month window",
            state.visiblePoints.all { !it.date.isBefore(cutoff) },
        )
        // And should contain the most recent point (today).
        assertEquals(today, state.visiblePoints.last().date)
    }

    @Test
    fun `ALL range returns all available data points`() = runTest {
        seedProfile()
        seedWeightHistory(count = 100)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        vm.setRange(WeightRange.ALL)
        val state = vm.state.value

        // All points should be visible.
        assertEquals(state.allPoints.size, state.visiblePoints.size)
    }

    @Test
    fun `3mo range is the default selection`() = runTest {
        seedProfile()
        seedWeightHistory(count = 30)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        assertEquals(WeightRange.M3, vm.state.value.selectedRange)
    }

    @Test
    fun `switching range does not change predictionOn`() = runTest {
        seedProfile()
        seedWeightHistory(count = 60)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        // Turn prediction on first.
        vm.setPrediction(true)
        assertTrue(vm.state.value.predictionOn)

        // Switch range — prediction should stay on.
        vm.setRange(WeightRange.M1)
        assertTrue("predictionOn should remain true after range change", vm.state.value.predictionOn)
        assertEquals(WeightRange.M1, vm.state.value.selectedRange)
    }

    // -----------------------------------------------------------------------
    // 2. kg → lb conversion
    // -----------------------------------------------------------------------

    @Test
    fun `emaLb is emaKg times 2_2046226`() = runTest {
        seedProfile()
        insertWeight(today.minusDays(2), 80.0)
        insertWeight(today.minusDays(1), 79.5)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        val state = vm.state.value
        assertTrue("Should have at least one visible point", state.visiblePoints.isNotEmpty())

        // Get the raw series directly from the repo to compare.
        val rawSeries = repo.weightSeries()
        rawSeries.forEachIndexed { i, rawPoint ->
            val expected = rawPoint.emaKg * KG_TO_LB
            val actual = state.allPoints.find { it.date == rawPoint.date }?.emaLb
            assertNotNull("Should have a point for date ${rawPoint.date}", actual)
            assertEquals(
                "emaLb should equal emaKg × 2.2046226 for date ${rawPoint.date}",
                expected,
                actual!!,
                0.0001,
            )
        }
    }

    @Test
    fun `rawLb is rawKg times 2_2046226 when measurement exists`() = runTest {
        seedProfile()
        val weightKg = 80.0
        insertWeight(today.minusDays(1), weightKg)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        val state = vm.state.value
        val point = state.allPoints.find { it.date == today.minusDays(1) }
        assertNotNull(point)
        assertNotNull("rawLb should not be null when a measurement was recorded", point!!.rawLb)
        assertEquals(weightKg * KG_TO_LB, point.rawLb!!, 0.0001)
    }

    @Test
    fun `rawLb is null on days with no measurement`() = runTest {
        seedProfile()
        insertWeight(today.minusDays(3), 80.0)
        // today.minusDays(2) and today.minusDays(1) have no measurement

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        val state = vm.state.value
        val noMeasurementPoint = state.allPoints.find { it.date == today.minusDays(2) }
        assertNotNull(noMeasurementPoint)
        assertTrue("rawLb should be null when no measurement for that day", noMeasurementPoint!!.rawLb == null)
    }

    @Test
    fun `goalLb in projection is goalKg times 2_2046226`() = runTest {
        val goalKg = 75.0
        seedProfile(goalWeightKg = goalKg)
        seedWeightHistory(count = 20)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        val projReady = vm.state.value.projection as? ProjectionUi.Ready
        assertNotNull("Projection should be Ready when goal is set", projReady)
        assertEquals(goalKg * KG_TO_LB, projReady!!.goalLb, 0.0001)
    }

    // -----------------------------------------------------------------------
    // 3. Prediction state independent of range
    // -----------------------------------------------------------------------

    @Test
    fun `predictionOn defaults to false`() = runTest {
        seedProfile()
        seedWeightHistory(count = 10)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        assertFalse(vm.state.value.predictionOn)
    }

    @Test
    fun `setPrediction true turns prediction on`() = runTest {
        seedProfile()
        seedWeightHistory(count = 10)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        vm.setPrediction(true)
        assertTrue(vm.state.value.predictionOn)
    }

    @Test
    fun `setPrediction false turns prediction off`() = runTest {
        seedProfile()
        seedWeightHistory(count = 10)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        vm.setPrediction(true)
        vm.setPrediction(false)
        assertFalse(vm.state.value.predictionOn)
    }

    @Test
    fun `changing prediction does not alter selectedRange`() = runTest {
        seedProfile()
        seedWeightHistory(count = 60)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        vm.setRange(WeightRange.M6)
        vm.setPrediction(true)

        assertEquals("Range should stay M6 after toggling prediction", WeightRange.M6, vm.state.value.selectedRange)
    }

    // -----------------------------------------------------------------------
    // 4. Projection surfaced correctly
    // -----------------------------------------------------------------------

    @Test
    fun `projection is NoGoal when no goalWeightKg is set`() = runTest {
        seedProfile(goalWeightKg = null)
        seedWeightHistory(count = 10)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        assertTrue(
            "Projection should be NoGoal when no goal is set",
            vm.state.value.projection is ProjectionUi.NoGoal,
        )
    }

    @Test
    fun `projection is Ready when goal is set and data exists`() = runTest {
        seedProfile(goalWeightKg = 75.0)
        seedWeightHistory(count = 20)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        assertTrue(
            "Projection should be Ready when goal is set and weight data exists",
            vm.state.value.projection is ProjectionUi.Ready,
        )
    }

    @Test
    fun `goalPace is Reachable with deficit rate toward lower goal`() = runTest {
        // Profile: goalRateKgPerWeek = -0.25, goal = 75 kg, trend ~80 kg → goal pace reachable.
        seedProfile(goalWeightKg = 75.0)
        seedWeightHistory(count = 20)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        val projReady = vm.state.value.projection as? ProjectionUi.Ready
        assertNotNull(projReady)
        assertTrue(
            "goalPace should be Reachable, got ${projReady!!.goalPace}",
            projReady.goalPace is PaceUi.Reachable,
        )
        val pace = projReady.goalPace as PaceUi.Reachable
        assertNotNull("Reachable goalPace should have a predicted date", pace.date)
    }

    @Test
    fun `currentPace is Unreachable when EMA is flat`() = runTest {
        seedProfile(goalWeightKg = 75.0)
        // Insert a flat weight series so the slope is ~0.
        for (i in 0..15) {
            insertWeight(today.minusDays(i.toLong()), 80.0)
        }

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        val projReady = vm.state.value.projection as? ProjectionUi.Ready
        assertNotNull(projReady)
        assertTrue(
            "currentPace should be Unreachable with flat EMA, got ${projReady!!.currentPace}",
            projReady.currentPace is PaceUi.Unreachable,
        )
    }

    @Test
    fun `currentPace is Reachable when EMA trends downward toward lower goal`() = runTest {
        seedProfile(goalWeightKg = 75.0)
        // Seed 16 days of clearly declining weight.
        for (i in 0..15) {
            insertWeight(today.minusDays((15 - i).toLong()), 82.0 - i * 0.1)
        }

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        val projReady = vm.state.value.projection as? ProjectionUi.Ready
        assertNotNull(projReady)
        assertTrue(
            "currentPace should be Reachable with downward EMA trend, got ${projReady!!.currentPace}",
            projReady.currentPace is PaceUi.Reachable,
        )
    }

    // -----------------------------------------------------------------------
    // 5. Helper unit tests — buildProjectionUi and toLb
    // -----------------------------------------------------------------------

    @Test
    fun `buildProjectionUi returns NoGoal for null input`() {
        val result = buildProjectionUi(null)
        assertTrue(result is ProjectionUi.NoGoal)
    }

    @Test
    fun `buildProjectionUi converts goalKg to lb`() {
        val wp = WeightProjection(
            currentTrendKg = 80.0,
            goalKg = 75.0,
            goalPace = Projection.Reachable(
                predictedDate = LocalDate.of(2026, 12, 1),
                rateKgPerDay = -0.036,
            ),
            currentPace = Projection.Unreachable("flat"),
        )
        val result = buildProjectionUi(wp) as ProjectionUi.Ready
        assertEquals(75.0 * KG_TO_LB, result.goalLb, 0.0001)
        assertEquals(80.0 * KG_TO_LB, result.currentTrendLb, 0.0001)
    }

    @Test
    fun `buildProjectionUi maps Reachable projection correctly`() {
        val predictedDate = LocalDate.of(2027, 3, 15)
        val wp = WeightProjection(
            currentTrendKg = 80.0,
            goalKg = 75.0,
            goalPace = Projection.Reachable(predictedDate, rateKgPerDay = -0.036),
            currentPace = Projection.Unreachable("flat"),
        )
        val result = buildProjectionUi(wp) as ProjectionUi.Ready
        val pace = result.goalPace as PaceUi.Reachable
        assertEquals(predictedDate, pace.date)
        assertEquals(-0.036 * KG_TO_LB, pace.rateLbPerDay, 0.0001)
    }

    @Test
    fun `buildProjectionUi maps Unreachable projection correctly`() {
        val wp = WeightProjection(
            currentTrendKg = 80.0,
            goalKg = 75.0,
            goalPace = Projection.Unreachable("going wrong direction"),
            currentPace = Projection.Unreachable("flat"),
        )
        val result = buildProjectionUi(wp) as ProjectionUi.Ready
        val pace = result.goalPace as PaceUi.Unreachable
        assertEquals("going wrong direction", pace.reason)
    }

    @Test
    fun `DayWeightPoint toLb converts correctly`() {
        val point = DayWeightPoint(
            date = LocalDate.of(2026, 6, 1),
            rawKg = 80.0,
            emaKg = 79.5,
        )
        val result = point.toLb()
        assertEquals(80.0 * KG_TO_LB, result.rawLb!!, 0.0001)
        assertEquals(79.5 * KG_TO_LB, result.emaLb, 0.0001)
    }

    @Test
    fun `DayWeightPoint toLb preserves null rawKg`() {
        val point = DayWeightPoint(
            date = LocalDate.of(2026, 6, 1),
            rawKg = null,
            emaKg = 79.5,
        )
        val result = point.toLb()
        assertTrue(result.rawLb == null)
    }

    // -----------------------------------------------------------------------
    // Helpers for food / expenditure tests
    // -----------------------------------------------------------------------

    private suspend fun insertFood(
        date: LocalDate,
        kcal: Double,
        proteinG: Double = kcal * 0.20 / 4.0,
        fatG: Double = kcal * 0.30 / 9.0,
        carbG: Double = kcal * 0.50 / 4.0,
    ) {
        // Timestamp at noon on the given date so it falls in the log-day window
        val ts = date.atStartOfDay(zone).toInstant().plusSeconds(12 * 3600)
        db.foodEntryDao().insert(
            FoodEntryEntity(
                userId = userId,
                timestamp = ts,
                rawText = "test meal",
                name = "Test Meal",
                quantity = 1.0,
                unit = "serving",
                grams = 300.0,
                kcal = kcal,
                proteinG = proteinG,
                fatG = fatG,
                carbG = carbG,
                sourceDb = FoodSourceDb.MANUAL,
                createdAt = ts,
                updatedAt = ts,
            )
        )
    }

    // -----------------------------------------------------------------------
    // 6. Expenditure series
    // -----------------------------------------------------------------------

    @Test
    fun `expenditure series is surfaced in VM state after load`() = runTest {
        seedProfile()
        seedWeightHistory(count = 10)
        // Log food on 5 of the 10 days
        for (i in 0 until 5) {
            insertFood(today.minusDays(i.toLong()), kcal = 2000.0)
        }

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        val state = vm.state.value
        assertTrue("Expenditure points should be non-empty", state.visibleExpenditurePoints.isNotEmpty())
        // Days with food logged should have non-null intakeKcal
        val loggedDays = state.visibleExpenditurePoints.filter { it.intakeKcal != null }
        assertTrue("Logged days should have intake kcal", loggedDays.isNotEmpty())
        // Days without food should have null intakeKcal
        val unloggedDays = state.visibleExpenditurePoints.filter { it.intakeKcal == null }
        assertTrue("Unlogged days should have null intake kcal", unloggedDays.isNotEmpty())
    }

    @Test
    fun `expenditure range slicing returns only the windowed subset`() = runTest {
        seedProfile()
        // 50 days of weight + food data
        seedWeightHistory(count = 50)
        for (i in 0 until 50) {
            insertFood(today.minusDays(i.toLong()), kcal = 2100.0)
        }

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        // Default range is M3 (90 days) — with 50 days of data, all points visible
        val stateM3 = vm.state.value
        assertEquals(ExpenditureRange.M3, stateM3.expenditureRange)

        // Switch to M1 (30 days)
        vm.setExpenditureRange(ExpenditureRange.M1)
        val stateM1 = vm.state.value
        val cutoff = today.minusDays(30)
        assertTrue(
            "All visible expenditure points should be within 30-day window",
            stateM1.visibleExpenditurePoints.all { !it.date.isBefore(cutoff) },
        )
        assertEquals(today, stateM1.visibleExpenditurePoints.last().date)
    }

    @Test
    fun `changing expenditure range does not affect trend range or prediction`() = runTest {
        seedProfile()
        seedWeightHistory(count = 60)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        vm.setRange(WeightRange.M6)
        vm.setPrediction(true)
        vm.setExpenditureRange(ExpenditureRange.M1)

        val state = vm.state.value
        assertEquals("Trend range unchanged", WeightRange.M6, state.selectedRange)
        assertTrue("Prediction unchanged", state.predictionOn)
        assertEquals("Expenditure range updated", ExpenditureRange.M1, state.expenditureRange)
    }

    // -----------------------------------------------------------------------
    // 7. Macro summary
    // -----------------------------------------------------------------------

    @Test
    fun `macro summary for TODAY reflects today's food entries`() = runTest {
        seedProfile()
        insertWeight(today, 80.0)
        insertFood(today, kcal = 500.0, proteinG = 40.0, fatG = 15.0, carbG = 60.0)
        insertFood(today, kcal = 300.0, proteinG = 20.0, fatG = 8.0, carbG = 40.0)

        val summary = repo.macroSummary(ChartWindow.TODAY)

        // Totals (not averages) for TODAY
        assertEquals(500.0 + 300.0, summary.kcal, 1.0)
        assertEquals(40.0 + 20.0, summary.proteinG, 0.5)
        assertEquals(15.0 + 8.0, summary.fatG, 0.5)
        assertEquals(60.0 + 40.0, summary.carbG, 0.5)
        // completeDays and totalDays are both 1 sentinel for TODAY
        assertEquals(1, summary.completeDays)
        assertEquals(1, summary.totalDays)
    }

    @Test
    fun `macro summary for M1 window averages over complete days only`() = runTest {
        seedProfile()
        seedWeightHistory(count = 35)

        // Log food on 3 days within the 30-day window
        val day1 = today.minusDays(5)
        val day2 = today.minusDays(10)
        val day3 = today.minusDays(20)
        insertFood(day1, kcal = 2400.0, proteinG = 150.0, fatG = 80.0, carbG = 240.0)
        insertFood(day2, kcal = 2100.0, proteinG = 120.0, fatG = 70.0, carbG = 210.0)
        insertFood(day3, kcal = 1800.0, proteinG = 90.0, fatG = 60.0, carbG = 180.0)

        val summary = repo.macroSummary(ChartWindow.M1)

        // Should average over 3 complete days
        assertEquals(3, summary.completeDays)
        val expectedKcal = (2400.0 + 2100.0 + 1800.0) / 3.0
        assertEquals(expectedKcal, summary.kcal, 1.0)
        val expectedProtein = (150.0 + 120.0 + 90.0) / 3.0
        assertEquals(expectedProtein, summary.proteinG, 0.5)
    }

    @Test
    fun `macro summary completeDays and totalDays are correct for M1 window`() = runTest {
        seedProfile()
        seedWeightHistory(count = 35)
        // Log on 2 of the 30 days in the window
        insertFood(today.minusDays(3), kcal = 2000.0)
        insertFood(today.minusDays(7), kcal = 2000.0)

        val summary = repo.macroSummary(ChartWindow.M1)

        assertEquals(2, summary.completeDays)
        // totalDays spans from today-1month to today (inclusive); at least 30 days
        assertTrue("totalDays should be >= 30", summary.totalDays >= 30)
    }

    @Test
    fun `macro summary VM state uses TODAY by default`() = runTest {
        seedProfile()
        insertWeight(today, 80.0)
        insertFood(today, kcal = 600.0, proteinG = 50.0, fatG = 20.0, carbG = 70.0)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        val state = vm.state.value
        assertEquals(MacroWindow.TODAY, state.macroWindow)
        assertNotNull("macroSummary should be loaded", state.macroSummary)
        // For TODAY the kcal should match our single food entry
        assertEquals(600.0, state.macroSummary!!.kcal, 1.0)
    }

    @Test
    fun `setMacroWindow changes window without affecting expenditure or trend ranges`() = runTest {
        seedProfile()
        seedWeightHistory(count = 60)

        val vm = InsightsViewModel(repo)
        awaitLoaded(vm)

        vm.setRange(WeightRange.M6)
        vm.setExpenditureRange(ExpenditureRange.Y1)
        vm.setMacroWindow(MacroWindow.M3)

        // Allow the coroutine to complete
        val state = vm.state.value
        assertEquals("Trend range unchanged", WeightRange.M6, state.selectedRange)
        assertEquals("Expenditure range unchanged", ExpenditureRange.Y1, state.expenditureRange)
        assertEquals("Macro window updated", MacroWindow.M3, state.macroWindow)
    }
}
