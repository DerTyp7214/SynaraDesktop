package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.InsertableRadioChannel
import dev.dertyp.data.RadioChannel
import dev.dertyp.data.RadioChannelItemType
import dev.dertyp.data.RadioChannelSearchResults
import dev.dertyp.data.RadioType
import dev.dertyp.data.UserSong
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.IRadioChannelService
import dev.dertyp.services.IRadioService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class RadioScreenModel(
    private val radioService: IRadioService,
    private val radioChannelService: IRadioChannelService,
    private val songService: ISongService,
    private val albumService: IAlbumService,
    private val artistService: IArtistService,
    private val rpcServiceManager: RpcServiceManager,
    private val playerModel: PlayerModel,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<RadioScreenModel.RadioState>(RadioState()) {

    data class RadioState(
        val channels: List<RadioChannel> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val channelContent: RadioChannelSearchResults? = null,
        val channelContentLoading: Boolean = false,
        val librarySongs: List<UserSong> = emptyList(),
        val libraryAlbums: List<Album> = emptyList(),
        val libraryArtists: List<Artist> = emptyList(),
    )

    init {
        loadChannels()
    }

    fun loadChannels() {
        screenModelScope.launch(dispatchers.io) {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                rpcServiceManager.awaitAuthentication()
                val channels = radioChannelService.listChannels().sortedBy { it.position }
                mutableState.update { it.copy(channels = channels, isLoading = false) }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun startStation(type: RadioType, displayName: String) {
        screenModelScope.launch(dispatchers.io) {
            try {
                val sessionId = radioService.createRadioSession(type, null)
                playerModel.playRadio(sessionId, displayName)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "Unknown error") }
            }
        }
    }

    fun startChannel(channel: RadioChannel) {
        screenModelScope.launch(dispatchers.io) {
            try {
                val sessionId = radioChannelService.startChannel(channel.id)
                playerModel.playRadio(sessionId, channel.name)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "Unknown error") }
            }
        }
    }

    suspend fun createChannel(channel: InsertableRadioChannel): PlatformUUID? {
        return try {
            val id = radioChannelService.createChannel(channel)
            loadChannels()
            id
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message ?: "Unknown error") }
            null
        }
    }

    suspend fun updateChannel(id: PlatformUUID, channel: InsertableRadioChannel): Boolean {
        return try {
            val result = radioChannelService.updateChannel(id, channel)
            loadChannels()
            result
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message ?: "Unknown error") }
            false
        }
    }

    fun deleteChannel(id: PlatformUUID) {
        screenModelScope.launch(dispatchers.io) {
            try {
                radioChannelService.deleteChannel(id)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "Unknown error") }
            }
            loadChannels()
        }
    }

    suspend fun setChannelImage(id: PlatformUUID, bytes: ByteArray) {
        try {
            radioChannelService.setChannelImage(id, bytes)
            loadChannels()
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message ?: "Unknown error") }
        }
    }

    private var contentJob: Job? = null

    fun loadChannelContent(channelId: PlatformUUID, query: String = "", debounce: Boolean = false) {
        contentJob?.cancel()
        contentJob = screenModelScope.launch(dispatchers.io) {
            if (debounce) delay(300.milliseconds)
            mutableState.update { it.copy(channelContentLoading = true) }
            try {
                val results = radioChannelService.rankedSearch(channelId, query, explicit = true)
                mutableState.update { it.copy(channelContent = results, channelContentLoading = false) }
            } catch (e: Exception) {
                mutableState.update { it.copy(channelContentLoading = false, error = e.message) }
            }
        }
    }

    fun clearChannelContent() {
        contentJob?.cancel()
        librarySearchJob?.cancel()
        mutableState.update {
            it.copy(
                channelContent = null,
                librarySongs = emptyList(),
                libraryAlbums = emptyList(),
                libraryArtists = emptyList()
            )
        }
    }

    private var librarySearchJob: Job? = null

    fun searchLibrary(query: String) {
        librarySearchJob?.cancel()
        if (query.length < 2) {
            mutableState.update {
                it.copy(librarySongs = emptyList(), libraryAlbums = emptyList(), libraryArtists = emptyList())
            }
            return
        }
        librarySearchJob = screenModelScope.launch(dispatchers.io) {
            delay(300.milliseconds)
            try {
                val songsJob = launch {
                    val songs = songService.rankedSearch(0, 10, query, explicit = true).data
                    mutableState.update { it.copy(librarySongs = songs) }
                }
                val albumsJob = launch {
                    val albums = albumService.rankedSearch(0, 10, query).data
                    mutableState.update { it.copy(libraryAlbums = albums) }
                }
                val artistsJob = launch {
                    val artists = artistService.rankedSearch(0, 10, query).data
                    mutableState.update { it.copy(libraryArtists = artists) }
                }
                songsJob.join()
                albumsJob.join()
                artistsJob.join()
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
        }
    }

    fun addChannelItem(channelId: PlatformUUID, type: RadioChannelItemType, itemId: PlatformUUID, contentQuery: String) {
        screenModelScope.launch(dispatchers.io) {
            try {
                radioChannelService.addChannelItem(channelId, type, itemId)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
            loadChannelContent(channelId, contentQuery)
            loadChannels()
        }
    }

    fun removeChannelItem(channelId: PlatformUUID, type: RadioChannelItemType, itemId: PlatformUUID, contentQuery: String) {
        screenModelScope.launch(dispatchers.io) {
            try {
                radioChannelService.removeChannelItem(channelId, type, itemId)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
            loadChannelContent(channelId, contentQuery)
            loadChannels()
        }
    }
}
