package com.tdee.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tdee.app.TdeeApplication
import com.tdee.app.data.LlmProvider
import com.tdee.app.data.LlmSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI state for the bring-your-own-key LLM settings screen.
 *
 * The stored key is never shown back (only [hasKey]); [keyInput] is the field the user types a new
 * key into.
 */
data class LlmSettingsUiState(
    val provider: LlmProvider,
    val model: String,
    val hasKey: Boolean,
    val keyInput: String = "",
    val message: String? = null,
)

/**
 * Reads and writes the user's LLM configuration via [LlmSettingsStore].
 *
 * The store is synchronous and not reactive, so this VM mirrors it into a [StateFlow] and updates
 * imperatively on each action. Selecting a provider switches the visible model + key status to that
 * provider's stored values.
 */
class LlmSettingsViewModel(
    private val store: LlmSettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot(store.provider))
    val state: StateFlow<LlmSettingsUiState> = _state.asStateFlow()

    private fun snapshot(p: LlmProvider, keyInput: String = "", message: String? = null) =
        LlmSettingsUiState(
            provider = p,
            model = store.modelFor(p),
            hasKey = store.hasKey(p),
            keyInput = keyInput,
            message = message,
        )

    fun selectProvider(p: LlmProvider) {
        store.setProvider(p)
        _state.value = snapshot(p)
    }

    fun selectModel(model: String) {
        val p = _state.value.provider
        store.setModel(p, model)
        _state.value = _state.value.copy(model = model, message = null)
    }

    fun setKeyInput(v: String) {
        _state.value = _state.value.copy(keyInput = v, message = null)
    }

    fun saveKey() {
        val p = _state.value.provider
        val key = _state.value.keyInput.trim()
        if (key.isBlank()) return
        store.setKey(p, key)
        _state.value = snapshot(p, message = "API key saved.")
    }

    fun removeKey() {
        val p = _state.value.provider
        store.setKey(p, "")
        _state.value = snapshot(p, message = "API key removed.")
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TdeeApplication
                LlmSettingsViewModel(app.container.llmSettingsStore)
            }
        }
    }
}
