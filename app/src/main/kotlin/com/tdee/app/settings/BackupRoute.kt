package com.tdee.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tdee.app.data.DriveBackupWorker

/**
 * Stateful host for the Backup screen. Owns the Drive consent launcher — [BackupViewModel] can't
 * hold Android `Activity` result types itself, so it surfaces the pending [android.content.IntentSender]
 * via [BackupScreenState.authRequest] and this route launches it, feeding the result back in.
 */
@Composable
fun BackupRoute(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory)
    val state by viewModel.state.collectAsState()

    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onAuthorizationResult(result.data)
    }

    LaunchedEffect(state.authRequest) {
        state.authRequest?.let { sender ->
            authLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    LaunchedEffect(state.status) {
        if (state.status is BackupUiState.Ready) {
            DriveBackupWorker.enqueue(context)
        }
    }

    BackupScreen(
        state = state,
        onBack = onBack,
        onConnect = viewModel::connectDrive,
        onBackupNow = viewModel::backupNow,
        onOpenDrivePicker = viewModel::openDrivePicker,
        onSelectDriveFile = viewModel::selectDriveFile,
        onDismissDrivePicker = viewModel::dismissDrivePicker,
        onOpenSnapshotPicker = viewModel::openSnapshotPicker,
        onSelectSnapshot = viewModel::selectSnapshot,
        onDismissSnapshotPicker = viewModel::dismissSnapshotPicker,
        onDismissConfirm = viewModel::dismissConfirm,
        onConfirmRestore = viewModel::confirmRestore,
    )
}
