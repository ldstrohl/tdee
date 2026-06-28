package com.tdee.app.weight

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.FakeHealthConnectSource
import com.tdee.app.data.HcWeight
import com.tdee.app.data.HealthConnectSyncManager
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.app.data.WeightEntryEntity
import com.tdee.app.data.WeightSource
import com.tdee.app.insights.WeightRange
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Unit tests for [WeightViewModel] (Phase 4 weight hub).
 *
 * Setup mirrors CheckinViewModelTest: a male user with a 75 kg goal, 7 days of weight, UTC with
 * dayStartHour=0, clock fixed to noon on 2026-06-21. Uses Robolectric + in-memory Room + a fake
 * [CurrentUser] + the real [HealthConnectSyncManager] over a [FakeHealthConnectSource].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeightViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "weight-vm-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val day0 = LocalDate.of(2026, 6, 13)
    private val weights = listOf(80.0, 79.9, 79.8, 79.6, 79.5, 79.4, 79.3)

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        db.userProfileDao().upsert(
            UserProfileEntity(
                userId = userId,
                sex = Sex.MALE,
                birthYear = 1990,
                heightCm = 175.0,
                activityLevel = ActivityLevel.MODERATE,
                goalRateKgPerWeek = -0.25,
                goalWeightKg = 75.0,
                proteinGPerKg = 2.0,
                fatPctOfCalories = 0.25,
                dayStartHour = 0,
                smoothingWindowDays = 14,
                tdeeWindowDays = 14,
                createdAt = Instant.parse("2026-06-13T08:00:00Z"),
                updatedAt = Instant.parse("2026-06-13T08:00:00Z"),
            )
        )

        weights.forEachIndexed { i, kg ->
            val ts = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant().plusSeconds(8 * 3600)
            db.weightEntryDao().insert(
                WeightEntryEntity(
                    userId = userId,
                    timestamp = ts,
                    weightKg = kg,
                    source = WeightSource.MANUAL,
                    createdAt = ts,
                )
            )
        }

        repo = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            targetDao = db.targetPeriodDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            savedMealDao = db.savedMealDao(),
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

    private fun makeVm(source: FakeHealthConnectSource = FakeHealthConnectSource()): WeightViewModel {
        val syncManager = HealthConnectSyncManager(source, db.weightEntryDao(), fakeCurrentUser, fixedClock)
        return WeightViewModel(repo, syncManager, source)
    }

    private suspend fun WeightViewModel.awaitLoaded(): WeightUiState =
        state.first { !it.isLoading && it.visiblePoints.isNotEmpty() }

    // -----------------------------------------------------------------------

    @Test
    fun `loads weight trend points, goal line, and connected HC state`() = runTest {
        val vm = makeVm()
        val s = vm.awaitLoaded()

        assertTrue("trend has at least two points", s.visiblePoints.size >= 2)
        // EMA is expressed in lb (~80 kg → ~176 lb).
        assertTrue("EMA is in lb", s.visiblePoints.last().emaLb > 150.0)
        assertNotNull("goal line is set", s.goalLb)
        assertEquals(HcAvailability.CONNECTED, s.hc)
    }

    @Test
    fun `setRange recomputes the visible points and selected range`() = runTest {
        val vm = makeVm()
        vm.awaitLoaded()

        vm.setRange(WeightRange.ALL)
        val s = vm.state.value
        assertEquals(WeightRange.ALL, s.selectedRange)
        // ALL shows every loaded point.
        assertEquals(s.allPoints.size, s.visiblePoints.size)
    }

    @Test
    fun `syncFromHealthConnect imports a new weigh-in and refreshes the chart`() = runTest {
        val source = FakeHealthConnectSource().apply {
            add(HcWeight(uid = "hc-new", time = Instant.parse("2026-06-20T07:00:00Z"), weightKg = 79.2, bodyFatPct = null))
        }
        val vm = makeVm(source)
        vm.awaitLoaded()

        vm.syncFromHealthConnect()
        val s = vm.state.first { !it.syncing && it.syncStatus != null }

        assertEquals("Imported 1 weigh-in.", s.syncStatus)
        assertTrue(
            "the imported HC weight is now stored",
            db.weightEntryDao().getAll(userId).any { it.source == WeightSource.HEALTH_CONNECT },
        )
    }

    @Test
    fun `syncFromHealthConnect reports up to date when nothing new`() = runTest {
        val vm = makeVm() // empty HC source
        vm.awaitLoaded()

        vm.syncFromHealthConnect()
        val s = vm.state.first { !it.syncing && it.syncStatus != null }

        assertEquals("Up to date — no new weigh-ins.", s.syncStatus)
    }
}
