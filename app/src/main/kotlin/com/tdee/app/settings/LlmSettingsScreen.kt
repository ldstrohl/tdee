package com.tdee.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tdee.app.data.LlmProvider

/**
 * Bring-your-own-key meal-parsing settings: pick a provider + model and enter that provider's API
 * key (stored encrypted on-device). With no key, natural-language meal parsing is disabled and
 * manual entry is used instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsScreen(
    viewModel: LlmSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var keyVisible by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Meal parsing (AI)", style = MaterialTheme.typography.headlineSmall)
        }

        Text(
            "Describe a meal in plain language and your chosen AI estimates the items and macros. " +
                "The app calls the provider directly with your key — it's stored encrypted on this " +
                "device and never sent anywhere else. Without a key, you can still log meals manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Status banner: enabled vs disabled.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.hasKey) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Text(
                if (state.hasKey) {
                    "Meal parsing is enabled with ${state.provider.displayName}."
                } else {
                    "Add an API key below to enable meal parsing."
                },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text("Provider", style = MaterialTheme.typography.titleMedium)
        LlmProvider.entries.forEach { provider ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectProvider(provider) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(
                    selected = provider == state.provider,
                    onClick = { viewModel.selectProvider(provider) },
                )
                Text(provider.displayName, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Text("Model", style = MaterialTheme.typography.titleMedium)
        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = { modelMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = state.model,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                state.provider.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            viewModel.selectModel(model)
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }

        Text("API key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = viewModel::setKeyInput,
            label = {
                Text(if (state.hasKey) "Enter a new key to replace" else "Paste your API key")
            },
            singleLine = true,
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { keyVisible = !keyVisible }) {
                    Text(if (keyVisible) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = viewModel::saveKey,
                enabled = state.keyInput.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Save key")
            }
            if (state.hasKey) {
                OutlinedButton(
                    onClick = viewModel::removeKey,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Remove key")
                }
            }
        }

        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
