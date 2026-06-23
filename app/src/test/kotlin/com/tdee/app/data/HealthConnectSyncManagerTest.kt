package com.tdee.app.data

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// ---------------------------------------------------------------------------
// Fake source — no HC SDK, no device required
// ---------------------------------------------------------------------------

/**
 * Fake implementation of [HealthConnectSource] for unit tests.
 *
 * [weights] is the full in-memory list. [readWeights] filters by [since] to
 * simulate the SDK's time-range filtering.
 */
class FakeHealthConnectSource(
    private val weights: MutableList<HcWeight> = mutableListOf(),
) : HealthConnectSource {

    override suspend fun isAvailable(): Boolean = true
    override suspend fun hasReadPermission(): Boolean = true

    override suspend fun readWeights(since: Instant?): List<HcWeight> {
        return if (since == null) {
            weights.toList()
        } else {
            // Inclusive lower bound: records at or after `since`.
            weights.filter { !it.time.isBefore(since) }
        }
    }

    fun add(weight: HcWeight) = weights.add(weight)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectSyncManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var weightDao: WeightEntryDao

    private val userId = "test-user"
    private val otherUserId = "other-user"
    private val fixedNow: Instant = Instant.parse("2024-06-01T12:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val currentUser: CurrentUser = CurrentUser { userId }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        weightDao = db.weightEntryDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private fun makeManager(source: HealthConnectSource) =
        HealthConnectSyncManager(source, weightDao, currentUser, fixedClock)

    private fun hcWeight(
        uid: String,
        timeStr: String,
        kg: Double,
        bodyFat: Double? = null,
    ) = HcWeight(
        uid = uid,
        time = Instant.parse(timeStr),
        weightKg = kg,
        bodyFatPct = bodyFat,
    )

    // -----------------------------------------------------------------------
    // 1. Mapping: HcWeight → WeightEntryEntity
    // -----------------------------------------------------------------------

    @Test
    fun `sync maps HcWeight fields correctly to WeightEntryEntity`() = runTest {
        val source = FakeHealthConnectSource().apply {
            add(hcWeight("uid-1", "2024-05-20T07:00:00Z", 80.5, bodyFat = 22.3))
        }
        val manager = makeManager(source)

        manager.sync(fullHistory = true)

        val stored = weightDao.getAll(userId)
        assertEquals(1, stored.size)
        val entity = stored[0]
        assertEquals(WeightSource.HEALTH_CONNECT, entity.source)
        assertEquals("uid-1", entity.healthConnectUid)
        assertEquals(80.5, entity.weightKg, 0.001)
        assertEquals(22.3, entity.bodyFatPct!!, 0.001)
        assertEquals(Instant.parse("2024-05-20T07:00:00Z"), entity.timestamp)
        assertEquals(userId, entity.userId)
        assertEquals(fixedNow, entity.createdAt)
    }

    @Test
    fun `sync stores null bodyFatPct when HcWeight has no body fat`() = runTest {
        val source = FakeHealthConnectSource().apply {
            add(hcWeight("uid-2", "2024-05-21T07:00:00Z", 79.0, bodyFat = null))
        }
        makeManager(source).sync(fullHistory = true)

        val entity = weightDao.getAll(userId).single()
        assertEquals(null, entity.bodyFatPct)
    }

    // -----------------------------------------------------------------------
    // 2. Dedup: syncing the same records twice inserts them only once
    // -----------------------------------------------------------------------

    @Test
    fun `syncing same records twice inserts them only once`() = runTest {
        val source = FakeHealthConnectSource().apply {
            add(hcWeight("uid-A", "2024-05-10T07:00:00Z", 81.0))
            add(hcWeight("uid-B", "2024-05-11T07:00:00Z", 80.5))
        }
        val manager = makeManager(source)

        val first = manager.sync(fullHistory = true)
        val second = manager.sync(fullHistory = true)

        assertEquals(2, first)
        assertEquals(0, second)  // all duplicates, none inserted
        assertEquals(2, weightDao.getAll(userId).size)
    }

    @Test
    fun `second sync with one new record inserts only the new one`() = runTest {
        val source = FakeHealthConnectSource().apply {
            add(hcWeight("uid-A", "2024-05-10T07:00:00Z", 81.0))
        }
        val manager = makeManager(source)

        val first = manager.sync(fullHistory = true)
        assertEquals(1, first)

        // Add a new record
        source.add(hcWeight("uid-B", "2024-05-11T07:00:00Z", 80.5))
        val second = manager.sync(fullHistory = true)
        assertEquals(1, second)  // only uid-B is new
        assertEquals(2, weightDao.getAll(userId).size)
    }

    // -----------------------------------------------------------------------
    // 3. History pull vs incremental sync
    // -----------------------------------------------------------------------

    @Test
    fun `fullHistory=true reads since=null and inserts all records`() = runTest {
        val source = FakeHealthConnectSource().apply {
            add(hcWeight("uid-1", "2024-01-01T07:00:00Z", 85.0))
            add(hcWeight("uid-2", "2024-03-01T07:00:00Z", 83.0))
            add(hcWeight("uid-3", "2024-05-01T07:00:00Z", 81.0))
        }
        val inserted = makeManager(source).sync(fullHistory = true)

        assertEquals(3, inserted)
        assertEquals(3, weightDao.getAll(userId).size)
    }

    @Test
    fun `incremental sync reads since the latest existing HC entry timestamp`() = runTest {
        // Pre-populate two HC entries in DB, simulating a prior full sync.
        val t1 = Instant.parse("2024-05-01T07:00:00Z")
        val t2 = Instant.parse("2024-05-10T07:00:00Z")
        weightDao.insertIgnoreDuplicate(
            WeightEntryEntity(
                userId = userId,
                timestamp = t1,
                weightKg = 82.0,
                source = WeightSource.HEALTH_CONNECT,
                healthConnectUid = "uid-old-1",
                createdAt = fixedNow,
            )
        )
        weightDao.insertIgnoreDuplicate(
            WeightEntryEntity(
                userId = userId,
                timestamp = t2,
                weightKg = 81.5,
                source = WeightSource.HEALTH_CONNECT,
                healthConnectUid = "uid-old-2",
                createdAt = fixedNow,
            )
        )

        // Source has the two old records plus one new record after t2.
        val t3 = Instant.parse("2024-05-20T07:00:00Z")
        val source = FakeHealthConnectSource().apply {
            add(hcWeight("uid-old-1", t1.toString(), 82.0))
            add(hcWeight("uid-old-2", t2.toString(), 81.5))
            add(hcWeight("uid-new",   t3.toString(), 80.0))
        }

        // Incremental sync: should pass `since = t2` to the source.
        // FakeHealthConnectSource returns records at or after t2: uid-old-2 and uid-new.
        // uid-old-2 is already in DB → ignored; uid-new → inserted.
        val inserted = makeManager(source).sync(fullHistory = false)

        assertEquals(1, inserted)
        assertEquals(3, weightDao.getAll(userId).size)
    }

    @Test
    fun `incremental sync with no existing HC entries behaves like fullHistory`() = runTest {
        // No HC entries for user, but there is a MANUAL entry (should be ignored for since calc).
        weightDao.insert(
            WeightEntryEntity(
                userId = userId,
                timestamp = Instant.parse("2024-04-01T08:00:00Z"),
                weightKg = 84.0,
                source = WeightSource.MANUAL,
                createdAt = fixedNow,
            )
        )

        val source = FakeHealthConnectSource().apply {
            add(hcWeight("uid-A", "2024-04-15T07:00:00Z", 83.0))
            add(hcWeight("uid-B", "2024-05-01T07:00:00Z", 82.0))
        }

        // since = null because no HC entries exist yet → reads everything
        val inserted = makeManager(source).sync(fullHistory = false)
        assertEquals(2, inserted)
    }

    // -----------------------------------------------------------------------
    // 4. User scoping
    // -----------------------------------------------------------------------

    @Test
    fun `records insert under the current user and other user sees nothing`() = runTest {
        val source = FakeHealthConnectSource().apply {
            add(hcWeight("uid-X", "2024-05-05T07:00:00Z", 78.0))
        }
        makeManager(source).sync(fullHistory = true)

        assertEquals(1, weightDao.getAll(userId).size)
        assertEquals(0, weightDao.getAll(otherUserId).size)
    }

    @Test
    fun `same HC uid for two users inserts a row for each (not deduped across users)`() = runTest {
        // User A syncs
        val sourceA = FakeHealthConnectSource().apply {
            add(hcWeight("shared-uid", "2024-05-05T07:00:00Z", 78.0))
        }
        HealthConnectSyncManager(sourceA, weightDao, CurrentUser { userId }, fixedClock)
            .sync(fullHistory = true)

        // User B syncs the same HC uid
        val sourceB = FakeHealthConnectSource().apply {
            add(hcWeight("shared-uid", "2024-05-05T07:00:00Z", 78.0))
        }
        HealthConnectSyncManager(sourceB, weightDao, CurrentUser { otherUserId }, fixedClock)
            .sync(fullHistory = true)

        // Each user has their own row — the unique index is per (userId, healthConnectUid).
        assertEquals(1, weightDao.getAll(userId).size)
        assertEquals(1, weightDao.getAll(otherUserId).size)
    }
}
