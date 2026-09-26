@file:OptIn(ExperimentalSerializationApi::class, FlowPreview::class)

package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.utils.compress
import dev.dertyp.synara.utils.decompress
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

private val PODCAST_PLAYER_TAG = LogTag("podcast-player")

class PodcastPlayerModel(
    private val engine: PodcastAudioEngine,
    private val podcastService: IPodcastService,
    private val progressStore: PodcastProgressStore,
    private val reporter: PodcastProgressReporter,
    private val playerModel: PlayerModel,
    private val rpcServiceManager: RpcServiceManager,
    private val cbor: Cbor,
    private val logger: Logger,
    dispatchers: SynaraDispatchers,
    settingsFactory: SettingsFactory,
) : PodcastPlayer {
    companion object {
        private val REPORT_INTERVAL = 10.seconds
        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val RESTORE_CHUNK_SIZE = 500
        private const val SAVE_POSITION_BUCKET_MS = 5_000L
        private const val SAVE_DEBOUNCE_MS = 1_000L
        private const val COMPLETION_TOLERANCE_MS = 30_000L
        private const val COMPLETION_TOLERANCE_PERCENT = 5L

        fun snapSpeed(speed: Float): Float =
            PodcastPlayer.SPEEDS.minBy { abs(it - speed) }
    }

    private val ioDispatcher = dispatchers.io
    private val stateDispatcher = dispatchers.createNamed("PodcastPlayerModel", 1)
    private val scope = CoroutineScope(dispatchers.main + SupervisorJob())
    private val fileSystem = FileSystem.SYSTEM
    private val path: Path = settingsFactory.getStatePath("podcast_player_state.cbor.zstd").toPath()

    private val _queue = MutableStateFlow<List<PodcastEpisode>>(emptyList())
    override val queue: StateFlow<List<PodcastEpisode>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    override val currentEpisode: StateFlow<PodcastEpisode?> = combine(_queue, _currentIndex) { q, i -> q.getOrNull(i) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val isPlaying: StateFlow<Boolean> = engine.isPlaying
    override val isLoading: StateFlow<Boolean> = engine.isLoading

    private val pendingResume = MutableStateFlow<Long?>(null)

    override val position: StateFlow<Long> = combine(engine.position, pendingResume) { p, pending -> pending ?: p }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    override val duration: StateFlow<Long> = combine(engine.duration, currentEpisode) { d, episode ->
        if (d > 0) d else episode?.knownDurationMs ?: 0L
    }.stateIn(scope, SharingStarted.Eagerly, 0L)

    private val _speed = MutableStateFlow(snapSpeed(Config.podcastPlaybackSpeed.value))
    override val speed: StateFlow<Float> = _speed.asStateFlow()

    override val volume: StateFlow<Float> = playerModel.volume
    override val fftData: StateFlow<FloatArray> = engine.fftData
    override val sampleRate: StateFlow<Int> = engine.sampleRate

    override val hasContent: StateFlow<Boolean> = combine(_queue, Config.isPodcastsEnabled) { q, enabled ->
        enabled && q.isNotEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _errors = MutableSharedFlow<PodcastPlayerError>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val errors: SharedFlow<PodcastPlayerError> = _errors.asSharedFlow()

    private var loadedEpisodeId: PlatformUUID? = null
    private var restoreDone = false
    private var playShowJob: Job? = null

    init {
        engine.setSpeed(_speed.value)

        scope.launch {
            Config.podcastPlaybackSpeed.collect {
                val snapped = snapSpeed(it)
                _speed.value = snapped
                engine.setSpeed(snapped)
            }
        }

        scope.launch {
            playerModel.volume.collect { engine.setVolume(it) }
        }

        scope.launch {
            playerModel.currentOutputDevice.collect { engine.setOutputDevice(it) }
        }

        scope.launch {
            engine.errors.collect { _errors.emit(it) }
        }

        scope.launch {
            engine.finished.collect { handleFinished() }
        }

        scope.launch {
            engine.isPlaying.collectLatest { playing ->
                if (!playing) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    delay(REPORT_INTERVAL)
                    reportCurrent()
                }
            }
        }

        scope.launch {
            progressStore.updates.collect { handleProgressUpdate(it) }
        }

        scope.launch {
            combine(
                _queue.map { list -> list.map { it.id } }.distinctUntilChanged(),
                _currentIndex,
                position.map { it / SAVE_POSITION_BUCKET_MS }.distinctUntilChanged()
            ) { ids, index, _ -> ids to index }
                .debounce(SAVE_DEBOUNCE_MS)
                .collect { (ids, index) ->
                    if (!restoreDone && ids.isEmpty()) return@collect
                    saveState(PodcastPlayerState(ids, index, position.value, currentTimeMillis()))
                }
        }

        restoreState()
    }

    override fun playEpisode(episode: PodcastEpisode, startMs: Long?) {
        playShowJob?.cancel()
        reportOutgoing()
        _queue.value = listOf(episode.withProgress(progressStore.latest.value))
        _currentIndex.value = 0
        loadCurrent(play = true, startMs = startMs)
    }

    override fun playEpisodes(episodes: List<PodcastEpisode>, startIndex: Int) {
        if (episodes.isEmpty()) return
        playShowJob?.cancel()
        reportOutgoing()
        _queue.value = episodes.distinctBy { it.id }.withProgress(progressStore.latest.value)
        val targetId = episodes.getOrNull(startIndex)?.id
        _currentIndex.value = _queue.value.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        loadCurrent(play = true)
    }

    override fun playShow(showId: PlatformUUID) {
        playShowJob?.cancel()
        playShowJob = scope.launch {
            try {
                val target = podcastService.resolveContinuation(showId)?.episode ?: return@launch
                val episodes = podcastService.continuationQueue(target)
                playEpisodes(episodes, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(PODCAST_PLAYER_TAG, e.message ?: "Failed to play show", e)
                _errors.emit(PodcastPlayerError.Failed(e.message))
            }
        }
    }

    override fun playNext(episode: PodcastEpisode) {
        val index = _currentIndex.value
        if (_queue.value.isEmpty() || index !in _queue.value.indices) {
            playEpisode(episode)
            return
        }
        if (_queue.value[index].id == episode.id) return

        val list = _queue.value.toMutableList()
        val existing = list.indexOfFirst { it.id == episode.id }
        var current = index
        if (existing != -1) {
            list.removeAt(existing)
            if (existing < current) current--
        }
        list.add(current + 1, episode.withProgress(progressStore.latest.value))
        _queue.value = list
        _currentIndex.value = current
    }

    override fun addToQueue(episodes: List<PodcastEpisode>) {
        val known = _queue.value.map { it.id }.toSet()
        val added = episodes.distinctBy { it.id }
            .filter { it.id !in known }
            .withProgress(progressStore.latest.value)
        if (added.isEmpty()) return
        val wasEmpty = _currentIndex.value !in _queue.value.indices
        _queue.value += added
        if (wasEmpty) {
            _currentIndex.value = 0
            loadCurrent(play = true)
        }
    }

    override fun removeFromQueue(index: Int) {
        val list = _queue.value
        if (index !in list.indices) return
        val current = _currentIndex.value

        if (index == current) {
            val wasPlaying = engine.isPlaying.value
            reportOutgoing()
            val remaining = list.toMutableList().apply { removeAt(index) }
            _queue.value = remaining
            if (remaining.isEmpty()) {
                _currentIndex.value = -1
                unload()
            } else {
                _currentIndex.value = index.coerceAtMost(remaining.size - 1)
                loadCurrent(play = wasPlaying)
            }
            return
        }

        _queue.value = list.toMutableList().apply { removeAt(index) }
        if (index < current) _currentIndex.value = current - 1
    }

    override fun moveInQueue(from: Int, to: Int) {
        val list = _queue.value
        if (from !in list.indices || to !in list.indices || from == to) return

        val newList = list.toMutableList()
        val entry = newList.removeAt(from)
        newList.add(to, entry)
        _queue.value = newList

        when (val current = _currentIndex.value) {
            from -> _currentIndex.value = to
            in (from + 1)..to -> _currentIndex.value = current - 1
            in to..<from -> _currentIndex.value = current + 1
        }
    }

    override fun clearQueue() {
        val current = _queue.value.getOrNull(_currentIndex.value)
        if (current == null) {
            _queue.value = emptyList()
            _currentIndex.value = -1
            unload()
            return
        }
        _queue.value = listOf(current)
        _currentIndex.value = 0
    }

    override fun playAt(index: Int) {
        if (index !in _queue.value.indices) return
        playShowJob?.cancel()
        reportOutgoing()
        _currentIndex.value = index
        loadCurrent(play = true)
    }

    override fun togglePlayPause() {
        if (engine.isPlaying.value) pause() else play()
    }

    override fun play() {
        val list = _queue.value
        if (list.isEmpty()) return
        if (_currentIndex.value !in list.indices) {
            _currentIndex.value = 0
            loadCurrent(play = true)
            return
        }
        val episode = list[_currentIndex.value]
        if (loadedEpisodeId != episode.id) {
            loadCurrent(play = true, startMs = pendingResume.value)
            return
        }
        pendingResume.value?.let { resume ->
            pendingResume.value = null
            if (abs(resume - engine.position.value) > 1_000L) engine.seekTo(resume)
        }
        engine.play()
        report(episode, engine.position.value)
    }

    override fun pause() {
        engine.pause()
        reportCurrent()
    }

    override fun stop() {
        val episode = _queue.value.getOrNull(_currentIndex.value)
        val wasLoaded = episode != null && loadedEpisodeId == episode.id
        val positionMs = engine.position.value
        reportCurrent()
        engine.stop()
        loadedEpisodeId = null
        if (wasLoaded && pendingResume.value == null) pendingResume.value = positionMs
    }

    override fun seekTo(positionMs: Long) {
        val episode = _queue.value.getOrNull(_currentIndex.value) ?: return
        val total = duration.value
        val target = if (total > 0) positionMs.coerceIn(0L, total) else positionMs.coerceAtLeast(0L)
        if (loadedEpisodeId != episode.id) {
            pendingResume.value = target
            return
        }
        pendingResume.value = null
        engine.seekTo(target)
        report(episode, target)
    }

    override fun skipBack() {
        seekTo(position.value - PodcastPlayer.SKIP_BACK_MS)
    }

    override fun skipForward() {
        seekTo(position.value + PodcastPlayer.SKIP_FORWARD_MS)
    }

    override fun skipNext() {
        val next = _currentIndex.value + 1
        if (next in _queue.value.indices) playAt(next)
    }

    override fun skipPrevious() {
        val previous = _currentIndex.value - 1
        if (position.value > RESTART_THRESHOLD_MS || previous !in _queue.value.indices) {
            seekTo(0L)
        } else {
            playAt(previous)
        }
    }

    override fun setSpeed(speed: Float) {
        val snapped = snapSpeed(speed)
        _speed.value = snapped
        engine.setSpeed(snapped)
        Config.setPodcastPlaybackSpeed(snapped)
    }

    override fun setVolume(volume: Float) {
        playerModel.setVolume(volume.coerceIn(0f, 1f))
    }

    override fun markPlayed(episode: PodcastEpisode, played: Boolean) {
        scope.launch {
            try {
                val progress = podcastService.setPlayed(episode.id, played)
                progressStore.record(progress)
                applyProgress(progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(PODCAST_PLAYER_TAG, e.message ?: "Failed to mark episode", e)
                _errors.emit(PodcastPlayerError.Failed(e.message))
            }
        }
    }

    private fun loadCurrent(play: Boolean, startMs: Long? = null) {
        val episode = _queue.value.getOrNull(_currentIndex.value) ?: return
        val start = (startMs ?: resumePosition(episode)).coerceAtLeast(0L)
        pendingResume.value = null
        loadedEpisodeId = episode.id
        engine.load(episode, start, play)
        if (play) report(episode, start)
    }

    private fun unload() {
        engine.stop()
        loadedEpisodeId = null
        pendingResume.value = null
    }

    private fun resumePosition(episode: PodcastEpisode): Long {
        val progress = freshest(progressStore.latest.value[episode.id], episode.progress) ?: return 0L
        if (progress.completed) return 0L
        return progress.positionMs.coerceAtLeast(0L)
    }

    private fun freshest(a: PodcastEpisodeProgress?, b: PodcastEpisodeProgress?): PodcastEpisodeProgress? = when {
        a == null -> b
        b == null -> a
        a.updatedAt >= b.updatedAt -> a
        else -> b
    }

    private fun knownDuration(episode: PodcastEpisode): Long? =
        engine.duration.value.takeIf { it > 0 } ?: episode.knownDurationMs

    private fun report(episode: PodcastEpisode, positionMs: Long, completed: Boolean = false) {
        reporter.report(episode, positionMs, knownDuration(episode), completed)
    }

    private fun reportCurrent() {
        val episode = _queue.value.getOrNull(_currentIndex.value) ?: return
        if (loadedEpisodeId != episode.id) return
        report(episode, engine.position.value)
    }

    private fun reportOutgoing() {
        val episode = _queue.value.getOrNull(_currentIndex.value) ?: return
        if (loadedEpisodeId != episode.id) return
        val positionMs = engine.position.value
        if (positionMs <= 0L) return
        report(episode, positionMs)
    }

    private fun isNearEnd(positionMs: Long, durationMs: Long?): Boolean {
        if (durationMs == null || durationMs <= 0L) return true
        val tolerance = max(COMPLETION_TOLERANCE_MS, durationMs * COMPLETION_TOLERANCE_PERCENT / 100)
        return durationMs - positionMs <= tolerance
    }

    private fun handleFinished() {
        val episode = _queue.value.getOrNull(_currentIndex.value) ?: return
        val positionMs = engine.position.value
        val knownDuration = knownDuration(episode)
        if (!isNearEnd(positionMs, knownDuration)) {
            report(episode, positionMs)
            engine.stop()
            loadedEpisodeId = null
            pendingResume.value = positionMs
            _errors.tryEmit(PodcastPlayerError.Failed(null))
            return
        }

        val total = knownDuration ?: positionMs
        reporter.report(episode, total, total.takeIf { it > 0 }, completed = true)
        loadedEpisodeId = null

        val next = _currentIndex.value + 1
        if (next in _queue.value.indices) {
            _currentIndex.value = next
            loadCurrent(play = true)
        } else {
            pendingResume.value = 0L
        }
    }

    private fun handleProgressUpdate(progress: PodcastEpisodeProgress) {
        applyProgress(progress)
        if (progress.deviceId != null && progress.deviceId == reporter.deviceId) return
        val current = _queue.value.getOrNull(_currentIndex.value) ?: return
        if (current.id != progress.episodeId) return
        if (engine.isPlaying.value || progress.completed) return
        pendingResume.value = progress.positionMs.coerceAtLeast(0L)
    }

    private fun applyProgress(progress: PodcastEpisodeProgress) {
        _queue.update { list ->
            if (list.none { it.id == progress.episodeId }) list
            else list.map { if (it.id == progress.episodeId) it.withProgress(progress) else it }
        }
    }

    private fun restoreState() {
        scope.launch {
            try {
                val state = withContext(ioDispatcher) { readState() }
                if (state == null || state.episodeIds.isEmpty()) return@launch

                rpcServiceManager.awaitAuthentication()
                val episodes = withContext(ioDispatcher) {
                    state.episodeIds.chunked(RESTORE_CHUNK_SIZE).flatMap { podcastService.getEpisodesByIds(it) }
                }
                if (episodes.isEmpty() || _queue.value.isNotEmpty()) return@launch

                val savedCurrentId = state.episodeIds.getOrNull(state.currentIndex)
                val index = episodes.indexOfFirst { it.id == savedCurrentId }
                    .takeIf { it >= 0 }
                    ?: state.currentIndex.coerceIn(0, episodes.size - 1)

                _queue.value = episodes.withProgress(progressStore.latest.value)
                _currentIndex.value = index

                val episode = _queue.value[index]
                val remote = freshest(progressStore.latest.value[episode.id], episode.progress)
                val start = when {
                    episode.id != savedCurrentId -> resumePosition(episode)
                    remote != null && remote.updatedAt > state.savedAt -> resumePosition(episode)
                    else -> state.positionMs
                }
                loadCurrent(play = false, startMs = start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(PODCAST_PLAYER_TAG, e.message ?: "Failed to restore podcast queue", e)
            } finally {
                restoreDone = true
            }
        }
    }

    private fun readState(): PodcastPlayerState? {
        if (!fileSystem.exists(path)) return null
        return try {
            val bytes = fileSystem.read(path) { readByteArray() }
            val decompressed = try {
                decompress(bytes)
            } catch (_: Exception) {
                bytes
            }
            cbor.decodeFromByteArray<PodcastPlayerState>(decompressed)
        } catch (e: Exception) {
            logger.error(PODCAST_PLAYER_TAG, e.message ?: "Failed to read podcast queue", e)
            null
        }
    }

    private fun saveState(state: PodcastPlayerState) {
        scope.launch(stateDispatcher) {
            try {
                val compressed = compress(cbor.encodeToByteArray(state))
                fileSystem.write(path) { write(compressed) }
            } catch (e: Exception) {
                logger.error(PODCAST_PLAYER_TAG, e.message ?: "Failed to save podcast queue", e)
            }
        }
    }
}
