package com.tdee.app.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import com.tdee.app.BuildConfig
import com.tdee.app.TdeeApplication
import com.tdee.app.health.HEALTH_CONNECT_PERMISSIONS
import com.tdee.app.health.HealthConnectSyncWorker
import com.tdee.app.ui.theme.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Stateful host for the Settings screen.
 *
 * Owns the Health Connect orchestration: availability check, the runtime-permission
 * request (via [PermissionController.createRequestPermissionResultContract]), the
 * full-history pre-seed sync on first grant, manual "sync now", and enqueueing the
 * periodic worker once connected. All HC calls are wrapped so a missing/denied HC
 * never crashes the Settings screen.
 */
@Composable
fun SettingsRoute(
    current: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onMealParsing: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as TdeeApplication).container }
    val source = container.healthConnectSource
    val syncManager = container.healthConnectSyncManager
    val repository = container.repository
    val scope = rememberCoroutineScope()

    var hcState by remember {
        mutableStateOf<HealthConnectUiState>(HealthConnectUiState.Loading)
    }

    // Debug-only: confirmation/error text for the sample-weight writer.
    var debugWriteStatus by remember { mutableStateOf<String?>(null) }

    // Permission request contract. On the grant result we re-check and, if granted,
    // run the full-history pre-seed and start the periodic worker.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(HEALTH_CONNECT_PERMISSIONS)) {
            hcState = HealthConnectUiState.Working
            scope.launch {
                val imported = runCatching { syncManager.sync(fullHistory = true) }.getOrNull()
                HealthConnectSyncWorker.enqueue(context)
                hcState = HealthConnectUiState.Connected(lastImported = imported)
            }
        } else {
            hcState = HealthConnectUiState.NeedsPermission
        }
    }

    // Initial availability + permission probe when the screen appears.
    LaunchedEffect(Unit) {
        hcState = resolveInitialState(
            available = runCatching { source.isAvailable() }.getOrDefault(false),
            granted = runCatching { source.hasReadPermission() }.getOrDefault(false),
        )
        if (hcState is HealthConnectUiState.Connected) {
            HealthConnectSyncWorker.enqueue(context)
        }
    }

    SettingsScreen(
        current = current,
        onSelect = onSelect,
        onBack = onBack,
        onEditProfile = onEditProfile,
        onMealParsing = onMealParsing,
        healthConnectState = hcState,
        onExportData = {
            scope.launch {
                val csv = runCatching { repository.exportCsv() }.getOrNull()
                // A header-only CSV (no data rows) means nothing to export.
                val hasRows = csv != null && csv.trim().lines().size > 1
                if (csv == null || !hasRows) {
                    Toast.makeText(context, "No data to export yet", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val uri = runCatching {
                    val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
                    val stamp = java.time.LocalDate.now(ZoneId.systemDefault())
                        .format(DateTimeFormatter.BASIC_ISO_DATE) // YYYYMMDD
                    val file = File(exportDir, "tdee-export-$stamp.csv")
                    withContext(Dispatchers.IO) { file.writeText(csv) }
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                }.getOrNull()
                if (uri == null) {
                    Toast.makeText(context, "Couldn't prepare export", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Export TDEE data"))
            }
        },
        onHealthConnectTap = {
            when (hcState) {
                is HealthConnectUiState.Connected -> {
                    // Manual incremental "sync now".
                    hcState = HealthConnectUiState.Working
                    scope.launch {
                        val imported =
                            runCatching { syncManager.sync(fullHistory = false) }.getOrNull()
                        hcState = HealthConnectUiState.Connected(lastImported = imported)
                    }
                }
                HealthConnectUiState.NeedsPermission ->
                    permissionLauncher.launch(HEALTH_CONNECT_PERMISSIONS)
                is HealthConnectUiState.Unavailable, HealthConnectUiState.Loading -> {
                    // Re-probe availability (e.g. user just installed/updated HC).
                    hcState = HealthConnectUiState.Working
                    scope.launch {
                        val available =
                            runCatching { source.isAvailable() }.getOrDefault(false)
                        if (available) {
                            permissionLauncher.launch(HEALTH_CONNECT_PERMISSIONS)
                            hcState = HealthConnectUiState.NeedsPermission
                        } else {
                            hcState = unavailableState()
                        }
                    }
                }
                HealthConnectUiState.Working -> Unit
            }
        },
        debugWriteStatus = debugWriteStatus,
        onDebugWriteSampleWeights = if (BuildConfig.DEBUG) {
            {
                debugWriteStatus = "Writing sample weights…"
                scope.launch {
                    val result = runCatching { source.writeSampleWeights() }
                    debugWriteStatus = result.fold(
                        onSuccess = { n -> "wrote $n sample weights" },
                        onFailure = { e ->
                            "couldn't write samples: ${e.message ?: e.javaClass.simpleName}" +
                                " (is HC connected with write permission?)"
                        },
                    )
                }
            }
        } else {
            null
        },
    )
}

private fun resolveInitialState(available: Boolean, granted: Boolean): HealthConnectUiState =
    when {
        !available -> unavailableState()
        granted -> HealthConnectUiState.Connected(lastImported = null)
        else -> HealthConnectUiState.NeedsPermission
    }

private fun unavailableState() = HealthConnectUiState.Unavailable(
    "Health Connect isn't available. Install or update the Health Connect app, then tap to retry.",
)
