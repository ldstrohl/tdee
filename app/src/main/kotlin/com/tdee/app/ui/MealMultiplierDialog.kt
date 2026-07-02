package com.tdee.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val PRESET_FACTORS = listOf(0.5, 1.0, 1.5, 2.0)

/**
 * Prompts for a positive scale factor to apply when (re-)logging a meal — e.g. 1.5x = "50% more
 * than the estimate". Defaults to 1.0 (no change). [onConfirm] receives the chosen factor;
 * [onDismiss] cancels without logging.
 *
 * Shared by every meal-logging path that lets the user scale quantities: saved-meal "Log" and
 * "Repeat" (meal or entry).
 */
@Composable
fun MealMultiplierDialog(
    onConfirm: (factor: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("1") }
    val factor = text.toDoubleOrNull()
    val isValid = factor != null && factor > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scale this meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESET_FACTORS.forEach { preset ->
                        val label = presetLabel(preset)
                        FilterChip(
                            selected = text == label,
                            onClick = { text = label },
                            label = { Text("${label}×") },
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Multiplier") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = !isValid,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { factor?.let(onConfirm) }, enabled = isValid) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun presetLabel(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
