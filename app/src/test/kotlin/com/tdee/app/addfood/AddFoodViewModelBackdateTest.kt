package com.tdee.app.addfood

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.app.data.WeightEntryEntity
import com.tdee.app.data.WeightSource
import com.tdee.app.data.logDay
import com.tdee.domain.ActivityLevel
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
 * Tests for [AddFoodViewModel] backdate (selectedDate) behaviour.
 *
 * Fixed "today" = 2026-06-21 (UTC), past date = 2026-06-15.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddFoodViewModelBackdateTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository
    private lateinit var vm: AddFoodViewModel

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val today = LocalDate.of(2026, 6, 21)
    private val pastDate = LocalDate.of(2026, 6, 15)
    private val futureDate = LocalDate.of(2026, 6, 28)

    private val userId = "add-food-backdate-user"
    private val fakeCurrentUser = CurrentUser { userId }
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        val now = fixedNow
        db.userProfileDao().upsert(
            UserProfileEntity(
                userId = userId,
                sex = Sex.MALE,
                birthYear = 1990,
                heightCm = 175.0,
                activityLevel = ActivityLevel.MODERATE,
                goalRateKgPerWeek = 0.0,
                goalWeightKg = null,
                proteinGPerKg = 2.0,
                fatPctOfCalories = 0.25,
                dayStartHour = 0,
                smoothingWindowDays = 14,
                tdeeWindowDays = 14,
                createdAt = now,
                updatedAt = now,
            )
        )
        db.weightEntryDao().insert(
            WeightEntryEntity(
                userId = userId,
                timestamp = now,
                weightKg = 80.0,
                source = WeightSource.MANUAL,
                createdAt = now,
            )
        )

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

        vm = AddFoodViewModel(repo, today = today)
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `selectedDate defaults to today`() {
        assertEquals(today, vm.form.value.selectedDate)
    }

    @Test
    fun `setSelectedDate to past date updates state`() {
        vm.setSelectedDate(pastDate)
        assertEquals(pastDate, vm.form.value.selectedDate)
    }

    @Test
    fun `setSelectedDate clamps future dates to today`() {
        vm.setSelectedDate(futureDate)
        assertEquals(today, vm.form.value.selectedDate)
    }

    @Test
    fun `save with past date persists entry on that log-day`() = runTest {
        vm.setSelectedDate(pastDate)
        vm.setName("Chicken")
        vm.setKcal("250")

        vm.save()
        vm.saved.filter { it }.first()

        val all = db.foodEntryDao().getActive(userId)
        assertEquals(1, all.size)
        val resolvedDay = logDay(all[0].timestamp, zone, dayStartHour = 0)
        assertEquals(pastDate, resolvedDay)
    }

    @Test
    fun `save with past date does NOT appear in todayFoodEntries`() = runTest {
        vm.setSelectedDate(pastDate)
        vm.setName("Rice")
        vm.setKcal("200")

        vm.save()
        vm.saved.filter { it }.first()

        val todayEntries = repo.todayFoodEntries()
        assertTrue("Past-dated entry must not appear in today list", todayEntries.isEmpty())
    }

    @Test
    fun `save with today as selected date appears in todayFoodEntries`() = runTest {
        // selectedDate is already today by default
        vm.setName("Egg")
        vm.setKcal("78")

        vm.save()
        vm.saved.filter { it }.first()

        val todayEntries = repo.todayFoodEntries()
        assertEquals(1, todayEntries.size)
    }
}
