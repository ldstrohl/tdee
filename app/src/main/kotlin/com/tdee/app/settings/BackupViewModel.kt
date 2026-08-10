package com.tdee.app.settings

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.BackupCounts
import com.tdee.app.data.DriveError
import com.tdee.app.data.DriveException
import com.tdee.app.data.DriveFile
import com.tdee.app.data.NeedsAuthorizationException
import com.tdee.app.data.RestoreResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock

/** Top-level connection/activity state, mirrors [HealthConnectUiState]. */
sealed interface BackupUiState {
    data object Loading : BackupUiState
    data object Disconnected : BackupUiState
    data object NeedsReconnect : BackupUiState
    data class Ready(val lastBackup: DriveFile?, val localCounts: BackupCounts) : BackupUiState
    data class Working(val message: String) : BackupUiState
    data class Error(val message: String) : BackupUiState
}

/** One entry in the "pick a backup to restore" list — a Drive file or a device snapshot. */
sealed interface RestoreSource {
    data class Drive(val file: DriveFile) : RestoreSource
    data class Snapshot(val file: File) : RestoreSource
}

/** Confirm-dialog contents: both sides of the swap, spelled out in real numbers. */
data class RestoreConfirmUi(
    val source: RestoreSource,
    val json: String,
    val localCounts: BackupCounts,
    val backupCounts: BackupCounts,
)

/** Everything the screen renders: the top-level [status] plus any open picker/dialog. */
data class BackupScreenState(
    val status: BackupUiState = BackupUiState.Loading,
    val drivePicker: List<DriveFile>? = null,
    val snapshotPicker: List<File>? = null,
    val confirm: RestoreConfirmUi? = null,
    /** Non-null while the Drive consent screen needs to be launched by the route. */
    val authRequest: IntentSender? = null,
)

/**
 * Backup/restore screen logic. Every external effect (Room via [BackupManager], Drive REST via
 * [DriveClient], OAuth via [DriveAuth]) is taken as a plain function so this VM can be unit tested
 * with fakes with no Robolectric/MockWebServer/GMS dependency — see [Factory] for the real wiring.
 *
 * Drive calls can throw [NeedsAuthorizationException] (consent required) — the action that
 * triggered it is remembered as [pendingAction] and re-run once the route reports the consent
 * result via [onAuthorizationResult].
 */
