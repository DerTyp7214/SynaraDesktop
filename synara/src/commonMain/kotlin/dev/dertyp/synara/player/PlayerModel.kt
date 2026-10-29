@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.LikeLevel
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.core.textName
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.*
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.utils.compress
import dev.dertyp.synara.utils.decompress
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.getString
import synara.synara.generated.resources.*
import kotlin.math.min
import kotlin.random.Random

private const val RADIO_BATCH_SIZE = 20
private const val RADIO_TOP_UP_THRESHOLD = 8
private const val MAX_AUTOMATIC_SKIPS = 5
private const val SHORT_END_TOLERANCE_MS = 3_000L
private const val RESTART_THRESHOLD_MS = 3_000L
private const val STATE_MOVE_ATTEMPTS = 3
private const val STATE_MOVE_RETRY_DELAY = 50L

class PlayerModel(
    private val rpcServiceManager: RpcServiceManager,
    private val audioPlayer: AudioPlayer,
    private val songService: SongServiceWrapper,
    private val userPlaylistService: UserPlaylistServiceWrapper,
    private val albumService: AlbumServiceWrapper,
    private val artistService: ArtistServiceWrapper,
    private val userService: UserServiceWrapper,
    private val radioService: RadioServiceWrapper,
    private val songCache: SongCache,
    private val timecodeTagStore: TimecodeTagStore,
    private val settings: Settings,
    private val snackbarManager: SnackbarManager,
    private val cbor: Cbor,
    private val logger: Logger,
    dispatchers: SynaraDispatchers,
    settingsFactory: SettingsFactory,
) {
    private val modelDispatcher = dispatchers.createNamed("PlayerModel", 2)
    private val stateDispatcher = dispatchers.createNamed("PlayerModelState")
    private val stateWriteLock = Mutex()
    private val mainDispatcher = dispatchers.main
    private val scope = CoroutineScope(mainDispatcher + SupervisorJob())
    private val fileSystem = FileSystem.SYSTEM
    private val path: Path = settingsFactory.getStatePath("player_state.v2.cbor.zstd").toPath()
    private val tempPath: Path = "$path.tmp".toPath()
    private val legacyCborPath: Path = settingsFactory.getStatePath("player_state.cbor.zstd").toPath()
    private val oldPath: Path = settingsFactory.getStatePath("player_state.pb").toPath()

    private val migrationProtoBuf = ProtoBuf {
        encodeDefaults = true
        serializersModule = cbor.serializersModule
    }

    private val logTag = LogTag("player")

    private var originalQueue: List<QueueEntry> = emptyList()
    private var setSourceJob: Job? = null
    private var automaticSkips = 0
    private var playRequested = false
    private var startGeneration = 0L
    private var currentStart: TrackStart? = null
    private var currentStartGeneration = 0L
    private var savedQueueId: Long? = null
    private var savedPosition = 0L
    private val pendingStart = MutableStateFlow<Long?>(null)

    private val _seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val seekEvents: SharedFlow<Long> = _seekEvents.asSharedFlow()

    private val _trackStarts = MutableSharedFlow<TrackStart>(extraBufferCapacity = 8)
    val trackStarts: SharedFlow<TrackStart> = _trackStarts.asSharedFlow()

    private val _currentSource = MutableStateFlow<PlaybackSource?>(null)
    val currentSource: StateFlow<PlaybackSource?> = _currentSource.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<UserSong?>(null)
    val currentSong: StateFlow<UserSong?> = _currentSong.asStateFlow()

    val currentSongId: PlatformUUID? get() = _queue.value.getOrNull(_currentIndex.value)?.let { songIdOf(it) }

    private val _loadedSongId = MutableStateFlow<PlatformUUID?>(null)
    val loadedSongId: StateFlow<PlatformUUID?> = _loadedSongId.asStateFlow()

    val playbackRequested: Boolean get() = playRequested

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleMode = MutableStateFlow(false)
    val shuffleMode: StateFlow<Boolean> = _shuffleMode.asStateFlow()

    private val _gaplessPlayback = MutableStateFlow(settings.get(SettingKey.GaplessPlayback, true))
    val gaplessPlayback: StateFlow<Boolean> = _gaplessPlayback.asStateFlow()

    private val _requestedWindow = MutableStateFlow<IntRange?>(null)

    private val _queueChanges = MutableSharedFlow<QueueChange>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val queueChanges: SharedFlow<QueueChange> = _queueChanges.asSharedFlow()

    private val _changeSerial = MutableStateFlow(0L)
    val changeSerial: StateFlow<Long> = _changeSerial.asStateFlow()

    private var suppressChanges = false

    var remotePlayHandler: ((queueId: Long) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) {
                _queue.value.getOrNull(_currentIndex.value)?.let { startPlayback(it, play = false, notifyRemote = false) }
            }
        }

    val isRemote: Boolean get() = remotePlayHandler != null

    data class TrackStart(val songId: PlatformUUID, val song: UserSong?, val startMs: Long)

    private sealed interface NextDeclaration {
        data class Prepare(val songId: PlatformUUID) : NextDeclaration
        data object Clear : NextDeclaration
        data object Keep : NextDeclaration
    }

    sealed class QueueChange {
        data object Replaced : QueueChange()
        data class Inserted(val anchor: Anchor, val entries: List<QueueEntry>, val index: Int) : QueueChange()
        data class Removed(val queueIds: List<Long>) : QueueChange()
        data class Moved(val queueId: Long, val toIndex: Int) : QueueChange()
        data class CurrentChanged(val queueId: Long?, val index: Int) : QueueChange()
        data class ModesChanged(val shuffle: Boolean, val repeat: RepeatMode) : QueueChange()

        enum class Anchor { AFTER_CURRENT, END }
    }

    data class QueueSnapshot(
        val original: List<QueueEntry>,
        val active: List<QueueEntry>,
        val currentIndex: Int,
        val shuffle: Boolean,
        val repeat: RepeatMode,
        val sourceId: String?
    )

    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val currentPosition: StateFlow<Long> = combine(audioPlayer.currentPosition, pendingStart) { position, pending ->
        pending ?: position
    }.stateIn(scope, SharingStarted.Eagerly, audioPlayer.currentPosition.value)
    val duration: StateFlow<Long> = combine(audioPlayer.duration, _currentSong, pendingStart) { loaded, song, pending ->
        if (pending != null) song?.duration ?: 0L else loaded
    }.stateIn(scope, SharingStarted.Eagerly, audioPlayer.duration.value)
    val volume: StateFlow<Float> = audioPlayer.volume
    val sampleRate: StateFlow<Int> = audioPlayer.sampleRate
    val bitsPerSample: StateFlow<Int> = audioPlayer.bitsPerSample
    val bitRate: StateFlow<Long> = audioPlayer.bitRate
    val fftData: StateFlow<FloatArray> = audioPlayer.fftData
    val stereoFftData: StateFlow<StereoSpectrum> = audioPlayer.stereoFftData

    val availableOutputDevices: StateFlow<List<String>> = audioPlayer.availableOutputDevices
    val currentOutputDevice: StateFlow<String?> = audioPlayer.currentOutputDevice

    val audioIntensity: StateFlow<Float> = audioPlayer.fftData.audioIntensityIn(scope)

    init {
        loadState()

        val savedVolume = settings.get(SettingKey.Volume, 0.5f)
        audioPlayer.setVolume(savedVolume)

        startRadioTopUpWatcher()

        scope.launch {
            combine(_queue, _currentIndex, _currentSource, _repeatMode, _shuffleMode) { q, idx, src, repeat, shuffle ->
                PlayerState(q, originalQueue, src, idx, repeat, shuffle, savedPositionFor(q.getOrNull(idx)))
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
            audioPlayer.onFinished.collect { songId ->
                if (songId == null || songId != _loadedSongId.value) return@collect
                val position = audioPlayer.currentPosition.value
                val duration = audioPlayer.duration.value
                if (duration > 0 && duration - position > SHORT_END_TOLERANCE_MS) {
                    handleLoadError(AudioLoadFailure(songId, AudioLoadError.Failed("Playback ended at $position ms of $duration ms")))
                } else {
                    handlePlaybackFinished()
                }
            }
        }

        scope.launch {
            audioPlayer.trackTransitions.collect { songId ->
                handleTrackTransition(songId)
            }
        }

        scope.launch {
            combine(_queue, _currentIndex, _repeatMode, _loadedSongId, _gaplessPlayback) { q, index, repeat, loaded, gapless ->
                declaredNext(q, index, repeat, loaded, gapless)
            }.distinctUntilChanged().collect { declaration ->
                declareNext(declaration)
            }
        }

        scope.launch {
            audioPlayer.isPlaying.collect { playing ->
                if (playing) automaticSkips = 0
            }
        }

        scope.launch {
            audioPlayer.loadErrors.collect { error ->
                handleLoadError(error)
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

    private fun startPlayback(entry: QueueEntry?, play: Boolean, notifyRemote: Boolean = true, startMs: Long = 0L) {
        val handler = remotePlayHandler
        if (handler != null && notifyRemote && play && entry != null) handler(entry.queueId)
        val playLocally = play && handler == null
        _loadedSongId.value = null
        if (entry == null) {
            playRequested = false
            startGeneration++
            pendingStart.value = null
            savedQueueId = null
            audioPlayer.stop()
            return
        }
        if (!playLocally) audioPlayer.stop()
        playRequested = playLocally
        pendingStart.value = startMs
        savedQueueId = entry.queueId
        savedPosition = startMs
        val generation = ++startGeneration
        if (handler != null) return
        scope.launch {
            if (generation != startGeneration) return@launch
            val id = resolveSongId(entry) ?: return@launch
            val song = knownSong(entry)
            if (generation != startGeneration) return@launch
            val start = pendingStart.value ?: startMs
            val trackStart = TrackStart(id, song, start)
            currentStart = trackStart
            currentStartGeneration = generation
            _trackStarts.tryEmit(trackStart)
            if (!playRequested) return@launch
            audioPlayer.load(id, playImmediately = true, startMs = start)
            audioPlayer.isLoading.first { !it }
            if (generation != startGeneration) return@launch
            pendingStart.value?.let { moved -> if (moved != start) audioPlayer.seekTo(moved) }
            pendingStart.value = null
            _loadedSongId.value = id
        }
    }

    private fun savedPositionFor(entry: QueueEntry?): Long =
        if (entry != null && entry.queueId == savedQueueId) savedPosition else 0L

    private fun currentState() = PlayerState(
        _queue.value,
        originalQueue,
        _currentSource.value,
        _currentIndex.value,
        _repeatMode.value,
        _shuffleMode.value,
        savedPositionFor(_queue.value.getOrNull(_currentIndex.value))
    )

    private suspend fun handleLoadError(failure: AudioLoadFailure) {
        if (isRemote) return
        val entry = _queue.value.getOrNull(_currentIndex.value) ?: return
        val songId = songIdOf(entry)
        if (failure.songId != songId) return
        val title = (_currentSong.value?.takeIf { it.id == songId } ?: knownSong(entry))?.title
        logger.error(logTag, "Failed to load track", data = "songId=$songId, title=$title, error=${failure.error}")
        val requested = playRequested
        val resumeAt = pendingStart.value ?: audioPlayer.currentPosition.value
        playRequested = false
        startGeneration++
        pendingStart.value = resumeAt
        _loadedSongId.value = null
        savedQueueId = entry.queueId
        savedPosition = resumeAt
        if (!requested) return

        val skips = automaticSkips + 1
        automaticSkips = skips
        val stop = _repeatMode.value == RepeatMode.ONE || skips >= min(_queue.value.size, MAX_AUTOMATIC_SKIPS)
        if (stop) audioPlayer.stop() else handlePlaybackFinished(play = true)

        snackbarManager.showSnackbar(
            when {
                stop -> getString(Res.string.playback_failed_stopped)
                title != null -> getString(Res.string.playback_failed_skipped, title)
                else -> getString(Res.string.playback_failed_skipped_unknown)
            }
        )
    }

    private fun emitChange(change: QueueChange) {
        if (suppressChanges) return
        if (_queueChanges.tryEmit(change)) _changeSerial.value++
    }

    private fun emitCurrentChanged() {
        val index = _currentIndex.value
        emitChange(QueueChange.CurrentChanged(_queue.value.getOrNull(index)?.queueId, index))
    }

    private fun songIdOf(entry: QueueEntry): PlatformUUID = when (entry) {
        is QueueEntry.Explicit -> entry.song.id
        is QueueEntry.FromSource -> entry.songId
    }

    private fun withQueueId(entry: QueueEntry, id: Long): QueueEntry = when (entry) {
        is QueueEntry.Explicit -> entry.copy(queueId = id)
        is QueueEntry.FromSource -> entry.copy(queueId = id)
    }

    private fun loadState() {
        try {
            val state = when {
                fileSystem.exists(path) -> cbor.decodeFromByteArray<PlayerState>(readCompressed(path))
                fileSystem.exists(legacyCborPath) -> {
                    val migrated = decodeLegacyPlayerState(cbor, readCompressed(legacyCborPath))
                    scope.launch(stateDispatcher) {
                        try {
                            writeState(migrated)
                            fileSystem.delete(legacyCborPath)
                        } catch (e: Exception) {
                            logger.error(logTag, "Failed to migrate the saved player state", e)
                        }
                    }
                    migrated
                }
                fileSystem.exists(oldPath) -> {
                    val bytes = fileSystem.read(oldPath) { readByteArray() }
                    val decoded = migrationProtoBuf.decodeFromByteArray<PlayerState>(bytes)
                    fileSystem.delete(oldPath)
                    decoded
                }
                else -> null
            } ?: return

            _queue.value = state.queue
            originalQueue = state.originalQueue
            _currentSource.value = state.source
            _currentIndex.value = state.currentIndex
            _repeatMode.value = state.repeatMode
            _shuffleMode.value = state.shuffleMode

            val currentEntry = _queue.value.getOrNull(_currentIndex.value)
            if (currentEntry != null) {
                startPlayback(currentEntry, play = false, startMs = state.lastPosition.coerceAtLeast(0L))
            }
        } catch (e: Exception) {
            logger.error(logTag, "Failed to load the saved player state", e)
            _queue.value = emptyList()
            originalQueue = emptyList()
            _currentIndex.value = -1
        }
    }

    private fun readCompressed(file: Path): ByteArray {
        val bytes = fileSystem.read(file) { readByteArray() }
        return try {
            decompress(bytes)
        } catch (_: Exception) {
            bytes
        }
    }

    private suspend fun writeState(state: PlayerState) = stateWriteLock.withLock {
        val compressed = compress(cbor.encodeToByteArray(state))
        fileSystem.delete(tempPath, mustExist = false)
        fileSystem.write(tempPath) {
            write(compressed)
        }
        repeat(STATE_MOVE_ATTEMPTS) { attempt ->
            try {
                fileSystem.atomicMove(tempPath, path)
                return@withLock
            } catch (e: IOException) {
                if (attempt == STATE_MOVE_ATTEMPTS - 1) {
                    logger.warning(logTag, "Atomic replace of the player state was denied, replacing without it", e.message)
                } else {
                    delay(STATE_MOVE_RETRY_DELAY)
                }
            }
        }
        if (!fileSystem.exists(tempPath)) return@withLock
        fileSystem.delete(path, mustExist = false)
        fileSystem.atomicMove(tempPath, path)
    }

    private fun saveState(state: PlayerState) {
        scope.launch(stateDispatcher) {
            try {
                writeState(state)
            } catch (e: Exception) {
                logger.error(logTag, "Failed to save the player state", e)
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

    private suspend fun knownSong(entry: QueueEntry): UserSong? = when (entry) {
        is QueueEntry.Explicit -> entry.song
        is QueueEntry.FromSource -> songCache.get(entry.songId)
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

        val song = songCache.get(id) ?: run {
            rpcServiceManager.awaitAuthentication()
            songService.byId(id)?.also { songCache.put(it) }
        }
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
            scope.launch(modelDispatcher) {
                try {
                    val neededIds = toFetchFromSource.map { it.songId }
                        .distinct()
                        .filter { songCache.get(it) == null }

                    if (neededIds.isNotEmpty()) {
                        rpcServiceManager.awaitAuthentication()
                        neededIds.chunked(50).forEach { chunk ->
                            val response = songService.byIds(chunk)
                            songCache.putAll(response)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun nextIndex(size: Int, current: Int, repeat: RepeatMode): Int = when (repeat) {
        RepeatMode.ONE -> current
        RepeatMode.ALL -> if (size > 0) (current + 1) % size else -1
        RepeatMode.OFF -> current + 1
    }

    private fun declaredNext(
        queue: List<QueueEntry>,
        index: Int,
        repeat: RepeatMode,
        loaded: PlatformUUID?,
        gapless: Boolean,
    ): NextDeclaration {
        val current = queue.getOrNull(index)
        return when {
            !gapless -> NextDeclaration.Clear
            loaded == null || current == null || songIdOf(current) != loaded -> NextDeclaration.Keep
            else -> queue.getOrNull(nextIndex(queue.size, index, repeat))
                ?.let { NextDeclaration.Prepare(songIdOf(it)) }
                ?: NextDeclaration.Clear
        }
    }

    private fun declareNext(declaration: NextDeclaration) {
        when (declaration) {
            is NextDeclaration.Prepare -> audioPlayer.prepareNext(declaration.songId)
            NextDeclaration.Clear -> audioPlayer.clearNext()
            NextDeclaration.Keep -> Unit
        }
    }

    private fun transitionIndex(songId: PlatformUUID): Int? {
        val queue = _queue.value
        val currentIndex = _currentIndex.value
        val current = queue.getOrNull(currentIndex) ?: return null
        if (_loadedSongId.value != songIdOf(current)) return null
        val index = nextIndex(queue.size, currentIndex, _repeatMode.value)
        if (queue.getOrNull(index)?.let { songIdOf(it) } == songId) return index
        handlePlaybackFinished()
        return null
    }

    private suspend fun handleTrackTransition(songId: PlatformUUID) {
        val lookup = transitionIndex(songId) ?: return
        val song = knownSong(_queue.value[lookup])
        val index = transitionIndex(songId) ?: return
        val queue = _queue.value
        val currentIndex = _currentIndex.value
        val repeat = _repeatMode.value
        startGeneration++
        val trackStart = TrackStart(songId, song, 0L)
        currentStart = trackStart
        currentStartGeneration = startGeneration
        _trackStarts.tryEmit(trackStart)
        savedQueueId = queue[index].queueId
        savedPosition = audioPlayer.currentPosition.value
        _loadedSongId.value = songId
        if (repeat != RepeatMode.ONE) {
            _currentIndex.value = index
            emitCurrentChanged()
        }
        val gapless = _gaplessPlayback.value
        val declaration = declaredNext(queue, index, repeat, songId, gapless)
        if (declaration == declaredNext(queue, currentIndex, repeat, songIdOf(queue[currentIndex]), gapless)) declareNext(declaration)
    }

    private fun handlePlaybackFinished(play: Boolean = playRequested) {
        val queue = _queue.value
        val repeatOne = _repeatMode.value == RepeatMode.ONE
        val index = nextIndex(queue.size, _currentIndex.value, _repeatMode.value)
        val entry = queue.getOrNull(index)
        if (entry == null) {
            if (_repeatMode.value == RepeatMode.OFF) {
                startPlayback(null, play = false)
                _currentIndex.value = -1
            }
            return
        }
        if (!repeatOne) {
            _currentIndex.value = index
            emitCurrentChanged()
        }
        startPlayback(entry, play = play, notifyRemote = !repeatOne)
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

    fun playAtIndex(index: Int, play: Boolean = true) {
        automaticSkips = 0
        if (index in _queue.value.indices) {
            val entry = _queue.value[index]
            _currentIndex.value = index
            emitCurrentChanged()
            startPlayback(entry, play = play)
        }
    }

    fun playSong(song: UserSong) {
        automaticSkips = 0
        val entry = QueueEntry.Explicit(song)
        _currentSource.value = PlaybackSource.Manual
        originalQueue = listOf(entry)
        _queue.value = listOf(entry)
        _currentIndex.value = 0
        emitChange(QueueChange.Replaced)
        scope.launch { songCache.put(song) }
        startPlayback(entry, play = true)
    }

    fun playRadio(sessionId: PlatformUUID, displayName: String? = null) {
        automaticSkips = 0
        setSourceJob?.cancel()
        radioTopUpJob?.cancel()
        setSourceJob = scope.launch {
            val source = PlaybackSource.Radio(sessionId, displayName)
            _currentSource.value = source

            val entries = fetchRadioBatch(sessionId)
            if (entries.isEmpty()) {
                snackbarManager.showSnackbar(getString(Res.string.radio_no_songs))
                return@launch
            }

            originalQueue = entries
            _queue.value = entries
            playAtIndex(0)
        }
    }

    private suspend fun fetchRadioBatch(sessionId: PlatformUUID): List<QueueEntry> {
        return try {
            withContext(modelDispatcher) {
                radioService.observeRadio(sessionId)
                    .take(RADIO_BATCH_SIZE)
                    .toList()
                    .map { QueueEntry.FromSource(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(logTag, "Failed to fetch a radio batch", e, data = "sessionId=$sessionId")
            emptyList()
        }
    }

    private var radioTopUpJob: Job? = null

    private fun startRadioTopUpWatcher() {
        scope.launch {
            combine(_queue, _currentIndex, _currentSource) { q, idx, src ->
                Triple(q, idx, src)
            }.collect { (q, idx, src) ->
                val radio = src as? PlaybackSource.Radio ?: return@collect
                if (idx < 0 || q.isEmpty()) return@collect
                if (q.size - idx > RADIO_TOP_UP_THRESHOLD) return@collect
                if (radioTopUpJob?.isActive == true) return@collect

                radioTopUpJob = scope.launch {
                    val entries = fetchRadioBatch(radio.sessionId)
                    if (entries.isNotEmpty() && _currentSource.value == radio) {
                        originalQueue = originalQueue + entries
                        _queue.value += entries
                    }
                }
            }
        }
    }

    fun playQueue(playbackQueue: PlaybackQueue, startIndex: Int = 0) {
        automaticSkips = 0
        setSourceJob?.cancel()
        setSourceJob = scope.launch {
            val previousSource = _currentSource.value
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
                emitChange(QueueChange.Replaced)
            } else if (source != null) {
                val startSong = try {
                    source.getSongAt(startIndex) as? UserSong
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _currentSource.value = previousSource
                    logger.error(logTag, "Failed to fetch the first song of a source", e, data = "source=${source.id}, index=$startIndex")
                    snackbarManager.showSnackbar(getString(Res.string.playback_source_unavailable))
                    return@launch
                }

                val startEntry = if (startSong != null) {
                    val entry = QueueEntry.FromSource(startSong.id)
                    _queue.value = listOf(entry)
                    _currentIndex.value = 0
                    _currentSong.value = startSong
                    songCache.put(startSong)
                    startPlayback(entry, play = true, notifyRemote = false)
                    entry
                } else null

                launch(modelDispatcher) {
                    val collectedEntries = try {
                        resolvePlaybackQueueItems(playbackQueue)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(logTag, "Failed to fetch the songs of a source", e, data = "source=${source.id}")
                        withContext(mainDispatcher) {
                            if (startSong == null) {
                                _currentSource.value = previousSource
                                snackbarManager.showSnackbar(getString(Res.string.playback_source_unavailable))
                            } else {
                                originalQueue = _queue.value
                                emitChange(QueueChange.Replaced)
                            }
                        }
                        return@launch
                    }
                    withContext(mainDispatcher) {
                        val entries = if (startEntry != null && collectedEntries.none { songIdOf(it) == startEntry.songId }) {
                            listOf(startEntry) + collectedEntries
                        } else {
                            collectedEntries
                        }
                        originalQueue = entries
                        val currentSongId = startSong?.id

                        if (_shuffleMode.value) {
                            val shuffled = entries.toMutableList()
                            val currentEntry = shuffled.find { (it as? QueueEntry.FromSource)?.songId == currentSongId }
                            if (currentEntry != null) {
                                shuffled.remove(currentEntry)
                                shuffled.add(0, currentEntry)
                            } else {
                                shuffled.shuffle()
                            }
                            _queue.value = shuffled
                            _currentIndex.value = if (shuffled.isEmpty()) -1 else 0
                        } else {
                            _queue.value = entries
                            if (currentSongId != null) {
                                _currentIndex.value = entries.indexOfFirst { entry ->
                                    (entry as? QueueEntry.FromSource)?.songId == currentSongId
                                }.coerceAtLeast(0)
                            } else if (entries.isEmpty()) {
                                _currentIndex.value = -1
                            } else {
                                _currentIndex.value = startIndex.coerceIn(0, entries.size - 1)
                            }
                        }
                        updateWindow()
                        emitChange(QueueChange.Replaced)
                        if (isRemote) startPlayback(_queue.value.getOrNull(_currentIndex.value), play = true)
                    }
                }
                return@launch
            }

            val currentItem = _queue.value.getOrNull(_currentIndex.value)
            if (currentItem != null) startPlayback(currentItem, play = true)
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
            is PlaybackSource.Album -> albumService.byId(source.albumId)?.textName()
            is PlaybackSource.Artist -> artistService.byId(source.artistId)?.name
            is PlaybackSource.Playlist -> userPlaylistService.byId(source.playlistId)?.name
            is PlaybackSource.AllSongs -> getString(Res.string.songs)
            is PlaybackSource.LikedSongs -> getString(Res.string.favorite)
            is PlaybackSource.SuperLikedSongs -> getString(Res.string.liked_filter_super)
            is PlaybackSource.Radio -> source.name ?: getString(Res.string.radio)
            is PlaybackSource.Manual -> null
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

            withContext(mainDispatcher) {
                val at = _queue.value.size
                val appended = if (_shuffleMode.value) newItems.shuffled() else newItems
                originalQueue = originalQueue + appended
                _queue.value += appended

                emitChange(QueueChange.Inserted(QueueChange.Anchor.END, appended, at))

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

            withContext(mainDispatcher) {
                if (_currentIndex.value == -1) {
                    playQueue(PlaybackQueue(items = newItems, source = playbackQueue.source))
                    snackbarManager.showSnackbar(message)
                    return@withContext
                }

                val insertIndex = _currentIndex.value + 1
                val newQueue = _queue.value.toMutableList()
                newQueue.addAll(insertIndex, newItems)
                _queue.value = newQueue

                if (_shuffleMode.value) {
                    originalQueue = originalQueue + newItems
                } else {
                    val currentEntry = _queue.value.getOrNull(_currentIndex.value)
                    val origInsertIndex = originalQueue.indexOf(currentEntry).let { if (it == -1) originalQueue.size else it + 1 }
                    val newOrigQueue = originalQueue.toMutableList()
                    newOrigQueue.addAll(origInsertIndex, newItems)
                    originalQueue = newOrigQueue
                }

                emitChange(QueueChange.Inserted(QueueChange.Anchor.AFTER_CURRENT, newItems, insertIndex))

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

        emitChange(QueueChange.Moved(entry.queueId, toIndex))
    }

    fun removeFromQueue(entry: QueueEntry) {
        val index = _queue.value.indexOf(entry)
        if (index == -1) return

        val newQueue = _queue.value.toMutableList()
        newQueue.removeAt(index)

        if (newQueue.isEmpty()) {
            clearQueue()
            return
        }

        val currentIdx = _currentIndex.value
        _queue.value = newQueue

        val origIndex = originalQueue.indexOf(entry)
        if (origIndex != -1) {
            val newOrig = originalQueue.toMutableList()
            newOrig.removeAt(origIndex)
            originalQueue = newOrig
        }

        emitChange(QueueChange.Removed(listOf(entry.queueId)))

        if (index == currentIdx) {
            playAtIndex(if (index < newQueue.size) index else 0, play = playRequested)
        } else if (index < currentIdx) {
            _currentIndex.value = currentIdx - 1
        }
    }

    fun clearQueue() {
        playRequested = false
        startGeneration++
        pendingStart.value = null
        _loadedSongId.value = null
        savedQueueId = null
        audioPlayer.stop()
        _queue.value = emptyList()
        originalQueue = emptyList()
        _currentIndex.value = -1
        _currentSong.value = null
        _currentSource.value = null
        emitChange(QueueChange.Replaced)
    }

    fun togglePlayPause() {
        if (playRequested) pause() else play()
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
        val previous = when {
            prevIndex in _queue.value.indices -> prevIndex
            _repeatMode.value == RepeatMode.ALL && _queue.value.isNotEmpty() -> _queue.value.size - 1
            else -> null
        }
        val startMs = trackStartMs()
        if (previous != null && currentPosition.value - startMs <= RESTART_THRESHOLD_MS) {
            playAtIndex(previous)
        } else if (_currentIndex.value in _queue.value.indices) {
            seekTo(startMs)
        }
    }

    private fun trackStartMs(): Long {
        if (isRemote) return 0L
        val id = currentSongId ?: return 0L
        val tags = timecodeTagStore.cached(id) ?: _currentSong.value?.takeIf { it.id == id }?.playbackTags ?: return 0L
        return TimecodeTagRules.startSeek(tags, duration.value)?.positionMs ?: 0L
    }

    fun stop() {
        playRequested = false
        startGeneration++
        _loadedSongId.value = null
        audioPlayer.stop()
        if (isRemote) return
        val entry = _queue.value.getOrNull(_currentIndex.value) ?: return
        pendingStart.value = 0L
        savedQueueId = entry.queueId
        savedPosition = 0L
        saveState(currentState())
    }

    fun pause() {
        playRequested = false
        audioPlayer.pause()
        val entry = _queue.value.getOrNull(_currentIndex.value) ?: return
        savedQueueId = entry.queueId
        savedPosition = pendingStart.value ?: audioPlayer.currentPosition.value
        saveState(currentState())
    }

    fun play() {
        automaticSkips = 0
        if (isRemote) return
        val entry = _queue.value.getOrNull(_currentIndex.value)
        if (entry == null) {
            if (_queue.value.isNotEmpty()) playAtIndex(0)
            return
        }
        val pending = pendingStart.value
        if (pending != null) {
            if (!playRequested) startPlayback(entry, play = true, startMs = pending)
            return
        }
        playRequested = true
        audioPlayer.play()
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        val unloaded = pendingStart.value != null && !isRemote
        if (unloaded) {
            pendingStart.value = target
        } else {
            audioPlayer.seekTo(positionMs)
        }
        if (!isRemote && (unloaded || !audioPlayer.isPlaying.value)) {
            _queue.value.getOrNull(_currentIndex.value)?.let { entry ->
                savedQueueId = entry.queueId
                savedPosition = target
                saveState(currentState())
            }
        }
        _seekEvents.tryEmit(positionMs)
    }

    fun seekTo(positionMs: Long, start: TrackStart) {
        scope.launch { if (isCurrentStart(start)) seekTo(positionMs) }
    }

    fun finishTrack(start: TrackStart) {
        scope.launch { if (isCurrentStart(start)) handlePlaybackFinished() }
    }

    private fun isCurrentStart(start: TrackStart): Boolean =
        start === currentStart && currentStartGeneration == startGeneration

    fun setFadeGain(gain: Float) {
        audioPlayer.setFadeGain(gain)
    }

    fun setVolume(value: Float) {
        audioPlayer.setVolume(value)
        settings.put(SettingKey.Volume, value)
    }

    fun setGaplessPlayback(enabled: Boolean) {
        _gaplessPlayback.value = enabled
        settings.put(SettingKey.GaplessPlayback, enabled)
    }

    fun setOutputDevice(device: String?) {
        audioPlayer.setOutputDevice(device)
    }

    fun toggleShuffle() {
        setShuffleMode(!_shuffleMode.value)
    }

    fun setShuffleMode(enabled: Boolean) {
        if (_currentSource.value?.isEndless == true) return
        if (_shuffleMode.value == enabled) return

        val currentEntry = _queue.value.getOrNull(_currentIndex.value)
        _shuffleMode.value = enabled

        if (enabled) {
            val shuffled = originalQueue.shuffled().toMutableList()
            if (currentEntry != null) {
                shuffled.remove(currentEntry)
                shuffled.add(0, currentEntry)
                _currentIndex.value = 0
            }
            _queue.value = shuffled
            emitChange(QueueChange.Replaced)
        } else if (currentEntry == null) {
            _queue.value = originalQueue
            emitChange(QueueChange.ModesChanged(false, _repeatMode.value))
        } else {
            val byQueueId = originalQueue.indexOfFirst { it.queueId == currentEntry.queueId }
            val index = if (byQueueId >= 0) {
                byQueueId
            } else {
                val currentSongId = songIdOf(currentEntry)
                originalQueue.indexOfFirst { songIdOf(it) == currentSongId }
            }
            if (index < 0) originalQueue = listOf(currentEntry) + originalQueue
            _queue.value = originalQueue
            _currentIndex.value = index.coerceAtLeast(0)
            emitChange(
                if (byQueueId >= 0) QueueChange.ModesChanged(false, _repeatMode.value) else QueueChange.Replaced
            )
        }
    }

    fun setRepeatMode(repeatMode: RepeatMode) {
        if (_repeatMode.value == repeatMode) return
        _repeatMode.value = repeatMode
        emitChange(QueueChange.ModesChanged(_shuffleMode.value, repeatMode))
    }

    fun toggleRepeat() {
        setRepeatMode(
            when (_repeatMode.value) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        )
    }

    fun toggleLike() {
        _currentSong.value?.let { toggleLike(it) }
    }

    fun toggleLike(song: UserSong) {
        scope.launch {
            try {
                setLikeLevel(song.id, if (song.isFavourite == true) LikeLevel.NONE else LikeLevel.LIKE)
            } catch (e: Exception) {
                logger.error(logTag, "Failed to toggle the like level", e)
            }
        }
    }

    fun toggleSuperLike() {
        _currentSong.value?.let { toggleSuperLike(it) }
    }

    fun toggleSuperLike(song: UserSong) {
        scope.launch {
            try {
                setLikeLevel(song.id, if (song.likeLevel == LikeLevel.SUPER) LikeLevel.LIKE else LikeLevel.SUPER)
            } catch (e: Exception) {
                logger.error(logTag, "Failed to toggle the super like level", e)
            }
        }
    }

    suspend fun setLikeLevel(songId: PlatformUUID, level: LikeLevel): UserSong? {
        val updated = songService.setLikeLevel(songId, level) ?: return null
        songCache.put(updated)
        songCache.notifyLikedSongsChanged()
        return updated
    }

    fun addSongsToPlaylist(playlistId: PlatformUUID, playbackQueue: PlaybackQueue) {
        scope.launch {
            try {
                val entries = resolvePlaybackQueueItems(playbackQueue)
                val songIds = entries.map {
                    when (it) {
                        is QueueEntry.Explicit -> it.song.id
                        is QueueEntry.FromSource -> it.songId
                    }
                }
                if (songIds.isEmpty()) return@launch

                userPlaylistService.addToPlaylist(playlistId, songIds.map { currentTimeMillis() to it })
                songCache.notifyPlaylistChanged(playlistId)
                songCache.notifyPlaylistsChanged()

                val playlist = userPlaylistService.byId(playlistId)
                val itemName = if (entries.size == 1) {
                    resolveSong(entries[0])?.title
                } else {
                    getSourceDisplayName(playbackQueue.source)
                }

                val message = if (playlist != null && itemName != null) {
                    getString(Res.string.added_to_playlist_item, itemName, playlist.name)
                } else {
                    getString(Res.string.added_to_playlist)
                }
                snackbarManager.showSnackbar(message)
            } catch (e: Exception) {
                logger.error(logTag, "Failed to add songs to the playlist", e)
            }
        }
    }

    fun createPlaylist(name: String, playbackQueue: PlaybackQueue? = null) {
        scope.launch {
            try {
                val currentUser = userService.me()
                val insertable = InsertablePlaylist(name = name, songPaths = emptyList())
                val playlistId = userPlaylistService.getOrAddPlaylist(currentUser.id, null, insertable)

                if (playbackQueue != null) {
                    val entries = resolvePlaybackQueueItems(playbackQueue)
                    val songIds = entries.map {
                        when (it) {
                            is QueueEntry.Explicit -> it.song.id
                            is QueueEntry.FromSource -> it.songId
                        }
                    }
                    if (songIds.isNotEmpty()) {
                        userPlaylistService.addToPlaylist(playlistId, songIds.map { currentTimeMillis() to it })
                        songCache.notifyPlaylistChanged(playlistId)
                    }
                }

                songCache.notifyPlaylistsChanged()
                snackbarManager.showSnackbar(getString(Res.string.playlist_created))
            } catch (e: Exception) {
                logger.error(logTag, "Failed to create the playlist", e)
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
                logger.error(logTag, "Failed to remove the song from the playlist", e)
            }
        }
    }

    fun snapshot(): QueueSnapshot = QueueSnapshot(
        original = originalQueue,
        active = _queue.value,
        currentIndex = _currentIndex.value,
        shuffle = _shuffleMode.value,
        repeat = _repeatMode.value,
        sourceId = _currentSource.value?.id
    )

    fun applyRemoteQueue(snapshot: QueueSnapshot, keepCurrentQueueId: Long? = null) {
        automaticSkips = 0
        suppressChanges = true
        try {
            setSourceJob?.cancel()
            radioTopUpJob?.cancel()

            val previousSongId = _currentSong.value?.id
            val wasPlaying = playRequested

            originalQueue = snapshot.original
            _queue.value = snapshot.active
            _shuffleMode.value = snapshot.shuffle
            _repeatMode.value = snapshot.repeat
            _currentSource.value = null
            _requestedWindow.value = null

            val keptIndex = keepCurrentQueueId
                ?.let { id -> snapshot.active.indexOfFirst { it.queueId == id } }
                ?.takeIf { it >= 0 }
            _currentIndex.value = when {
                snapshot.active.isEmpty() -> -1
                keptIndex != null -> keptIndex
                else -> snapshot.currentIndex.coerceIn(0, snapshot.active.size - 1)
            }

            val currentEntry = _queue.value.getOrNull(_currentIndex.value)
            val targetId = currentEntry?.let { songIdOf(it) }
            when {
                targetId == null -> startPlayback(null, play = false, notifyRemote = false)
                targetId == previousSongId -> savedQueueId = currentEntry.queueId
                else -> startPlayback(currentEntry, play = wasPlaying, notifyRemote = false)
            }
        } finally {
            suppressChanges = false
        }
    }

    fun reassignQueueIds(entries: List<QueueEntry>, taken: Set<Long>): List<QueueEntry> {
        if (entries.isEmpty()) return entries
        val used = HashSet(taken)
        _queue.value.forEach { used.add(it.queueId) }
        originalQueue.forEach { used.add(it.queueId) }

        return entries.map { entry ->
            if (used.add(entry.queueId)) return@map entry
            var candidate = Random.nextLong()
            while (!used.add(candidate)) candidate = Random.nextLong()
            withQueueId(entry, candidate)
        }
    }

    fun insertEntries(anchor: QueueChange.Anchor, entries: List<QueueEntry>) {
        if (entries.isEmpty()) return

        val newQueue = _queue.value.toMutableList()
        val at = when (anchor) {
            QueueChange.Anchor.AFTER_CURRENT -> (_currentIndex.value + 1).coerceIn(0, newQueue.size)
            QueueChange.Anchor.END -> newQueue.size
        }
        newQueue.addAll(at, entries)
        _queue.value = newQueue
        originalQueue = originalQueue + entries

        emitChange(QueueChange.Inserted(anchor, entries, at))

        if (_currentIndex.value == -1) {
            playAtIndex(0)
        } else if (at <= _currentIndex.value) {
            _currentIndex.value += entries.size
        }
    }

    fun removeByQueueIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val dropped = ids.toSet()
        val currentList = _queue.value
        if (currentList.none { it.queueId in dropped }) return

        val oldIndex = _currentIndex.value
        val currentRemoved = currentList.getOrNull(oldIndex)?.let { it.queueId in dropped } == true
        val removedBefore = currentList.take(oldIndex.coerceAtLeast(0)).count { it.queueId in dropped }
        val remaining = currentList.filter { it.queueId !in dropped }

        _queue.value = remaining
        originalQueue = originalQueue.filter { it.queueId !in dropped }

        emitChange(QueueChange.Removed(ids))

        when {
            remaining.isEmpty() -> {
                startPlayback(null, play = false, notifyRemote = false)
                _currentIndex.value = -1
                _currentSong.value = null
            }

            oldIndex < 0 -> _currentIndex.value = -1

            currentRemoved -> playAtIndex(
                (oldIndex - removedBefore).coerceIn(0, remaining.size - 1),
                play = playRequested
            )

            else -> _currentIndex.value = (oldIndex - removedBefore).coerceIn(0, remaining.size - 1)
        }
    }

    fun moveByQueueId(queueId: Long, toIndex: Int) {
        val from = _queue.value.indexOfFirst { it.queueId == queueId }
        if (from < 0) return
        moveQueueItem(from, toIndex.coerceIn(0, (_queue.value.size - 1).coerceAtLeast(0)))
    }

    fun skipToQueueId(queueId: Long): Boolean {
        val index = _queue.value.indexOfFirst { it.queueId == queueId }
        if (index < 0) return false
        if (index == _currentIndex.value) {
            emitCurrentChanged()
        } else {
            playAtIndex(index)
        }
        return true
    }

    fun getPlaybackState(): PlaybackState {
        return PlaybackState(
            queue = _queue.value.mapIndexed { index, entry ->
                when (entry) {
                    is QueueEntry.Explicit -> PlaybackState.QueueEntry.WithSong(entry.song, index.toLong())
                    is QueueEntry.FromSource -> PlaybackState.QueueEntry.FromSource(entry.songId, index.toLong())
                }
            },
            currentIndex = _currentIndex.value,
            isPlaying = audioPlayer.isPlaying.value,
            positionMs = currentPosition.value,
            isShuffled = _shuffleMode.value,
            repeatMode = _repeatMode.value
        )
    }
}
