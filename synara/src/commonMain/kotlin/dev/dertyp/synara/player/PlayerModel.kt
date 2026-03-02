package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.serializers.BaseSerializersModule
import dev.dertyp.serializers.UUIDByteSerializer
import dev.dertyp.synara.rpc.services.AlbumServiceWrapper
import dev.dertyp.synara.rpc.services.ArtistServiceWrapper
import dev.dertyp.synara.rpc.services.SongServiceWrapper
import dev.dertyp.synara.rpc.services.UserPlaylistServiceWrapper
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.takeAverage
import dev.dertyp.synara.ui.models.SnackbarManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.protobuf.ProtoBuf
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.getString
import synara.synara.generated.resources.*
import kotlin.math.log10

@Suppress("unused")
@OptIn(ExperimentalSerializationApi::class)
class PlayerModel(
    private val audioPlayer: AudioPlayer,
    private val songService: SongServiceWrapper,
    private val userPlaylistService: UserPlaylistServiceWrapper,
    private val albumService: AlbumServiceWrapper,
    private val artistService: ArtistServiceWrapper,
    private val songCache: SongCache,
    private val settings: Settings,
    private val snackbarManager: SnackbarManager,
    statePath: String
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val fileSystem = FileSystem.SYSTEM
    private val path: Path = statePath.toPath()

    private val protoBuf = ProtoBuf {
        encodeDefaults = true
        serializersModule = SerializersModule {
            include(BaseSerializersModule)
            contextual(UUIDByteSerializer)
        }
    }

    private var originalQueue: List<QueueEntry> = emptyList()
    private var setSourceJob: Job? = null
    
    private val _currentSource = MutableStateFlow<PlaybackSource?>(null)
    val currentSource: StateFlow<PlaybackSource?> = _currentSource.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<UserSong?>(null)
    val currentSong: StateFlow<UserSong?> = _currentSong.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleMode = MutableStateFlow(false)
    val shuffleMode: StateFlow<Boolean> = _shuffleMode.asStateFlow()

    private val _requestedWindow = MutableStateFlow<IntRange?>(null)

    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val currentPosition: StateFlow<Long> = audioPlayer.currentPosition
    val duration: StateFlow<Long> = audioPlayer.duration
    val volume: StateFlow<Float> = audioPlayer.volume
    val sampleRate: StateFlow<Int> = audioPlayer.sampleRate
    val bitsPerSample: StateFlow<Int> = audioPlayer.bitsPerSample
    val bitRate: StateFlow<Long> = audioPlayer.bitRate
    val fftData: StateFlow<FloatArray> = audioPlayer.fftData

    val audioIntensity: StateFlow<Float> = audioPlayer.fftData
        .map { fft ->
            if (fft.isEmpty()) return@map 0f
            val avgMagnitude = fft.takeAverage(5)
            val minDb = -90f
            val maxDb = -20f
            val db = if (avgMagnitude > 0.00003f) 20f * log10(avgMagnitude) else minDb
            ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
        }
        .stateIn(scope, SharingStarted.Lazily, 0f)

    init {
        loadState()

        val savedVolume = settings.get(SettingKey.Volume, 0.5f)
        audioPlayer.setVolume(savedVolume)

        scope.launch {
            combine(_queue, _currentIndex, _currentSource, _repeatMode, _shuffleMode) { q, idx, src, repeat, shuffle ->
                PlayerState(q, originalQueue, src, idx, repeat, shuffle)
            }.distinctUntilChanged { old, new ->
                old.queue == new.queue && 
                old.currentIndex == new.currentIndex && 
                old.source == new.source &&
                old.repeatMode == new.repeatMode &&
                old.shuffleMode == new.shuffleMode
            }.collect { state ->
                updateCurrentSong(state.queue.getOrNull(state.currentIndex))
                saveState(state)
            }
        }

        scope.launch {
            _requestedWindow.collectLatest {
                updateWindow()
            }
        }

        scope.launch {
            audioPlayer.onFinished.collect {
                handlePlaybackFinished()
            }
        }

        scope.launch {
            songCache.updates.collect { update ->
                when (update) {
                    is CacheUpdate.SongUpdated -> {
                        val updatedSong = update.song
                        if (_currentSong.value?.id == updatedSong.id) {
                            _currentSong.value = updatedSong
                        }
                        
                        _queue.value = _queue.value.map { entry ->
                            if (entry is QueueEntry.Explicit && entry.song.id == updatedSong.id) {
                                entry.copy(song = updatedSong)
                            } else {
                                entry
                            }
                        }

                        originalQueue = originalQueue.map { entry ->
                            if (entry is QueueEntry.Explicit && entry.song.id == updatedSong.id) {
                                entry.copy(song = updatedSong)
                            } else {
                                entry
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadState() {
        try {
            if (!fileSystem.exists(path)) return

            val bytes = fileSystem.read(path) { readByteArray() }
            val state: PlayerState = protoBuf.decodeFromByteArray(bytes)

            _queue.value = state.queue
            originalQueue = state.originalQueue
            _currentSource.value = state.source
            _currentIndex.value = state.currentIndex
            _repeatMode.value = state.repeatMode
            _shuffleMode.value = state.shuffleMode
            
            val currentEntry = _queue.value.getOrNull(_currentIndex.value)
            if (currentEntry != null) {
                scope.launch {
                    resolveSongId(currentEntry)?.let { id ->
                        audioPlayer.load(id)
                    }
                }
            }
        } catch (_: Exception) {
            _queue.value = emptyList()
            originalQueue = emptyList()
            _currentIndex.value = -1
        }
    }

    private fun saveState(state: PlayerState) {
        scope.launch(Dispatchers.Default) {
            try {
                val bytes = protoBuf.encodeToByteArray(state)
                fileSystem.write(path) {
                    write(bytes)
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun resolveSongId(entry: QueueEntry): PlatformUUID? {
        return when (entry) {
            is QueueEntry.Explicit -> {
                songCache.put(entry.song)
                entry.song.id
            }
            is QueueEntry.FromSource -> entry.songId
        }
    }

    private suspend fun updateCurrentSong(entry: QueueEntry?) {
        if (entry == null) {
            _currentSong.value = null
            return
        }

        val id = when (entry) {
            is QueueEntry.Explicit -> {
                songCache.put(entry.song)
                entry.song.id
            }
            is QueueEntry.FromSource -> entry.songId
        }

        val song = songCache.get(id) ?: songService.byId(id)?.also { songCache.put(it) }
        _currentSong.value = song
        
        updateWindow()
    }

    private fun updateWindow() {
        val index = _currentIndex.value
        val q = _queue.value
        if (q.isEmpty()) return

        val requested = _requestedWindow.value
        val playingRange = (index - 2)..(index + 10)
        
        val range = if (requested != null) {
            (minOf(playingRange.first, requested.first - 5))..(maxOf(playingRange.last, requested.last + 5))
        } else {
            playingRange
        }

        val toFetchFromSource = range.filter { i ->
            i in q.indices && q[i] is QueueEntry.FromSource
        }.map { q[it] as QueueEntry.FromSource }

        if (toFetchFromSource.isNotEmpty()) {
            scope.launch(Dispatchers.Default) {
                try {
                    val neededIds = toFetchFromSource.map { it.songId }
                        .distinct()
                        .filter { songCache.get(it) == null }
                    
                    if (neededIds.isNotEmpty()) {
                        neededIds.chunked(50).forEach { chunk ->
                            val response = songService.byIds(chunk)
                            songCache.putAll(response.data)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handlePlaybackFinished() {
        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                val index = _currentIndex.value
                val entry = _queue.value.getOrNull(index)
                if (entry != null) {
                    scope.launch {
                        resolveSongId(entry)?.let { audioPlayer.load(it) }
                        audioPlayer.play()
                    }
                }
            }
            RepeatMode.ALL -> {
                val nextIndex = if (_queue.value.isNotEmpty()) (_currentIndex.value + 1) % _queue.value.size else -1
                playAtIndex(nextIndex)
            }
            RepeatMode.OFF -> {
                val nextIndex = _currentIndex.value + 1
                if (nextIndex in _queue.value.indices) {
                    playAtIndex(nextIndex)
                } else {
                    audioPlayer.stop()
                    _currentIndex.value = -1
                }
            }
        }
    }

    suspend fun resolveSong(entry: QueueEntry): UserSong? {
        return when (entry) {
            is QueueEntry.Explicit -> entry.song
            is QueueEntry.FromSource -> songCache.get(entry.songId) ?: songService.byId(entry.songId)?.also { songCache.put(it) }
        }
    }

    fun setRequestedWindow(range: IntRange?) {
        _requestedWindow.value = range
    }

    fun playEntry(entry: QueueEntry) {
        val index = _queue.value.indexOf(entry)
        if (index != -1) {
            playAtIndex(index)
        }
    }

    fun playAtIndex(index: Int) {
        if (index in _queue.value.indices) {
            val entry = _queue.value[index]
            _currentIndex.value = index
            scope.launch {
                resolveSongId(entry)?.let { id ->
                    audioPlayer.load(id)
                    audioPlayer.play()
                }
            }
        }
    }

    fun playSong(song: UserSong) {
        val entry = QueueEntry.Explicit(song)
        _currentSource.value = PlaybackSource.Manual
        originalQueue = listOf(entry)
        _queue.value = listOf(entry)
        _currentIndex.value = 0
        scope.launch { songCache.put(song) }
        audioPlayer.load(song.id)
        audioPlayer.play()
    }

    fun playQueue(playbackQueue: PlaybackQueue, startIndex: Int = 0) {
        setSourceJob?.cancel()
        setSourceJob = scope.launch {
            _currentSource.value = playbackQueue.source
            val source = playbackQueue.source.toQueueSource(songService)

            if (playbackQueue.items.isNotEmpty()) {
                val newItems = playbackQueue.items
                originalQueue = newItems
                val explicitSongs = newItems.filterIsInstance<QueueEntry.Explicit>().map { it.song }
                songCache.putAll(explicitSongs)

                if (_shuffleMode.value) {
                    val shuffled = newItems.shuffled().toMutableList()
                    val currentItem = newItems.getOrNull(startIndex)
                    if (currentItem != null) {
                        shuffled.remove(currentItem)
                        shuffled.add(0, currentItem)
                    }
                    _queue.value = shuffled
                    _currentIndex.value = 0
                } else {
                    _queue.value = newItems
                    _currentIndex.value = startIndex
                }
            } else if (source != null) {
                val startSong = try {
                    source.getSongAt(startIndex) as? UserSong
                } catch (_: Exception) {
                    null
                }

                if (startSong != null) {
                    val startEntry = QueueEntry.FromSource(startSong.id)
                    _queue.value = listOf(startEntry)
                    _currentIndex.value = 0
                    _currentSong.value = startSong
                    songCache.put(startSong)
                    audioPlayer.load(startSong.id)
                    audioPlayer.play()
                }

                launch(Dispatchers.Default) {
                    val collectedEntries = resolvePlaybackQueueItems(playbackQueue)
                    withContext(Dispatchers.Main) {
                        originalQueue = collectedEntries
                        val currentSongId = startSong?.id

                        if (_shuffleMode.value) {
                            val shuffled = collectedEntries.toMutableList()
                            val currentEntry = shuffled.find { (it as? QueueEntry.FromSource)?.songId == currentSongId }
                            if (currentEntry != null) {
                                shuffled.remove(currentEntry)
                                shuffled.add(0, currentEntry)
                            } else {
                                shuffled.shuffle()
                            }
                            _queue.value = shuffled
                            _currentIndex.value = 0
                        } else {
                            _queue.value = collectedEntries
                            if (currentSongId != null) {
                                _currentIndex.value = collectedEntries.indexOfFirst { entry ->
                                    (entry as? QueueEntry.FromSource)?.songId == currentSongId
                                }.coerceAtLeast(0)
                            } else {
                                _currentIndex.value = startIndex.coerceIn(0, (collectedEntries.size - 1).coerceAtLeast(0))
                            }
                        }
                        updateWindow()
                    }
                }
                return@launch
            }

            val currentItem = _queue.value.getOrNull(_currentIndex.value)
            if (currentItem != null) {
                resolveSongId(currentItem)?.let { id ->
                    audioPlayer.load(id)
                    audioPlayer.play()
                }
            }
        }
    }

    private suspend fun resolvePlaybackQueueItems(playbackQueue: PlaybackQueue): List<QueueEntry> {
        if (playbackQueue.items.isNotEmpty()) {
            val explicitSongs = playbackQueue.items.filterIsInstance<QueueEntry.Explicit>().map { it.song }
            songCache.putAll(explicitSongs)
            return playbackQueue.items
        }

        val source = playbackQueue.source.toQueueSource(songService) ?: return emptyList()
        val entries = mutableListOf<QueueEntry>()
        source.getIdFlow().collect { id ->
            entries.add(QueueEntry.FromSource(id))
        }
        return entries
    }

    private suspend fun getSourceDisplayName(source: PlaybackSource): String? {
        return when (source) {
            is PlaybackSource.Album -> albumService.byId(source.albumId)?.name
            is PlaybackSource.Artist -> artistService.byId(source.artistId)?.name
            is PlaybackSource.Playlist -> userPlaylistService.byId(source.playlistId)?.name
            PlaybackSource.AllSongs -> getString(Res.string.songs)
            PlaybackSource.LikedSongs -> getString(Res.string.favorite)
            PlaybackSource.Manual -> null
        }
    }

    fun addToQueue(song: UserSong) {
        addToQueue(PlaybackQueue(items = listOf(QueueEntry.Explicit(song))))
    }

    fun addToQueue(playbackQueue: PlaybackQueue) {
        scope.launch {
            val newItems = resolvePlaybackQueueItems(playbackQueue)
            if (newItems.isEmpty()) return@launch

            val name = if (playbackQueue.items.size == 1 && playbackQueue.items[0] is QueueEntry.Explicit) {
                (playbackQueue.items[0] as QueueEntry.Explicit).song.title
            } else {
                getSourceDisplayName(playbackQueue.source)
            }

            val message = if (name != null) {
                getString(Res.string.added_to_queue_item, name)
            } else {
                getString(Res.string.added_to_queue)
            }

            withContext(Dispatchers.Main) {
                originalQueue = originalQueue + newItems
                if (_shuffleMode.value) {
                    _queue.value += newItems.shuffled()
                } else {
                    _queue.value += newItems
                }
                
                if (_currentIndex.value == -1) {
                    playAtIndex(0)
                }
                
                snackbarManager.showSnackbar(message)
            }
        }
    }

    fun playNext(song: UserSong) {
        playNext(PlaybackQueue(items = listOf(QueueEntry.Explicit(song))))
    }

    fun playNext(playbackQueue: PlaybackQueue) {
        scope.launch {
            val newItems = resolvePlaybackQueueItems(playbackQueue)
            if (newItems.isEmpty()) return@launch

            val name = if (playbackQueue.items.size == 1 && playbackQueue.items[0] is QueueEntry.Explicit) {
                (playbackQueue.items[0] as QueueEntry.Explicit).song.title
            } else {
                getSourceDisplayName(playbackQueue.source) ?: (newItems.getOrNull(0) as? QueueEntry.Explicit)?.song?.title
            }

            val message = if (name != null) {
                getString(Res.string.playing_next_item, name)
            } else {
                getString(Res.string.play_next)
            }

            withContext(Dispatchers.Main) {
                if (_currentIndex.value == -1) {
                    playQueue(PlaybackQueue(items = newItems, source = playbackQueue.source))
                    snackbarManager.showSnackbar(message)
                    return@withContext
                }
                
                val insertIndex = _currentIndex.value + 1
                val newQueue = _queue.value.toMutableList()
                newQueue.addAll(insertIndex, newItems)
                _queue.value = newQueue
                
                val currentEntry = _queue.value.getOrNull(_currentIndex.value)
                val origInsertIndex = originalQueue.indexOf(currentEntry).let { if (it == -1) originalQueue.size else it + 1 }
                val newOrigQueue = originalQueue.toMutableList()
                newOrigQueue.addAll(origInsertIndex, newItems)
                originalQueue = newOrigQueue
                
                snackbarManager.showSnackbar(message)
            }
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in _queue.value.indices || toIndex !in _queue.value.indices) return
        if (fromIndex == toIndex) return

        val newQueue = _queue.value.toMutableList()
        val entry = newQueue.removeAt(fromIndex)
        newQueue.add(toIndex, entry)
        _queue.value = newQueue

        when (val currentIdx = _currentIndex.value) {
            fromIndex -> {
                _currentIndex.value = toIndex
            }
            in (fromIndex + 1)..toIndex -> {
                _currentIndex.value = currentIdx - 1
            }
            in toIndex..<fromIndex -> {
                _currentIndex.value = currentIdx + 1
            }
        }

        if (!_shuffleMode.value) {
            originalQueue = newQueue
        }
    }

    fun removeFromQueue(entry: QueueEntry) {
        val index = _queue.value.indexOf(entry)
        if (index == -1) return

        val newQueue = _queue.value.toMutableList()
        newQueue.removeAt(index)
        
        val currentIdx = _currentIndex.value
        if (index == currentIdx) {
            if (newQueue.isEmpty()) {
                clearQueue()
            } else {
                _queue.value = newQueue
                playAtIndex(if (index < newQueue.size) index else 0)
            }
        } else {
            _queue.value = newQueue
            if (index < currentIdx) {
                _currentIndex.value = currentIdx - 1
            }
        }

        val origIndex = originalQueue.indexOf(entry)
        if (origIndex != -1) {
            val newOrig = originalQueue.toMutableList()
            newOrig.removeAt(origIndex)
            originalQueue = newOrig
        }
    }

    fun clearQueue() {
        audioPlayer.stop()
        _queue.value = emptyList()
        originalQueue = emptyList()
        _currentIndex.value = -1
        _currentSong.value = null
        _currentSource.value = null
    }

    fun togglePlayPause() {
        if (audioPlayer.isPlaying.value) {
            audioPlayer.pause()
        } else {
            audioPlayer.play()
        }
    }

    fun skipNext() {
        val nextIndex = _currentIndex.value + 1
        if (nextIndex in _queue.value.indices) {
            playAtIndex(nextIndex)
        } else if (_repeatMode.value == RepeatMode.ALL && _queue.value.isNotEmpty()) {
            playAtIndex(0)
        }
    }

    fun skipPrevious() {
        val prevIndex = _currentIndex.value - 1
        if (prevIndex in _queue.value.indices) {
            playAtIndex(prevIndex)
        } else if (_repeatMode.value == RepeatMode.ALL && _queue.value.isNotEmpty()) {
            playAtIndex(_queue.value.size - 1)
        }
    }

    fun stop() {
        audioPlayer.stop()
    }

    fun pause() {
        audioPlayer.pause()
    }

    fun play() {
        audioPlayer.play()
    }

    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    fun setVolume(value: Float) {
        audioPlayer.setVolume(value)
        settings.put(SettingKey.Volume, value)
    }

    fun toggleShuffle() {
        val currentEntry = _queue.value.getOrNull(_currentIndex.value)
        val newShuffleMode = !_shuffleMode.value
        _shuffleMode.value = newShuffleMode
        
        if (newShuffleMode) {
            val shuffled = originalQueue.shuffled().toMutableList()
            if (currentEntry != null) {
                shuffled.remove(currentEntry)
                shuffled.add(0, currentEntry)
                _currentIndex.value = 0
            }
            _queue.value = shuffled
        } else {
            _queue.value = originalQueue
            if (currentEntry != null) {
                _currentIndex.value = originalQueue.indexOf(currentEntry)
            }
        }
    }

    fun setRepeatMode(repeatMode: RepeatMode) {
        _repeatMode.value = repeatMode
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun toggleLike() {
        _currentSong.value?.let { toggleLike(it) }
    }

    fun toggleLike(song: UserSong) {
        scope.launch {
            try {
                val updated = songService.setLiked(song.id, !(song.isFavourite ?: false)) ?: return@launch
                songCache.put(updated)
                songCache.notifyLikedSongsChanged()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addSongToPlaylist(playlistId: PlatformUUID, songId: PlatformUUID) {
        scope.launch {
            try {
                userPlaylistService.addToPlaylist(playlistId, listOf(currentTimeMillis() to songId))
                songCache.notifyPlaylistChanged(playlistId)
                songCache.notifyPlaylistsChanged()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: PlatformUUID, songId: PlatformUUID) {
        scope.launch {
            try {
                userPlaylistService.removeFromPlaylist(playlistId, listOf(songId))
                songCache.notifyPlaylistChanged(playlistId)
                songCache.notifyPlaylistsChanged()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getPlaybackState(): PlaybackState {
        return PlaybackState(
            queue = _queue.value.mapIndexed { index, entry ->
                when (entry) {
                    is QueueEntry.Explicit -> PlaybackState.QueueEntry.Explicit(entry.song, index.toLong())
                    is QueueEntry.FromSource -> PlaybackState.QueueEntry.FromSource(entry.songId, index.toLong())
                }
            },
            currentIndex = _currentIndex.value,
            isPlaying = audioPlayer.isPlaying.value,
            positionMs = audioPlayer.currentPosition.value,
            shuffleMode = _shuffleMode.value,
            repeatMode = _repeatMode.value
        )
    }
}
