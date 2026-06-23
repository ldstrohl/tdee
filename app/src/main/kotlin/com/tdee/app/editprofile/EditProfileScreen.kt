package com.tdee.app.editprofile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tdee.app.onboarding.GoalSelection
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(viewModel: EditProfileViewModel, onDone: () -> Unit) {
    val form by viewModel.form.collectAsState()
    val saved by viewModel.saved.collectAsState()

    LaunchedEffect(saved) {
        if (saved) onDone()
    }

    if (form.loading) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Edit Profile", style = MaterialTheme.typography.headlineSmall)

        // Sex
        SectionLabel("Biological sex")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sex.entries.forEach { sex ->
                ElevatedFilterChip(
                    selected = form.sex == sex,
                    onClick = { viewModel.setSex(sex) },
                    label = { Text(sex.name.lowercase().replaceFirstChar { it.uppercaseChar() }) },
                )
            }
        }

        // Birth year
        OutlinedTextField(
            value = form.birthYear,
            onValueChange = viewModel::setBirthYear,
            label = { Text("Birth year (e.g. 1990)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Height
        SectionLabel("Height")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.heightFt,
                onValueChange = viewModel::setHeightFt,
                label = { Text("ft") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.heightIn,
                onValueChange = viewModel::setHeightIn,
                label = { Text("in") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        // Activity level
        SectionLabel("Activity level")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityLevel.entries.forEach { level ->
                ElevatedFilterChip(
                    selected = form.activityLevel == level,
                    onClick = { viewModel.setActivityLevel(level) },
                    label = {
                        Text(
                            level.name.lowercase().replace('_', ' ')
                                .replaceFirstChar { it.uppercaseChar() }
                        )
                    },
                )
            }
        }

        // Goal
        SectionLabel("Goal")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalSelection.entries.forEach { goal ->
                ElevatedFilterChip(
                    selected = form.goal == goal,
                    onClick = { viewModel.setGoal(goal) },
                    label = {
                        Text(goal.name.lowercase().replaceFirstChar { it.uppercaseChar() })
                    },
                )
            }
        }

        // Rate (only for CUT / BULK)
        if (form.goal != GoalSelection.MAINTAIN) {
            OutlinedTextField(
                value = form.goalRateLbPerWeek,
                onValueChange = viewModel::setGoalRateLbPerWeek,
                label = {
                    Text(
                        if (form.goal == GoalSelection.CUT) "Loss rate (lb/week)"
                        else "Gain rate (lb/week)"
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Optional: goal weight
        OutlinedTextField(
            value = form.goalWeightLb,
            onValueChange = viewModel::setGoalWeightLb,
            label = { Text("Goal weight (lb) — optional") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Optional macro overrides
        SectionLabel("Macro overrides (optional)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.proteinGPerKg,
                onValueChange = viewModel::setProteinGPerKg,
                label = { Text("Protein g/kg") },
                placeholder = { Text("2.0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.fatPct,
                onValueChange = viewModel::setFatPct,
                label = { Text("Fat (% of calories)") },
                placeholder = { Text("e.g. 30") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!form.canSave && form.missingRequiredFields.isNotEmpty()) {
            Text(
                "Still needed: ${form.missingRequiredFields.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = viewModel::save,
            enabled = form.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }

        TextButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}
