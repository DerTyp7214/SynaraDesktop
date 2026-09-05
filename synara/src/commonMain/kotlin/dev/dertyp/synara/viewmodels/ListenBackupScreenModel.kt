package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.ListenBackupConfig
import dev.dertyp.data.ListenBackupConnectionTest
import dev.dertyp.data.ListenBackupState
import dev.dertyp.services.IListenBackupService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ListenBackupScreenModel(
    private val listenBackupService: IListenBackupService,
    private val rpcServiceManager: RpcServiceManager,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<ListenBackupScreenModel.ListenBackupUiState>(ListenBackupUiState()) {

    data class ListenBackupUiState(
        val backupState: ListenBackupState? = null,
        val isLoading: Boolean = true,
        val error: String? = null,
        val enabled: Boolean = false,
        val url: String = "",
        val key: String = "",
        val batchSize: String = "1000",
        val hasLoadedForm: Boolean = false,
        val isBusy: Boolean = false,
        val testResult: ListenBackupConnectionTest? = null
    )

    init {
        load()
    }

    fun load() {
        screenModelScope.launch(dispatchers.io) {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                rpcServiceManager.awaitAuthentication()
                try {
                    listenBackupService.getStateFlow().collect { applyState(it) }
                } catch (e: Exception) {
                    applyState(listenBackupService.getState())
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    private fun applyState(backupState: ListenBackupState) {
        mutableState.update { current ->
            val seedForm = !current.hasLoadedForm
            current.copy(
                backupState = backupState,
                isLoading = false,
                error = null,
                enabled = if (seedForm) backupState.config.enabled else current.enabled,
                url = if (seedForm) backupState.config.url else current.url,
                batchSize = if (seedForm) backupState.config.batchSize.toString() else current.batchSize,
                hasLoadedForm = true
            )
        }
    }

    fun setEnabled(value: Boolean) = mutableState.update { it.copy(enabled = value) }

    fun setUrl(value: String) = mutableState.update { it.copy(url = value) }

    fun setKey(value: String) = mutableState.update { it.copy(key = value) }

    fun setBatchSize(value: String) {
        if (value.isEmpty() || value.all { it.isDigit() }) {
            mutableState.update { it.copy(batchSize = value) }
        }
    }

    private fun currentConfig(): ListenBackupConfig {
        val current = state.value
        return ListenBackupConfig(
            enabled = current.enabled,
            url = current.url.trim(),
            key = current.key.ifBlank { null },
            batchSize = current.batchSize.toIntOrNull() ?: 1000
        )
    }

    suspend fun save(): Boolean {
        mutableState.update { it.copy(isBusy = true, error = null) }
        return try {
            val result = listenBackupService.updateConfig(currentConfig())
            applyState(result)
            mutableState.update { it.copy(isBusy = false, key = "") }
            true
        } catch (e: Exception) {
            mutableState.update { it.copy(isBusy = false, error = e.message ?: "Unknown error") }
            false
        }
    }

    suspend fun testConnection() {
        mutableState.update { it.copy(isBusy = true, error = null, testResult = null) }
        try {
            val result = listenBackupService.testConnection(currentConfig())
            mutableState.update { it.copy(isBusy = false, testResult = result) }
        } catch (e: Exception) {
            mutableState.update { it.copy(isBusy = false, error = e.message ?: "Unknown error") }
        }
    }

    suspend fun syncNow(): Boolean {
        mutableState.update { it.copy(isBusy = true, error = null) }
        return try {
            val result = listenBackupService.syncNow()
            applyState(result)
            mutableState.update { it.copy(isBusy = false) }
            true
        } catch (e: Exception) {
            mutableState.update { it.copy(isBusy = false, error = e.message ?: "Unknown error") }
            false
        }
    }

    suspend fun resetCursor(): Boolean {
        mutableState.update { it.copy(isBusy = true, error = null) }
        return try {
            val result = listenBackupService.resetCursor()
            applyState(result)
            mutableState.update { it.copy(isBusy = false) }
            true
        } catch (e: Exception) {
            mutableState.update { it.copy(isBusy = false, error = e.message ?: "Unknown error") }
            false
        }
    }
}
