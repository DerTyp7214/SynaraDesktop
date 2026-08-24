package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.SubsonicCredentialInfo
import dev.dertyp.services.ISubsonicCredentialService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SubsonicCredentialScreenModel(
    private val subsonicCredentialService: ISubsonicCredentialService,
    private val rpcServiceManager: RpcServiceManager,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<SubsonicCredentialScreenModel.SubsonicState>(SubsonicState()) {

    data class SubsonicState(
        val credential: SubsonicCredentialInfo? = null,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    init {
        load()
    }

    fun load() {
        screenModelScope.launch(dispatchers.io) {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                rpcServiceManager.awaitAuthentication()
                val credential = subsonicCredentialService.getSubsonicCredential()
                mutableState.update { it.copy(credential = credential, isLoading = false) }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun regenerate() {
        screenModelScope.launch(dispatchers.io) {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val credential = subsonicCredentialService.regenerateSubsonicCredential()
                mutableState.update { it.copy(credential = credential, isLoading = false) }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun revoke() {
        screenModelScope.launch(dispatchers.io) {
            try {
                subsonicCredentialService.revokeSubsonicCredential()
                mutableState.update { it.copy(credential = null) }
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
        }
    }
}
