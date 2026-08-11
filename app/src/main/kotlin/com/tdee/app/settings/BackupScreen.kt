package com.tdee.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdee.app.BuildConfig
import com.tdee.app.data.BackupCounts
import com.tdee.app.data.DriveFile
import java.io.File

/**
 * Backup & restore settings screen. Stateless — [BackupRoute] owns the VM and the Drive consent
 * launcher.
 *
 * Restore is gated behind [BuildConfig.DEBUG] for the first release: the first build going onto
 * the user's real phone should be able to back up but not restore. Both restore entry points
 * (Drive + device snapshot) are wrapped below — remove the `if (BuildConfig.DEBUG)` guards once
 * restore has emulator sign-off.
 */
@Composable
fun BackupScreen(
    state: BackupScreenState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onBackupNow: () -> Unit,
    onOpenDrivePicker: () -> Unit,
    onSelectDriveFile: (DriveFile) -> Unit,
    onDismissDrivePicker: () -> Unit,
    onOpenSnapshotPicker: () -> Unit,
    onSelectSnapshot: (File) -> Unit,
    onDismissSnapshotPicker: () -> Unit,
    onDismissConfirm: () -> Unit,
    onConfirmRestore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Backup & restore", style = MaterialTheme.typography.headlineSmall)
        }

        Text(
            "Back up your logged history to Google Drive so you can restore it on a new device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val status = state.status
        when (status) {
            is BackupUiState.Loading -> {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }

            is BackupUiState.Disconnected -> ConnectRow(onConnect)

            is BackupUiState.NeedsReconnect -> {
                Text(
                    "Google Drive access expired. Reconnect to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                ConnectRow(onConnect, label = "Reconnect Google Drive")
            }

            is BackupUiState.Working -> {
                Text(status.message, style = MaterialTheme.typography.bodyMedium)
            }

            is BackupUiState.Error -> {
                Text(
                    status.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                ConnectRow(onConnect, label = "Try again")
            }

            is BackupUiState.Ready -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            status.lastBackup?.let { "Last backup: ${it.name}" }
                                ?: "No backup yet this session.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "On this device: ${countsSummary(status.localCounts)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SettingsEntry(
                    title = "Back up now",
                    subtitle = "Upload a snapshot of your data to Google Drive",
                    onClick = onBackupNow,
                )

                // ponytail: restore gated for the first release — see the guard note in the
                // function doc above. Remove both `if (BuildConfig.DEBUG)` blocks after sign-off.
                if (BuildConfig.DEBUG) {
                    SettingsEntry(
                        title = "Restore from Drive",
                        subtitle = "Replace this device's data with a backup from Drive",
                        onClick = onOpenDrivePicker,
                    )
                }
            }
        }

        if (BuildConfig.DEBUG) {
            SettingsEntry(
                title = "Restore from device snapshot",
                subtitle = "Recovery path if a Drive restore goes wrong",
                onClick = onOpenSnapshotPicker,
            )
        }
    }

    state.drivePicker?.let { files ->
        PickerDialog(
            title = "Restore from Drive",
            items = files,
            itemLabel = { "${it.name}  ·  ${it.createdTime}${it.size?.let { s -> "  ·  ${sizeLabel(s)}" } ?: ""}" },
            onSelect = onSelectDriveFile,
            onDismiss = onDismissDrivePicker,
            empty = "No backups found in Drive yet.",
        )
    }

    state.snapshotPicker?.let { files ->
        PickerDialog(
            title = "Restore from device snapshot",
            items = files,
            itemLabel = { it.name },
            onSelect = onSelectSnapshot,
            onDismiss = onDismissSnapshotPicker,
            empty = "No snapshots on this device.",
        )
    }

    state.confirm?.let { confirm ->
        RestoreConfirmDialog(confirm, onDismiss = onDismissConfirm, onConfirm = onConfirmRestore)
    }
}

@Composable
private fun ConnectRow(onConnect: () -> Unit, label: String = "Connect Google Drive") {
    SettingsEntry(
        title = label,
        // The scope is drive.file, so this app can only ever see files it created itself — but the
        // folder is a normal visible one, deliberately, so backups can be inspected and recovered
        // by hand. Don't call it "private": that would describe the hidden appDataFolder instead.
        subtitle = "Creates a \"TDEE Backups\" folder in your Drive. This app can only see files it creates.",
        onClick = onConnect,
    )
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> PickerDialog(
    title: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    empty: String,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (items.isEmpty()) {
                    Text(empty, style = MaterialTheme.typography.bodyMedium)
                }
                items.forEach { item ->
                    Text(
                        itemLabel(item),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The last guard before irreversible data loss: states both sides in real numbers, breaks out
 * each row type, and makes the destructive action ("Replace my data") the non-default button so
 * an accidental tap on the dismiss button is the safe outcome.
 */
@Composable
private fun RestoreConfirmDialog(
    confirm: RestoreConfirmUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val local = confirm.localCounts
    val backup = confirm.backupCounts
    val sourceLabel = when (val source = confirm.source) {
        is RestoreSource.Drive -> "the backup of ${source.file.createdTime}"
        is RestoreSource.Snapshot -> "the device snapshot ${source.file.name}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace your data?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Replace ${entryCount(totalEntries(local))} on this device with " +
                        "${entryCount(totalEntries(backup))} from $sourceLabel?",
                )
                Text(
                    "This device                Backup\n" +
                        "Food entries:   ${local.foods}   →   ${backup.foods}\n" +
                        "Weigh-ins:      ${local.weights}   →   ${backup.weights}\n" +
                        "Saved meals:    ${local.savedMeals}   →   ${backup.savedMeals}\n" +
                        "Targets:        ${local.targets}   →   ${backup.targets}\n" +
                        "Profile:        ${yesNo(local.hasProfile)}   →   ${yesNo(backup.hasProfile)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "A safety copy of this device's current data is saved locally before anything " +
                        "is replaced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        // Dismiss is the visually prominent default; confirm is the deliberate, named destructive action.
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Replace my data") }
        },
    )
}

private fun totalEntries(c: BackupCounts): Int = c.foods + c.weights + c.savedMeals + c.targets

/** "1 entry" / "12 entries" — this string sits in the dialog that precedes irreversible loss. */
private fun entryCount(n: Int): String = if (n == 1) "1 entry" else "$n entries"

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

private fun countsSummary(c: BackupCounts): String =
    "${c.foods} food entries, ${c.weights} weigh-ins, ${c.savedMeals} saved meals, ${c.targets} targets"

private fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
