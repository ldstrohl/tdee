package com.tdee.app.addfood

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tdee.app.data.Macro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParseConfirmScreen(
    viewModel: ParseConfirmViewModel,
    onDone: () -> Unit,
    autoScan: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val mealSaved by viewModel.mealSaved.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()

    LaunchedEffect(saved) {
        if (saved) onDone()
    }

    val context = LocalContext.current
    var barcodeInput by remember { mutableStateOf("") }

    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri
        if (!ok || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { LabelPhoto.readDownscaledJpeg(context, uri) }
            if (bytes == null) viewModel.labelPhotoFailed() else viewModel.parseLabel(bytes)
        }
    }

    fun launchScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
            )
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(viewModel::lookupBarcode) }
            .addOnCanceledListener { /* user backed out — no message */ }
            .addOnFailureListener { viewModel.scannerFailed() }
    }

    LaunchedEffect(Unit) {
        if (autoScan && viewModel.scanningAvailable) launchScanner()
    }

    val today = remember { LocalDate.now() }
    var showDatePicker by remember { mutableStateOf(false) }
    var showSaveAsMealDialog by remember { mutableStateOf(false) }
    var mealName by remember { mutableStateOf("") }
    var mealNameInput by remember { mutableStateOf(state.text) }
    var mealNameInputEdited by remember { mutableStateOf(false) }
    LaunchedEffect(state.text) {
        if (!mealNameInputEdited && mealNameInput.isBlank() && state.text.isNotBlank()) {
            mealNameInput = state.text
        }
    }
    LaunchedEffect(state.mealName) {
        val llmName = state.mealName
        if (!mealNameInputEdited && !llmName.isNullOrBlank()) mealNameInput = llmName
    }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = Instant.ofEpochMilli(utcTimeMillis)
                    .atZone(ZoneOffset.UTC).toLocalDate()
                return !date.isAfter(today)
            }
        },
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        viewModel.setSelectedDate(picked)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showSaveAsMealDialog) {
        AlertDialog(
            onDismissRequest = { showSaveAsMealDialog = false; mealName = "" },
            title = { Text("Save meal & add") },
            text = {
                OutlinedTextField(
                    value = mealName,
                    onValueChange = { mealName = it },
                    label = { Text("Meal name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveMealAndAdd(mealName)
                        showSaveAsMealDialog = false
                        mealName = ""
                    },
                    enabled = mealName.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAsMealDialog = false; mealName = "" }) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.appendMode) "Add to meal" else "Describe a meal",
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onDone) { Text("Back") }
        }

        // Date selector — defaults to today; tap to log to a prior day. Hidden in append mode:
        // appended items always land on the target meal's existing log-day.
        if (!state.appendMode) {
            val dateLabel = if (selectedDate == today) "Today" else
                selectedDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Logging for: $dateLabel")
            }
        }

        // The two camera affordances sit side by side; manual barcode entry is the fallback below
        // them. "Photograph label" is not gated on scanningAvailable, which is about the Open Food
        // Facts lookup that the label path does not use.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (viewModel.scanningAvailable) {
                Button(
                    onClick = { launchScanner() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scan barcode")
                }
            }
            OutlinedButton(
                onClick = {
                    try {
                        val uri = LabelPhoto.newCaptureUri(context)
                        captureUri = uri
                        takePicture.launch(uri)
                    } catch (_: IOException) {
                        viewModel.labelPhotoFailed()
                    }
                },
                enabled = !state.parsing,
                modifier = Modifier.weight(1f),
            ) {
                Text("Photograph label")
            }
        }

        if (viewModel.scanningAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = barcodeInput,
                    onValueChange = { barcodeInput = it.filter(Char::isDigit) },
                    label = { Text("Enter barcode") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { viewModel.lookupBarcode(barcodeInput) }) {
                    Text("Look up")
                }
            }
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::setText,
            label = { Text("Describe your meal, e.g. '2 eggs and oatmeal'") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = viewModel::parse,
            enabled = state.text.isNotBlank() && !state.parsing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Parse")
        }

        Text(
            "Review and fill in the numbers for each item, then save.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.parseError?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        if (state.items.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Meal totals", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "%,d kcal · P %d g · F %d g · C %d g".format(
                            state.totalKcal.toInt(),
                            state.totalProteinG.toInt(),
                            state.totalFatG.toInt(),
                            state.totalCarbG.toInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        state.items.forEachIndexed { index, item ->
            ItemCard(
                item = item,
                onName = { viewModel.setName(index, it) },
                onKcal = { viewModel.setKcal(index, it) },
                onProtein = { viewModel.setProteinG(index, it) },
                onFat = { viewModel.setFatG(index, it) },
                onCarb = { viewModel.setCarbG(index, it) },
                onGrams = { viewModel.setGrams(index, it) },
                onFactor = { viewModel.setFactor(index, it) },
                onRemove = { viewModel.removeItem(index) },
            )
        }

        OutlinedButton(
            onClick = viewModel::addItem,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add item")
        }

        if (!state.appendMode) {
            OutlinedTextField(
                value = mealNameInput,
                onValueChange = { mealNameInput = it; mealNameInputEdited = true },
                label = { Text("Meal name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (mealSaved) {
                Text(
                    "Meal saved to library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        state.mealSaveError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (!state.appendMode) {
            OutlinedButton(
                onClick = { showSaveAsMealDialog = true },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save meal & add")
            }
        }

        if (state.appendMode) {
            Button(
                onClick = { viewModel.saveAll() },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add to meal")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::saveAllIndividually,
                    enabled = state.canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Add items")
                }
                Button(
                    onClick = { viewModel.saveAll(mealNameInput) },
                    enabled = state.canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Add as meal")
                }
            }
        }
    }
}

/** Short, understated note for macros the row estimated rather than read off a label. */
private fun estimatedMacroCaption(estimated: Set<Macro>): String? {
    if (estimated.isEmpty()) return null
    val names = estimated.sortedBy { it.ordinal }.joinToString(", ") { macro ->
        when (macro) {
            Macro.PROTEIN -> "Protein"
            Macro.FAT -> "Fat"
            Macro.CARB -> "Carbs"
        }
    }
    return "$names estimated — not on the label"
}

@Composable
private fun ItemCard(
    item: EditableFoodItem,
    onName: (String) -> Unit,
    onKcal: (String) -> Unit,
    onProtein: (String) -> Unit,
    onFat: (String) -> Unit,
    onCarb: (String) -> Unit,
    onGrams: (String) -> Unit,
    onFactor: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Item", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onRemove) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }

            OutlinedTextField(
                value = item.name,
                onValueChange = onName,
                label = { Text("Food name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            estimatedMacroCaption(item.estimatedMacros)?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = item.kcal,
                onValueChange = onKcal,
                label = { Text("Calories (kcal)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = item.proteinG,
                    onValueChange = onProtein,
                    label = { Text("Protein (g)") },
                    placeholder = { Text("0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = item.fatG,
                    onValueChange = onFat,
                    label = { Text("Fat (g)") },
                    placeholder = { Text("0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = item.carbG,
                    onValueChange = onCarb,
                    label = { Text("Carbs (g)") },
                    placeholder = { Text("0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = item.grams,
                    onValueChange = onGrams,
                    label = { Text("Serving (g)") },
                    placeholder = { Text("—") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = item.factor,
                    onValueChange = onFactor,
                    label = { Text("×") },
                    placeholder = { Text("1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
