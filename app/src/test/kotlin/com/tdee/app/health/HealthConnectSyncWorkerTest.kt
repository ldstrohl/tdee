package com.tdee.app.health

import androidx.room.Room
import androidx.work.ListenableWorker
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.FakeHealthConnectSource
import com.tdee.app.data.HcWeight
import com.tdee.app.data.HealthConnectSource
import com.tdee.app.data.HealthConnectSyncManager
import com.tdee.app.data.WeightEntryDao
import kotlinx.coroutines.test.runTest
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
import java.time.ZoneOffset

/**
 * Unit tests for the worker's pure sync-delegation logic ([HealthConnectSyncWorker.runSync]).
 *
 * The real grant + HC read is verified on-device; here we only assert that a successful
 * sync maps to [ListenableWorker.Result.success] and a throwing source maps to retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectSyncWorkerTest {

    private lateinit var db: AppDatabase
    private lateinit var weightDao: WeightEntryDao

    private val userId = "test-user"
    private val clock: Clock =
        Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC)
    private val currentUser = CurrentUser { userId }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        weightDao = db.weightEntryDao()
    }

    @After
    fun teardown() = db.close()

    private fun manager(source: HealthConnectSource) =
        HealthConnectSyncManager(source, weightDao, currentUser, clock)

    @Test
    fun `runSync delegates a successful sync and returns success`() = runTest {
        val source = FakeHealthConnectSource().apply {
            add(HcWeight("uid-1", Instant.parse("2024-05-20T07:00:00Z"), 80.0, null))
        }

        val result = HealthConnectSyncWorker.runSync(manager(source))

        assertEquals(ListenableWorker.Result.success(), result)
        // The incremental sync actually inserted the record.
        assertEquals(1, weightDao.getAll(userId).size)
    }

    @Test
    fun `runSync returns retry when the source throws`() = runTest {
        val throwing = object : HealthConnectSource {
            override suspend fun isAvailable() = true
            override suspend fun hasReadPermission() = true
            override suspend fun readWeights(since: Instant?): List<HcWeight> =
                throw IllegalStateException("HC unavailable")
        }

        val result = HealthConnectSyncWorker.runSync(manager(throwing))

        assertTrue(result is ListenableWorker.Result.Retry)
    }
}
