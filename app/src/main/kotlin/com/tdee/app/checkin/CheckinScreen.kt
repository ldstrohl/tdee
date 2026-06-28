package com.tdee.app.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tdee.app.data.CheckinProposal
import com.tdee.domain.Macro
import kotlin.math.abs

@Composable
fun CheckinScreen(
    viewModel: CheckinViewModel,
    onDone: () -> Unit,
) {
    val proposal by viewModel.proposal.collectAsState()
    val form by viewModel.form.collectAsState()
    val saved by viewModel.saved.collectAsState()

    LaunchedEffect(saved) {
        if (saved) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Check-in", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onDone) { Text("Back") }
        }

        val p = proposal
        if (p == null) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        } else {
            SummaryCard(p)
            TargetsSection(p, form, viewModel)

            Button(
                onClick = viewModel::accept,
                enabled = form.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save targets")
            }
            Text(
                "Accept the proposed targets as-is, or edit any value before saving. " +
                    "Changes take effect immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCard(p: CheckinProposal) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "%,d kcal".format(p.tdeeKcal.toInt()),
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (p.calibrating) {
                    Text(
                        text = "Calibrating",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Text(
                "Current TDEE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SummaryRow(
                "7-day avg intake",
                p.last7AvgIntakeKcal?.let { "%,d kcal".format(it.toInt()) } ?: "—",
            )
            SummaryRow(
                "Weight trend (7 days)",
                "%+.1f lb".format(p.trendChangeLb),
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TargetsSection(
    p: CheckinProposal,
    form: CheckinFormState,
    viewModel: CheckinViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Targets", style = MaterialTheme.typography.titleMedium)
            Text(
                "Edit a macro and the others rebalance to keep the calorie target. " +
                    "Lock a macro to hold it while the rest adjust.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TargetField(
                label = "Calories (kcal)",
                current = p.currentTargets?.let { "%,d".format(it.calorieTargetKcal.toInt()) },
                proposed = "%,d".format(p.proposedTargets.calorieTargetKcal.toInt()),
                value = form.calorieKcal,
                onValueChange = viewModel::setCalorie,
            )
            TargetField(
                label = "Protein (g)",
                current = p.currentTargets?.let { "%,d".format(it.proteinG.toInt()) },
                proposed = "%,d".format(p.proposedTargets.proteinG.toInt()),
                value = form.proteinG,
                onValueChange = viewModel::setProtein,
                locked = Macro.PROTEIN in form.locked,
                onToggleLock = { viewModel.toggleLock(Macro.PROTEIN) },
            )
            TargetField(
                label = "Fat (g)",
                current = p.currentTargets?.let { "%,d".format(it.fatG.toInt()) },
                proposed = "%,d".format(p.proposedTargets.fatG.toInt()),
                value = form.fatG,
                onValueChange = viewModel::setFat,
                locked = Macro.FAT in form.locked,
                onToggleLock = { viewModel.toggleLock(Macro.FAT) },
            )
            TargetField(
                label = "Carbs (g)",
                current = p.currentTargets?.let { "%,d".format(it.carbG.toInt()) },
                proposed = "%,d".format(p.proposedTargets.carbG.toInt()),
                value = form.carbG,
                onValueChange = viewModel::setCarb,
                locked = Macro.CARB in form.locked,
                onToggleLock = { viewModel.toggleLock(Macro.CARB) },
            )

            HorizontalDivider()

            val macrosKcal = form.macrosKcal
            val calTarget = form.calorieValue
            SummaryRow(
                "Macros total",
                macrosKcal?.let { "%,d kcal".format(it.toInt()) } ?: "—",
            )
            // Over-constrained (e.g. two locked + the third edited): macros no longer sum to the
            // target. Surface the implied kcal and offer to update the calorie target — never
            // override it silently.
            if (macrosKcal != null && calTarget != null && abs(macrosKcal - calTarget) > 10) {
                Text(
                    "These macros add up to ${macrosKcal.toInt()} kcal, " +
                        "not the ${calTarget.toInt()} kcal target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = viewModel::alignCaloriesToMacros) {
                    Text("Set calories to ${macrosKcal.toInt()}")
                }
            }
        }
    }
}

@Composable
private fun TargetField(
    label: String,
    current: String?,
    proposed: String,
    value: String,
    onValueChange: (String) -> Unit,
    locked: Boolean? = null,
    onToggleLock: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Current: ${current ?: "—"}  →  Proposed: $proposed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                readOnly = locked == true,
                isError = value.toDoubleOrNull()?.let { it < 0.0 } ?: true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (locked != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Checkbox(checked = locked, onCheckedChange = { onToggleLock() })
                    Text("Lock", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
