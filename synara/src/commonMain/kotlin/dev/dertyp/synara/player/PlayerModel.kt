package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.serializers.BaseSerializersModule
import dev.dertyp.serializers.UUIDByteSerializer
import dev.dertyp.synara.rpc.services.SongServiceWrapper
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.protobuf.ProtoBuf
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@OptIn(ExperimentalSerializationApi::class)
class PlayerModel(
    private val audioPlayer: AudioPlayer,
    private val songService: SongServiceWrapper,
    private val settings: Settings,
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
    
    private val _currentSource = MutableStateFlow<PlaybackSource?>(null)
    val currentSource: StateFlow<PlaybackSource?> = _currentSource.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<UserSong?>(null)
    val currentSong: StateFlow<UserSong?> = _currentSong.asStateFlow()

    private val _fetchedSongs = MutableStateFlow<Map<QueueEntry, UserSong>>(emptyMap())
    val fetchedSongs: StateFlow<Map<QueueEntry, UserSong>> = _fetchedSongs.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleMode = MutableStateFlow(false)
    val shuffleMode: StateFlow<Boolean> = _shuffleMode.asStateFlow()

    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val currentPosition: StateFlow<Long> = audioPlayer.currentPosition
    val duration: StateFlow<Long> = audioPlayer.duration
    val volume: StateFlow<Float> = audioPlayer.volume

    init {
        loadState()

        val savedVolume = settings.get(SettingKey.Volume, 0.5f)
        audioPlayer.setVolume(savedVolume)

        scope.launch {
            combine(_queue, _currentIndex, _currentSource, _repeatMode, _shuffleMode) { q, idx, src, repeat, shuffle ->
                PlayerState(q, originalQueue, src, idx, repeat, shuffle, audioPlayer.currentPosition.value)
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
            audioPlayer.onFinished.collect {
                handlePlaybackFinished()
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
                audioPlayer.load(currentEntry.songId)
                if (state.lastPosition > 0) {
                    audioPlayer.seekTo(state.lastPosition)
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

    private suspend fun updateCurrentSong(entry: QueueEntry?) {
        if (entry == null) {
            _currentSong.value = null
            return
        }

        val cached = when (entry) {
            is QueueEntry.Explicit -> entry.song
            is QueueEntry.FromSource -> _fetchedSongs.value[entry]
        }

        if (cached != null) {
            _currentSong.value = cached
        } else if (entry is QueueEntry.FromSource) {
            val song = songService.byId(entry.songId)
            if (song != null) {
                _fetchedSongs.value += (entry to song)
                _currentSong.value = song
            }
        }
        
        updateWindow()
    }

    private fun updateWindow() {
        val index = _currentIndex.value
        val q = _queue.value
        if (index == -1 || q.isEmpty()) return

        val range = (index - 2)..(index + 10)
        val toFetch = range.filter { i ->
            i in q.indices && q[i] is QueueEntry.FromSource && !_fetchedSongs.value.containsKey(q[i])
        }.map { q[it] as QueueEntry.FromSource }

        if (toFetch.isEmpty()) return

        scope.launch(Dispatchers.Default) {
            try {
                val ids = toFetch.map { it.songId }.distinct()
                val response = songService.byIds(ids)
                val results = mutableMapOf<QueueEntry, UserSong>()
                
                response.data.forEach { song ->
                    toFetch.filter { it.songId == song.id }.forEach { entry ->
                        results[entry] = song
                    }
                }

                if (results.isNotEmpty()) {
                    _fetchedSongs.value += results
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handlePlaybackFinished() {
        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                _queue.value.getOrNull(_currentIndex.value)?.songId?.let { audioPlayer.load(it) }
                audioPlayer.play()
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

    private fun playAtIndex(index: Int) {
        if (index in _queue.value.indices) {
            val entry = _queue.value[index]
            _currentIndex.value = index
            audioPlayer.load(entry.songId)
            audioPlayer.play()
        }
    }

    fun playSong(song: UserSong) {
        val entry = QueueEntry.Explicit(song)
        _currentSource.value = PlaybackSource.Manual
        originalQueue = listOf(entry)
        _queue.value = listOf(entry)
        _currentIndex.value = 0
        audioPlayer.load(song.id)
        audioPlayer.play()
    }

    fun playQueue(playbackQueue: PlaybackQueue, startIndex: Int = 0) {
        _currentSource.value = playbackQueue.source
        originalQueue = playbackQueue.items
        if (_shuffleMode.value) {
            val shuffled = originalQueue.shuffled().toMutableList()
            val currentItem = originalQueue.getOrNull(startIndex)
            if (currentItem != null) {
                shuffled.remove(currentItem)
                shuffled.add(0, currentItem)
            }
            _queue.value = shuffled
            _currentIndex.value = 0
        } else {
            _queue.value = originalQueue
            _currentIndex.value = startIndex
        }
        
        val currentItem = _queue.value.getOrNull(_currentIndex.value)
        if (currentItem != null) {
            audioPlayer.load(currentItem.songId)
            audioPlayer.play()
        }
    }

    fun addToQueue(song: UserSong) {
        addToQueue(PlaybackQueue(items = listOf(QueueEntry.Explicit(song))))
    }

    fun addToQueue(playbackQueue: PlaybackQueue) {
        val newItems = playbackQueue.items
        if (newItems.isEmpty()) return

        originalQueue = originalQueue + newItems
        if (_shuffleMode.value) {
            _queue.value += newItems.shuffled()
        } else {
            _queue.value += newItems
        }
        
        if (_currentIndex.value == -1) {
            playAtIndex(0)
        }
    }

    fun playNext(song: UserSong) {
        playNext(PlaybackQueue(items = listOf(QueueEntry.Explicit(song))))
    }

    fun playNext(playbackQueue: PlaybackQueue) {
        val newItems = playbackQueue.items
        if (newItems.isEmpty()) return

        if (_currentIndex.value == -1) {
            playQueue(playbackQueue)
            return
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

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun toggleLike() {
        val song = _currentSong.value ?: return
        scope.launch {
            try {
                val updated = songService.setLiked(song.id, !(song.isFavourite ?: false)) ?: return@launch

                val newFetched = _fetchedSongs.value.toMutableMap()
                var changed = false
                newFetched.entries.forEach { (entry, s) ->
                    if (s.id == song.id) {
                        newFetched[entry] = updated
                        changed = true
                    }
                }
                if (changed) {
                    _fetchedSongs.value = newFetched
                }

                _queue.value = _queue.value.map { entry ->
                    if (entry is QueueEntry.Explicit && entry.song.id == song.id) {
                        entry.copy(song = updated)
                    } else {
                        entry
                    }
                }

                originalQueue = originalQueue.map { entry ->
                    if (entry is QueueEntry.Explicit && entry.song.id == song.id) {
                        entry.copy(song = updated)
                    } else {
                        entry
                    }
                }

                _currentSong.value = updated
            } catch (_: Exception) {
            }
        }
    }
}
