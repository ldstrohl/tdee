package com.tdee.app.data

import android.content.IntentSender
import androidx.work.ListenableWorker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for the worker's pure backup-delegation logic ([DriveBackupWorker.runBackup]).
 *
 * The real credential/Drive I/O is verified on-device; here we only assert that successful
 * backups and network errors map to the correct [ListenableWorker.Result] states, and that
 * authorization failures set the reconnect flag and return failure (not retry).
 */
class DriveBackupWorkerTest {

    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-08-09T14:30:00Z"), ZoneOffset.UTC)

    private fun stubIntentSender(): IntentSender =
        ReflectionHelpers.callConstructor(IntentSender::class.java)

    @Test
    fun `runBackup succeeds and calls upload then prune with correct arguments`() = runTest {
        val backupJson = "{\"version\":1}"
        var folderId: String? = null
        var uploadedFilename: String? = null
        var uploadedJson: String? = null
        var pruneFolder: String? = null
        var reconnectFlag = false

        val result = DriveBackupWorker.runBackup(
            backup = { backupJson },
            ensureFolder = { "folder-123".also { folderId = it } },
            upload = { folder, name, json ->
                uploadedFilename = name
                uploadedJson = json
            },
            prune = { folder -> pruneFolder = folder },
            setNeedsReconnect = { reconnectFlag = it },
            clock = fixedClock,
        )

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("folder-123", folderId)
        assertEquals("tdee-backup-2026-08-09T14-30-00Z.json", uploadedFilename)
        assertEquals(backupJson, uploadedJson)
        assertEquals("folder-123", pruneFolder)
        assertTrue("reconnect flag should not be set on success", !reconnectFlag)
    }

    @Test
    fun `runBackup returns failure and sets flag on DriveException NeedsAuth from upload`() = runTest {
        var reconnectFlag = false

        val result = DriveBackupWorker.runBackup(
            backup = { "{}" },
            ensureFolder = { "folder-1" },
            upload = { _, _, _ -> throw DriveException(DriveError.NeedsAuth, "revoked") },
            prune = { },
            setNeedsReconnect = { reconnectFlag = it },
            clock = fixedClock,
        )

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue("needsReconnect should be set", reconnectFlag)
    }

    @Test
    fun `runBackup returns failure and sets flag on NeedsAuthorizationException from backup`() = runTest {
        var reconnectFlag = false

        val result = DriveBackupWorker.runBackup(
            backup = { throw NeedsAuthorizationException(stubIntentSender()) },
            ensureFolder = { "folder-1" },
            upload = { _, _, _ -> },
            prune = { },
            setNeedsReconnect = { reconnectFlag = it },
            clock = fixedClock,
        )

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue("needsReconnect should be set", reconnectFlag)
    }

    @Test
    fun `runBackup returns failure and sets flag on NeedsAuthorizationException from token`() = runTest {
        var reconnectFlag = false

        val result = DriveBackupWorker.runBackup(
            backup = { "{}" },
            ensureFolder = { throw NeedsAuthorizationException(stubIntentSender()) },
            upload = { _, _, _ -> },
            prune = { },
            setNeedsReconnect = { reconnectFlag = it },
            clock = fixedClock,
        )

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue("needsReconnect should be set", reconnectFlag)
    }

    @Test
    fun `runBackup returns retry on DriveException Network`() = runTest {
        var reconnectFlag = false

        val result = DriveBackupWorker.runBackup(
            backup = { "{}" },
            ensureFolder = { "folder-1" },
            upload = { _, _, _ -> throw DriveException(DriveError.Network, "no internet") },
            prune = { },
            setNeedsReconnect = { reconnectFlag = it },
            clock = fixedClock,
        )

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue("reconnect flag should not be set on transient error", !reconnectFlag)
    }

    @Test
    fun `runBackup returns retry on DriveException RateLimited`() = runTest {
        var reconnectFlag = false

        val result = DriveBackupWorker.runBackup(
            backup = { "{}" },
            ensureFolder = { "folder-1" },
            upload = { _, _, _ -> throw DriveException(DriveError.RateLimited, "rate limited") },
            prune = { },
            setNeedsReconnect = { reconnectFlag = it },
            clock = fixedClock,
        )

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue("reconnect flag should not be set on transient error", !reconnectFlag)
    }

    @Test
    fun `runBackup returns retry on DriveException Server`() = runTest {
        var reconnectFlag = false

        val result = DriveBackupWorker.runBackup(
            backup = { "{}" },
            ensureFolder = { "folder-1" },
            upload = { _, _, _ -> throw DriveException(DriveError.Server, "server error") },
            prune = { },
            setNeedsReconnect = { reconnectFlag = it },
            clock = fixedClock,
        )

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue("reconnect flag should not be set on transient error", !reconnectFlag)
    }

    @Test
    fun `runBackup returns retry on DriveException Unknown`() = runTest {
        var reconnectFlag = false

        val result = DriveBackupWorker.runBackup(
            backup = { "{}" },
            ensureFolder = { "folder-1" },
            upload = { _, _, _ -> throw DriveException(DriveError.Unknown("weird"), "weird") },
            prune = { },
            setNeedsReconnect = { reconnectFlag = it },
            clock = fixedClock,
        )

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue("reconnect flag should not be set on transient error", !reconnectFlag)
    }

    @Test
    fun `runBackup returns retry on generic RuntimeException`() = runTest {
        var reconnectFlag = false

        val result = DriveBackupWorker.runBackup(
            backup = { throw RuntimeException("something broke") },
            ensureFolder = { "folder-1" },
            upload = { _, _, _ -> },
            prune = { },
            setNeedsReconnect = { reconnectFlag = it },
            clock = fixedClock,
        )

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue("reconnect flag should not be set on generic error", !reconnectFlag)
    }
}
