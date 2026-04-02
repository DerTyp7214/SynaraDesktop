package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.core.safeParseUrl
import dev.dertyp.core.tidalId
import dev.dertyp.services.tdn.IDownloadService
import dev.dertyp.services.tdn.Type
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TidalDownloadScreenModel(
    private val downloadService: IDownloadService,
    dispatchers: SynaraDispatchers
) : ScreenModel {

    private val modelDispatcher = dispatchers.createNamed("TidalDownloadScreenModel")

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
            _isAuthorized.value = downloadService.tidalDownloadAuthorized()
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
                delay(5000)
            }
        }
    }

    fun submitUrl(urlStr: String) {
        val url = safeParseUrl(urlStr) ?: return
        val segment = url.segments.let { segments ->
            if (segments.firstOrNull() == "browse") segments.getOrNull(1)
            else segments.firstOrNull()
        } ?: return
        val type = Type.fromValue(segment) ?: return

        screenModelScope.launch(modelDispatcher) {
            downloadService.downloadTidalIds(listOf(url.tidalId()), type)
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

    fun tidalDownloadLogin(): Flow<String> {
        return downloadService.tidalDownloadLogin()
    }

    fun checkAuthorization() {
        screenModelScope.launch(modelDispatcher) {
            _isAuthorized.value = downloadService.tidalDownloadAuthorized()
        }
    }

    override fun onDispose() {
        super.onDispose()
        (modelDispatcher as? AutoCloseable)?.close()
    }
}
