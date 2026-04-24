package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.services.download.IDownloadService
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class DownloaderScreenModel(
    private val downloadService: IDownloadService,
    dispatchers: SynaraDispatchers
) : ScreenModel {

    private val modelDispatcher = dispatchers.createNamed("DownloaderScreenModel")

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
            _isAuthorized.value = downloadService.downloadAuthorized()
            _syncFavAvailable.value = downloadService.syncFavouritesAvailable()
            _isLoading.value = false

            downloadService.logs()
                .filter { !it.line.isNullOrBlank() }
                .filter { !it.line!!.startsWith("Let us check") }
                .collect { log ->
                    _logs.update { (it + log.line!!).takeLast(500) }
                }
        }

        screenModelScope.launch(modelDispatcher) {
            while (true) {
                if (!_syncFavAvailable.value) {
                    _syncFavAvailable.value = downloadService.syncFavouritesAvailable()
                }
                delay(5.seconds)
            }
        }
    }

    fun submitUrl(urlStr: String) {
        screenModelScope.launch(modelDispatcher) {
            downloadService.downloadUrls(listOf(urlStr))
        }
    }

    fun syncFavorites() {
        screenModelScope.launch(modelDispatcher) {
            if (downloadService.tidalSyncAuthorized()) {
                _syncFavAvailable.value = false
                downloadService.syncFavourites()
            }
        }
    }

    suspend fun isSyncAuthorized(): Boolean {
        return downloadService.tidalSyncAuthorized()
    }

    suspend fun getAuthUrl(): String {
        return downloadService.getAuthUrl()
    }

    fun downloadLogin(): Flow<String> {
        return downloadService.downloadLogin()
    }

    fun checkAuthorization() {
        screenModelScope.launch(modelDispatcher) {
            _isAuthorized.value = downloadService.downloadAuthorized()
        }
    }

    override fun onDispose() {
        super.onDispose()
        (modelDispatcher as? AutoCloseable)?.close()
    }
}