class BackupViewModel(
    private val backup: suspend () -> String,
    private val localCounts: suspend () -> BackupCounts,
    private val countsOf: (String) -> BackupCounts,
    private val restore: suspend (String) -> RestoreResult,
    private val listSnapshots: () -> List<File>,
    private val readSnapshot: (File) -> String,
    private val ensureFolder: suspend () -> String,
    private val uploadFile: suspend (folderId: String, name: String, content: String) -> DriveFile,
    private val listDriveFiles: suspend (folderId: String) -> List<DriveFile>,
    private val downloadFile: suspend (fileId: String) -> String,
    private val prune: suspend (folderId: String, keep: Int) -> Unit,
    private val token: suspend () -> String,
    private val onAuthResult: (Intent?) -> Boolean,
    private val invalidateAuth: () -> Unit,
    needsReconnectAtLoad: Boolean,
    private val setNeedsReconnect: (Boolean) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private val _state = MutableStateFlow(
        BackupScreenState(
            status = if (needsReconnectAtLoad) BackupUiState.NeedsReconnect else BackupUiState.Disconnected,
        ),
    )
    val state: StateFlow<BackupScreenState> = _state.asStateFlow()

    private var folderId: String? = null
    private var lastBackupFile: DriveFile? = null
    private var pendingAction: (suspend () -> Unit)? = null

    fun connectDrive() = runAuthed { doConnect() }

    fun backupNow() = runAuthed { doBackup() }

    fun openDrivePicker() = runAuthed { doOpenDrivePicker() }

    fun selectDriveFile(file: DriveFile) = runAuthed { doOpenConfirm(RestoreSource.Drive(file), downloadFile(file.id)) }

    fun openSnapshotPicker() {
        _state.update { it.copy(snapshotPicker = listSnapshots()) }
    }

    fun selectSnapshot(file: File) {
        viewModelScope.launch {
            runCatching { doOpenConfirm(RestoreSource.Snapshot(file), readSnapshot(file)) }
                .onFailure(::handleFailure)
        }
    }

    fun dismissDrivePicker() = _state.update { it.copy(drivePicker = null) }

    fun dismissSnapshotPicker() = _state.update { it.copy(snapshotPicker = null) }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    fun confirmRestore() {
        val confirm = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        viewModelScope.launch {
            setStatus(BackupUiState.Working("Restoring…"))
            runCatching { restore(confirm.json) }
                .onSuccess { result ->
                    setStatus(BackupUiState.Ready(lastBackup = lastBackupFile, localCounts = result.counts))
                }
                .onFailure { e ->
                    // BackupManager writes the pre-restore snapshot before touching the DB, so on a
                    // mid-restore failure the newest snapshot on disk is the safety copy just made.
                    val snapshotPath = listSnapshots().firstOrNull()?.absolutePath
                    val message = e.message ?: "Restore failed."
                    setStatus(
                        BackupUiState.Error(
                            if (snapshotPath != null) {
                                "$message Your previous data was saved to $snapshotPath."
                            } else {
                                message
                            },
                        ),
                    )
                }
        }
    }

    /**
     * Called by the route after the Drive consent activity returns. On success, re-runs the action
     * that originally triggered the consent request (e.g. "Back up now" resumes after connecting).
     */
    fun onAuthorizationResult(intent: Intent?) {
        _state.update { it.copy(authRequest = null) }
        if (!onAuthResult(intent)) {
            pendingAction = null
            setStatus(BackupUiState.Error("Couldn't connect to Google Drive."))
            return
        }
        val action = pendingAction
        if (action != null) {
            viewModelScope.launch {
                runCatching { action() }
                    .onSuccess { pendingAction = null }
                    .onFailure(::handleFailure)
            }
        }
    }

    // -------------------------------------------------------------------

    private suspend fun doConnect() {
        token()
        setNeedsReconnect(false)
        refreshReady()
    }

    private suspend fun doBackup() {
        setStatus(BackupUiState.Working("Backing up…"))
        val json = backup()
        val folder = folderId ?: ensureFolder().also { folderId = it }
        val name = "tdee-backup-${clock.instant().toString().replace(':', '-')}.json"
        lastBackupFile = uploadFile(folder, name, json)
        prune(folder, 10)
        refreshReady()
    }

    private suspend fun doOpenDrivePicker() {
        setStatus(BackupUiState.Working("Loading backups…"))
        val folder = folderId ?: ensureFolder().also { folderId = it }
        val files = listDriveFiles(folder)
        _state.update { it.copy(drivePicker = files) }
        refreshReady()
    }

    private suspend fun doOpenConfirm(source: RestoreSource, json: String) {
        val backupCounts = countsOf(json)
        val local = localCounts()
        _state.update {
            it.copy(
                confirm = RestoreConfirmUi(source, json, local, backupCounts),
                drivePicker = null,
                snapshotPicker = null,
            )
        }
        refreshReady()
    }

    private suspend fun refreshReady() {
        setStatus(BackupUiState.Ready(lastBackup = lastBackupFile, localCounts = localCounts()))
    }

    /** Runs [action], launching the Drive consent flow on [NeedsAuthorizationException] and
     * remembering [action] so it can be resumed from [onAuthorizationResult]. */
    private fun runAuthed(action: suspend () -> Unit) {
        pendingAction = action
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { pendingAction = null }
                .onFailure(::handleFailure)
        }
    }

    private fun handleFailure(e: Throwable) {
        when (e) {
            is NeedsAuthorizationException -> _state.update { it.copy(authRequest = e.intentSender) }
            is DriveException -> {
                pendingAction = null
                if (e.error is DriveError.NeedsAuth) {
                    invalidateAuth()
                    setNeedsReconnect(true)
                    setStatus(BackupUiState.NeedsReconnect)
                } else {
                    setStatus(BackupUiState.Error(mapDriveError(e.error)))
                }
            }
            else -> {
                pendingAction = null
                setStatus(BackupUiState.Error(e.message ?: "Something went wrong."))
            }
        }
    }

    private fun mapDriveError(error: DriveError): String = when (error) {
        DriveError.NeedsAuth -> "Google Drive access expired. Reconnect to continue."
        DriveError.Network -> "No internet connection."
        DriveError.RateLimited -> "Google Drive is busy, try again in a minute."
        DriveError.Server -> "Google Drive is having problems, try again later."
        is DriveError.Unknown -> error.message
    }

    private fun setStatus(status: BackupUiState) = _state.update { it.copy(status = status) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                val container = app.container
                val bm = container.backupManager
                val dc = container.driveClient
                val da = container.driveAuth
                BackupViewModel(
                    backup = { bm.backup() },
                    localCounts = { bm.localCounts() },
                    countsOf = { json -> bm.countsOf(json) },
                    restore = { json -> bm.restore(json) },
                    listSnapshots = { bm.listSnapshots() },
                    readSnapshot = { file -> file.readText() },
                    ensureFolder = { dc.ensureFolder() },
                    uploadFile = { folderId, name, content -> dc.upload(folderId, name, content) },
                    listDriveFiles = { folderId -> dc.list(folderId) },
                    downloadFile = { fileId -> dc.download(fileId) },
                    prune = { folderId, keep -> dc.prune(folderId, keep) },
                    token = { da.token() },
                    onAuthResult = { intent -> da.onAuthorizationResult(intent) },
                    invalidateAuth = { da.invalidate() },
                    needsReconnectAtLoad = da.needsReconnect,
                    setNeedsReconnect = { v -> da.needsReconnect = v },
                )
            }
        }
    }
}
