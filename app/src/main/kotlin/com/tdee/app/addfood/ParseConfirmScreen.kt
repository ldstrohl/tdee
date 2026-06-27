package com.tdee.app.addfood

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
fun ParseConfirmScreen(
    viewModel: ParseConfirmViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val saved by viewModel.saved.collectAsState()

    LaunchedEffect(saved) {
        if (saved) onDone()
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
        Text("Describe a meal", style = MaterialTheme.typography.headlineSmall)

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
                onRemove = { viewModel.removeItem(index) },
            )
        }

        OutlinedButton(
            onClick = viewModel::addItem,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add item")
        }

        Button(
            onClick = viewModel::saveAll,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save all")
        }
    }
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
            }
        }
    }
}
