package com.tdee.app.settings

import com.tdee.app.data.BackupCounts
import com.tdee.app.data.DriveError
import com.tdee.app.data.DriveException
import com.tdee.app.data.DriveFile
import com.tdee.app.data.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [BackupViewModel]. Every external effect (Room via BackupManager, Drive REST,
 * OAuth) is a plain fake lambda — no Robolectric GMS/MockWebServer dependency needed, since
 * [BackupViewModel] takes function parameters rather than the concrete `BackupManager`/
 * `DriveClient`/`DriveAuth` classes (those are final and do real I/O, so faking them directly
 * would require MockWebServer/Play-Services shadows; lambdas are simpler and match the project's
 * "prefer constructor-injected lambdas over new abstractions" convention).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupViewModelTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-09T14:30:00Z"), ZoneOffset.UTC)
    private val testDispatcher = UnconfinedTestDispatcher()

    private val localCounts = BackupCounts(foods = 10, weights = 5, savedMeals = 2, targets = 1, hasProfile = true)
    private val backupCounts = BackupCounts(foods = 20, weights = 8, savedMeals = 3, targets = 2, hasProfile = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    /** Builds a VM with working-by-default fakes; individual tests override what they need. */
    private fun makeVm(
        backup: suspend () -> String = { "{}" },
        localCountsFn: suspend () -> BackupCounts = { localCounts },
        countsOf: (String) -> BackupCounts = { backupCounts },
        restore: suspend (String) -> RestoreResult = { RestoreResult(backupCounts, "/snap/pre-restore-1.json") },
        listSnapshots: () -> List<File> = { emptyList() },
        readSnapshot: (File) -> String = { "{}" },
        ensureFolder: suspend () -> String = { "folder-1" },
        uploadFile: suspend (String, String, String) -> DriveFile = { _, name, _ ->
            DriveFile(id = "file-1", name = name, createdTime = "2026-08-09T14:30:00Z", size = 100L)
        },
        listDriveFiles: suspend (String) -> List<DriveFile> = { emptyList() },
        downloadFile: suspend (String) -> String = { "{}" },
        prune: suspend (String, Int) -> Unit = { _, _ -> },
        token: suspend () -> String = { "token" },
        onAuthResult: (android.content.Intent?) -> Boolean = { true },
        invalidateAuth: () -> Unit = {},
        needsReconnectAtLoad: Boolean = false,
        setNeedsReconnect: (Boolean) -> Unit = {},
    ) = BackupViewModel(
        backup = backup,
        localCounts = localCountsFn,
        countsOf = countsOf,
        restore = restore,
        listSnapshots = listSnapshots,
        readSnapshot = readSnapshot,
        ensureFolder = ensureFolder,
        uploadFile = uploadFile,
        listDriveFiles = listDriveFiles,
        downloadFile = downloadFile,
        prune = prune,
        token = token,
        onAuthResult = onAuthResult,
        invalidateAuth = invalidateAuth,
        needsReconnectAtLoad = needsReconnectAtLoad,
        setNeedsReconnect = setNeedsReconnect,
        clock = fixedClock,
    )

    @Test
    fun `successful backup transitions to Ready with the uploaded file recorded`() = runTest {
        val vm = makeVm()

        vm.backupNow()

        val status = vm.state.value.status as? BackupUiState.Ready
        requireNotNull(status) { "expected Ready, got ${vm.state.value.status}" }
        assertEquals("file-1", status.lastBackup?.id)
        assertEquals(localCounts, status.localCounts)
    }

    @Test
    fun `DriveError NeedsAuth during backup becomes NeedsReconnect`() = runTest {
        var reconnectFlag = false
        val vm = makeVm(
            uploadFile = { _, _, _ -> throw DriveException(DriveError.NeedsAuth, "nope") },
            setNeedsReconnect = { reconnectFlag = it },
        )

        vm.backupNow()

        assertEquals(BackupUiState.NeedsReconnect, vm.state.value.status)
        assertTrue("needsReconnect persisted", reconnectFlag)
    }

    @Test
    fun `DriveError Network maps to the no-internet message`() = runTest {
        val vm = makeVm(
            uploadFile = { _, _, _ -> throw DriveException(DriveError.Network, "boom") },
        )

        vm.backupNow()

        val status = vm.state.value.status as? BackupUiState.Error
        requireNotNull(status)
        assertEquals("No internet connection.", status.message)
    }

    @Test
    fun `confirm dialog carries both local and backup counts`() = runTest {
        val file = DriveFile(id = "f1", name = "tdee-backup-x.json", createdTime = "2026-08-09T14:30:00Z", size = 50L)
        val vm = makeVm(
            downloadFile = { "{}" },
            countsOf = { backupCounts },
        )

        vm.selectDriveFile(file)

        val confirm = vm.state.value.confirm
        requireNotNull(confirm)
        assertEquals(localCounts, confirm.localCounts)
        assertEquals(backupCounts, confirm.backupCounts)
    }

    @Test
    fun `restore success returns to Ready with refreshed counts`() = runTest {
        val refreshedCounts = backupCounts
        val vm = makeVm(
            downloadFile = { "{}" },
            restore = { RestoreResult(refreshedCounts, "/snap/pre-restore-1.json") },
        )
        vm.selectDriveFile(DriveFile("f1", "name", "2026-08-09T14:30:00Z", 50L))
        requireNotNull(vm.state.value.confirm)

        vm.confirmRestore()

        val status = vm.state.value.status as? BackupUiState.Ready
        requireNotNull(status)
        assertEquals(refreshedCounts, status.localCounts)
        assertEquals(null, vm.state.value.confirm)
    }

    @Test
    fun `restore failure surfaces the message and the snapshot path`() = runTest {
        val snapshotFile = File("/data/backup/pre-restore-999.json")
        val vm = makeVm(
            downloadFile = { "{}" },
            restore = { throw IllegalStateException("disk full") },
            listSnapshots = { listOf(snapshotFile) },
        )
        vm.selectDriveFile(DriveFile("f1", "name", "2026-08-09T14:30:00Z", 50L))

        vm.confirmRestore()

        val status = vm.state.value.status as? BackupUiState.Error
        requireNotNull(status)
        assertTrue("carries the exception message", status.message.contains("disk full"))
        assertTrue("carries the snapshot path", status.message.contains(snapshotFile.absolutePath))
    }

    @Test
    fun `needsReconnect true at load starts in NeedsReconnect`() = runTest {
        val vm = makeVm(needsReconnectAtLoad = true)

        assertEquals(BackupUiState.NeedsReconnect, vm.state.value.status)
    }
}
