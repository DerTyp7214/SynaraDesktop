package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.services.import.IImportService
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ImportScreenModel(
    private val importService: IImportService,
    dispatchers: SynaraDispatchers
) : ScreenModel {

    private val modelDispatcher = dispatchers.io

    private val _isAuthorized = MutableStateFlow<Boolean?>(null)
    val isAuthorized = _isAuthorized.asStateFlow()

    private val _syncFavAvailable = MutableStateFlow(false)
    val syncFavAvailable = _syncFavAvailable.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    init {
        screenModelScope.launch(modelDispatcher) {
            _isAuthorized.value = importService.importAuthorized()
            _syncFavAvailable.value = importService.syncFavouritesAvailable()
            _isLoading.value = false

            importService.logs()
                .filter { !it.line.isNullOrBlank() }
                .filter { !it.line!!.startsWith("Let us check") }
                .collect { log ->
                    _logs.update { (it + log.line!!).takeLast(500) }
                }
        }

        screenModelScope.launch(modelDispatcher) {
            while (true) {
                if (!_syncFavAvailable.value) {
                    _syncFavAvailable.value = importService.syncFavouritesAvailable()
                }
                delay(5.seconds)
            }
        }
    }

    fun submitUrl(urlStr: String) {
        screenModelScope.launch(modelDispatcher) {
            importService.importUrls(listOf(urlStr))
        }
    }

    fun syncFavorites() {
        screenModelScope.launch(modelDispatcher) {
            if (importService.tidalSyncAuthorized()) {
                _syncFavAvailable.value = false
                importService.syncFavourites()
            }
        }
    }

    suspend fun isSyncAuthorized(): Boolean {
        return importService.tidalSyncAuthorized()
    }

    suspend fun getAuthUrl(): String {
        return importService.getAuthUrl()
    }

    fun importLogin(): Flow<String> {
        return importService.importLogin()
    }

    fun checkAuthorization() {
        screenModelScope.launch(modelDispatcher) {
            _isAuthorized.value = importService.importAuthorized()
        }
    }
}
