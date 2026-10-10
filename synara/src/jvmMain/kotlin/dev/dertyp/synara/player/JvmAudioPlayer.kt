package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.MonoMix
import dev.dertyp.synara.player.audio.PcmChunk
import dev.dertyp.synara.player.audio.PlaybackTimeline
import dev.dertyp.synara.player.audio.TimeStretchStage
import dev.dertyp.synara.player.audio.UnsupportedAudioFormatException
import dev.dertyp.synara.player.audio.decode.AudioDecoders
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.getOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.lwjgl.BufferUtils
import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.AL_BITS
import org.lwjgl.openal.AL10.AL_BUFFER
import org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED
import org.lwjgl.openal.AL10.AL_BUFFERS_QUEUED
import org.lwjgl.openal.AL10.AL_CHANNELS
import org.lwjgl.openal.AL10.AL_FORMAT_MONO16
import org.lwjgl.openal.AL10.AL_FORMAT_STEREO16
import org.lwjgl.openal.AL10.AL_GAIN
import org.lwjgl.openal.AL10.AL_MAX_GAIN
import org.lwjgl.openal.AL10.AL_NO_ERROR
import org.lwjgl.openal.AL10.AL_PAUSED
import org.lwjgl.openal.AL10.AL_PLAYING
import org.lwjgl.openal.AL10.AL_SIZE
import org.lwjgl.openal.AL10.AL_SOURCE_STATE
import org.lwjgl.openal.AL10.alBufferData
import org.lwjgl.openal.AL10.alDeleteBuffers
import org.lwjgl.openal.AL10.alDeleteSources
import org.lwjgl.openal.AL10.alGenBuffers
import org.lwjgl.openal.AL10.alGenSources
import org.lwjgl.openal.AL10.alGetBufferi
import org.lwjgl.openal.AL10.alGetError
import org.lwjgl.openal.AL10.alGetSourcei
import org.lwjgl.openal.AL10.alIsExtensionPresent
import org.lwjgl.openal.AL10.alSourcePause
import org.lwjgl.openal.AL10.alSourcePlay
import org.lwjgl.openal.AL10.alSourceQueueBuffers
import org.lwjgl.openal.AL10.alSourceStop
import org.lwjgl.openal.AL10.alSourceUnqueueBuffers
import org.lwjgl.openal.AL10.alSourcef
import org.lwjgl.openal.AL10.alSourcei
import org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.ALC_DEFAULT_DEVICE_SPECIFIER
import org.lwjgl.openal.ALC10.alcCloseDevice
import org.lwjgl.openal.ALC10.alcCreateContext
import org.lwjgl.openal.ALC10.alcDestroyContext
import org.lwjgl.openal.ALC10.alcGetString
import org.lwjgl.openal.ALC10.alcMakeContextCurrent
import org.lwjgl.openal.EXTThreadLocalContext.alcSetThreadContext
import org.lwjgl.openal.ALC10.alcOpenDevice
import org.lwjgl.openal.ALC11
import org.lwjgl.openal.ALUtil
import org.lwjgl.openal.EXTMCFormats.AL_FORMAT_51CHN16
import org.lwjgl.openal.EXTMCFormats.AL_FORMAT_61CHN16
import org.lwjgl.openal.EXTMCFormats.AL_FORMAT_71CHN16
import org.lwjgl.openal.EXTMCFormats.AL_FORMAT_QUAD16
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer
import java.util.ArrayDeque
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class JvmAudioPlayer(
    songService: ISongService,
    songCache: SongCache,
    private val settings: Settings,
    private val logger: Logger
) : AudioPlayer {
    private val decoders = AudioDecoders(logger)
    private val audioDispatcher = newSingleThreadContext("OpenAL-Audio")
    private val scope = CoroutineScope(audioDispatcher + SupervisorJob())

    private var device: Long = 0
    private var context: Long = 0
    private var useThreadLocalContext = false
    private var supportsMultiChannelFormats = false
    private var loudnessCompensation = 1.0f
    @Volatile
    private var fadeGain = 1.0f
    private var sourceId: Int = 0
    private var numBuffers = MIN_BUFFER_COUNT
    private var buffers: IntBuffer = BufferUtils.createIntBuffer(numBuffers)

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration = _duration.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    override val volume = _volume.asStateFlow()

    private val _sampleRate = MutableStateFlow(0)
    override val sampleRate = _sampleRate.asStateFlow()

    private val _bitsPerSample = MutableStateFlow(0)
    override val bitsPerSample = _bitsPerSample.asStateFlow()

    private val _bitRate = MutableStateFlow(0L)
    override val bitRate = _bitRate.asStateFlow()

    private val _availableOutputDevices = MutableStateFlow<List<String>>(emptyList())
    override val availableOutputDevices = _availableOutputDevices.asStateFlow()

    private val _currentOutputDevice = MutableStateFlow<String?>(null)
    override val currentOutputDevice = _currentOutputDevice.asStateFlow()

    private val fftAnalyzer = FftAnalyzer(FFT_SIZE)
    override val fftData = fftAnalyzer.fftData

    private val _stereoFftData = MutableStateFlow(StereoSpectrum.EMPTY)
    override val stereoFftData: StateFlow<StereoSpectrum> = _stereoFftData.asStateFlow()

    private val fftMixScratch = ShortArray(FFT_SIZE)
    private val fftLeftScratch = ShortArray(FFT_SIZE)
    private val fftRightScratch = ShortArray(FFT_SIZE)

    private val _onFinished = MutableSharedFlow<PlatformUUID?>()
    override val onFinished = _onFinished.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadErrors = MutableSharedFlow<AudioLoadFailure>(extraBufferCapacity = 8)
    override val loadErrors: SharedFlow<AudioLoadFailure> = _loadErrors.asSharedFlow()

    private val _trackTransitions = MutableSharedFlow<PlatformUUID>(extraBufferCapacity = 8)
    override val trackTransitions: SharedFlow<PlatformUUID> = _trackTransitions.asSharedFlow()

    @Volatile
    var timeStretchEnabled = false

    @Volatile
    private var playbackSpeed = 1f

    private var playerJob: Job? = null
    @Volatile
    private var lastTrack: LoadedTrack? = null
    private var prepared: Prepared? = null
    @Volatile
    private var loadGeneration = 0L
    private var isDesiredPlaying: Boolean = false

    private val dataSource = SongDataSource(songService, songCache, settings)

    init {
        _currentOutputDevice.value = settings.getOrNull(SettingKey.AudioOutputDevice)
        scope.launch {
            try {
                initOpenAL()
                updateAvailableDevices()
            } catch (e: Throwable) {
                logger.error(LOG_TAG, "Failed to initialise OpenAL: ${e.message}", e)
            }
        }
    }

    private fun updateAvailableDevices() {
        val devices = ALUtil.getStringList(0L, ALC11.ALC_ALL_DEVICES_SPECIFIER)
        _availableOutputDevices.value = devices ?: emptyList()

        if (_currentOutputDevice.value == null) {
            _currentOutputDevice.value = alcGetString(0L, ALC_DEFAULT_DEVICE_SPECIFIER)
        }
    }

    private fun initOpenAL() {
        val deviceName = _currentOutputDevice.value
        device = alcOpenDevice(deviceName)
        if (device == 0L) {
            device = alcOpenDevice(null as ByteBuffer?)
        }
        if (device == 0L) throw RuntimeException("Failed to open OpenAL device")

        val deviceCaps = ALC.createCapabilities(device)

        val bufferSize = settings.getOrNull(SettingKey.AudioBufferSize)
        val bufferCount = settings.getOrNull(SettingKey.AudioBufferCount)
        val targetSampleRate = settings.getOrNull(SettingKey.AudioTargetSampleRate)

        val attributes = if (bufferSize != null || bufferCount != null || targetSampleRate != null) {
            val list = mutableListOf<Int>()

            val freq = targetSampleRate ?: 48000
            val bSize = (bufferSize ?: 1024).coerceAtLeast(1)
            val bCount = bufferCount ?: 4

            list.add(0x1007) // ALC_FREQUENCY
            list.add(freq)

            list.add(0x1008) // ALC_REFRESH
            list.add(freq / bSize)

            list.add(0x1009) // ALC_SYNC
            list.add(1) // ALC_TRUE

            list.add(0x1016) // ALC_BUFFER_SIZE
            list.add(bSize * bCount)

            list.add(0x1014) // ALC_PERIOD_SIZE
            list.add(bSize)
            list.add(0x1015) // ALC_PERIODS
            list.add(bCount)

            list.add(0x1992) // ALC_PERIOD_SIZE_SOFT
            list.add(bSize)
            list.add(0x1993) // ALC_PERIODS_SOFT
            list.add(bCount)

            list.add(0) // Null terminator
            val attrBuffer = BufferUtils.createIntBuffer(list.size)
            list.forEach { attrBuffer.put(it) }
            attrBuffer.flip()
            attrBuffer
        } else null

        context = alcCreateContext(device, attributes)
        if (context == 0L) throw RuntimeException("Failed to create OpenAL context")

        useThreadLocalContext = deviceCaps.ALC_EXT_thread_local_context
        if (!makeContextCurrent(context)) {
            throw RuntimeException("Failed to make OpenAL context current")
        }
        val alCaps = AL.createCapabilities(deviceCaps)
        supportsMultiChannelFormats =
            alCaps.AL_EXT_MCFORMATS || alIsExtensionPresent("AL_EXT_MCFORMATS")

        sourceId = alGenSources()
        alSourcef(sourceId, AL_MAX_GAIN, MAX_SOURCE_GAIN)
        alGetError()
        numBuffers = settings.get(SettingKey.AudioBufferCount, DEFAULT_BUFFER_COUNT).coerceAtLeast(MIN_BUFFER_COUNT)
        buffers = BufferUtils.createIntBuffer(numBuffers)
        alGenBuffers(buffers)
    }

    private fun nativeFormatFor(channels: Int): Int? = when (channels) {
        1 -> AL_FORMAT_MONO16
        2 -> AL_FORMAT_STEREO16
        4 -> if (supportsMultiChannelFormats) AL_FORMAT_QUAD16 else null
        6 -> if (supportsMultiChannelFormats) AL_FORMAT_51CHN16 else null
        7 -> if (supportsMultiChannelFormats) AL_FORMAT_61CHN16 else null
        8 -> if (supportsMultiChannelFormats) AL_FORMAT_71CHN16 else null
        else -> null
    }

    private fun makeContextCurrent(ctx: Long): Boolean =
        if (useThreadLocalContext) alcSetThreadContext(ctx) else alcMakeContextCurrent(ctx)

    override fun setOutputDevice(deviceSpecifier: String?) {
        if (_currentOutputDevice.value == deviceSpecifier) return
        _currentOutputDevice.value = deviceSpecifier

        scope.launch {
            val wasPlaying = isDesiredPlaying
            val currentPos = _currentPosition.value
            val currentTrack = lastTrack

            stopInternal(false, resetPosition = false, resetIsPlaying = false)

            if (sourceId != 0) alDeleteSources(sourceId)
            if (buffers.get(0) != 0) alDeleteBuffers(buffers)
            if (context != 0L) {
                makeContextCurrent(0)
                alcDestroyContext(context)
            }
            if (device != 0L) alcCloseDevice(device)

            try {
                initOpenAL()
                if (currentTrack != null) {
                    loadInternal(currentTrack.source, currentPos, wasPlaying, currentTrack.songId, claimPrepared = false)
                }
            } catch (e: Exception) {
                logger.error(LOG_TAG, "Failed to switch the output device to $deviceSpecifier: ${e.message}", e)
            }
        }
    }

    override fun play() {
        isDesiredPlaying = true
        scope.launch {
            if (sourceId != 0 && !_isPlaying.value) {
                alSourcePlay(sourceId)
                _isPlaying.value = true
            }
        }
    }

    override fun pause() {
        isDesiredPlaying = false
        scope.launch {
            if (sourceId != 0 && _isPlaying.value) {
                alSourcePause(sourceId)
                _isPlaying.value = false
            }
        }
    }

    override fun stop() {
        stopInternal(true)
        setFadeGain(1f)
    }

    private fun stopInternal(resetDesiredPlaying: Boolean, resetPosition: Boolean = true, resetIsPlaying: Boolean = true) {
        if (resetDesiredPlaying) {
            isDesiredPlaying = false
            _isLoading.value = false
        }
        playerJob?.cancel()
        if (resetIsPlaying) {
            _isPlaying.value = false
        }
        if (resetPosition) {
            _currentPosition.value = 0
        }
        scope.launch {
            if (sourceId != 0) {
                alSourceStop(sourceId)
                val queued = alGetSourcei(sourceId, AL_BUFFERS_QUEUED)
                repeat(queued) {
                    alSourceUnqueueBuffers(sourceId)
                }
                alSourcei(sourceId, AL_BUFFER, 0)
            }
            if (resetDesiredPlaying) clearPrepared()
            _bitRate.value = 0
            _sampleRate.value = 0
            _bitsPerSample.value = 0
        }
    }

    override fun seekTo(positionMs: Long) {
        val track = lastTrack ?: return
        loadInternal(track.source, positionMs, isDesiredPlaying, track.songId, claimPrepared = false)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(TimeStretchStage.MIN_SPEED, TimeStretchStage.MAX_SPEED)
    }

    override fun setVolume(volume: Float) {
        _volume.value = volume
        scope.launch {
            if (sourceId != 0) {
                alSourcef(sourceId, AL_GAIN, volume * loudnessCompensation * fadeGain)
            }
        }
    }

    override fun setFadeGain(gain: Float) {
        fadeGain = gain.coerceIn(0f, 1f)
        scope.launch {
            if (sourceId != 0) {
                alSourcef(sourceId, AL_GAIN, _volume.value * loudnessCompensation * fadeGain)
            }
        }
    }

    override fun load(songId: PlatformUUID, playImmediately: Boolean, startMs: Long) {
        fadeGain = 1f
        loadInternal(dataSource.source(songId), startMs.coerceAtLeast(0L), playImmediately, songId, claimPrepared = true)
    }

    fun loadSource(source: AudioSource, startMs: Long = 0L, playImmediately: Boolean = true) {
        fadeGain = 1f
        loadInternal(source, startMs.coerceAtLeast(0L), playImmediately, null, claimPrepared = true)
    }

    override fun prepareNext(songId: PlatformUUID) {
        scope.launch {
            if (prepared?.songId == songId) return@launch
            clearPrepared()
            val source = dataSource.source(songId)
            val slotScope = CoroutineScope(audioDispatcher + SupervisorJob(scope.coroutineContext[Job]))
            val warmJob = slotScope.launch {
                try {
                    if (source.prepare()) source.size()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warning(LOG_TAG, "Failed to warm up ${source.cacheKey}: ${e.message}")
                }
            }
            prepared = Prepared.Requested(songId, source, slotScope, warmJob)
        }
    }

    override fun clearNext() {
        scope.launch { clearPrepared() }
    }

    private fun clearPrepared() {
        prepared?.scope?.cancel()
        prepared = null
    }

    private fun openPrepared(requested: Prepared.Requested) {
        val opening = Prepared.Opening(requested.songId, requested.source, requested.scope)
        prepared = opening
        opening.scope.launch {
            requested.warmJob.join()
            val session = try {
                decoders.open(opening.source, 0L, opening.scope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning(LOG_TAG, "Failed to prepare ${opening.source.cacheKey}: ${e.message}")
                null
            }
            if (prepared !== opening) return@launch
            if (session == null) {
                logger.warning(LOG_TAG, "Nothing to join for ${opening.source.cacheKey}, the track is loaded when the current one ends")
                opening.scope.cancel()
                prepared = Prepared.Failed(opening.songId, opening.scope)
            } else {
                prepared = Prepared.Ready(opening.songId, opening.source, session, opening.scope)
            }
        }
    }

    private fun publishSegment(segment: Segment) {
        loudnessCompensation = segment.loudnessCompensation
        _duration.value = segment.durationMs
        _sampleRate.value = segment.sampleRate
        _bitsPerSample.value = segment.session.bitsPerSample
        _bitRate.value = segment.bitRate
    }

    private fun loadInternal(
        source: AudioSource,
        startTimeMs: Long,
        playImmediately: Boolean,
        songId: PlatformUUID?,
        claimPrepared: Boolean
    ) {
        isDesiredPlaying = playImmediately
        val generation = ++loadGeneration
        stopInternal(false, resetPosition = false, resetIsPlaying = !playImmediately)
        _currentPosition.value = startTimeMs
        lastTrack = LoadedTrack(source, songId)
        _isLoading.value = true
        source.durationMsHint?.let { _duration.value = it }

        playerJob = scope.launch {
            var timeline: PlaybackTimeline<Segment>? = null
            val promoted = if (claimPrepared) {
                (prepared as? Prepared.Ready)?.takeIf {
                    songId != null && it.songId == songId && startTimeMs == 0L && !timeStretchEnabled
                }
            } else null
            if (promoted != null) {
                prepared = null
                logger.info(LOG_TAG, "Starting ${promoted.source.cacheKey} from its prepared session")
            } else if (claimPrepared && prepared?.songId == songId) {
                clearPrepared()
            }
            try {
                val session = if (promoted != null) promoted.session else try {
                    decoders.open(source, startTimeMs, this)
                } catch (e: UnsupportedAudioFormatException) {
                    logger.warning(LOG_TAG, "Failed to play ${source.cacheKey}: ${e.message}")
                    if (generation == loadGeneration) _loadErrors.tryEmit(AudioLoadFailure(songId, AudioLoadError.UnsupportedFormat))
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(LOG_TAG, "Failed to create playback session for ${source.cacheKey}: ${e.message}", e)
                    if (generation == loadGeneration) _loadErrors.tryEmit(AudioLoadFailure(songId, AudioLoadError.Failed(e.message)))
                    return@launch
                }
                if (session == null) {
                    logger.warning(LOG_TAG, "Failed to create playback session for ${source.cacheKey} (unsupported or unreadable audio stream)")
                    if (generation == loadGeneration) _loadErrors.tryEmit(AudioLoadFailure(songId, AudioLoadError.Unavailable))
                    return@launch
                }
                val alFormat = nativeFormatFor(session.channels) ?: run {
                    val reason = if (supportsMultiChannelFormats) "unsupported channel count"
                    else "AL_EXT_MCFORMATS unavailable"
                    logger.warning(LOG_TAG, "Failed to play ${source.cacheKey}: no OpenAL format for ${session.channels} channels ($reason)")
                    if (generation == loadGeneration) _loadErrors.tryEmit(AudioLoadFailure(songId, AudioLoadError.Failed(reason)))
                    return@launch
                }

                val segments = PlaybackTimeline(
                    Segment(songId, promoted?.source ?: source, session, alFormat, promoted?.scope),
                    session.sampleRate,
                    (session.startMs * session.sampleRate) / 1000
                )
                timeline = segments
                publishSegment(segments.current)

                val queuedChunks = ArrayDeque<PcmChunk>()
                val fftQueue = ArrayDeque<FftFrame>()

                var uploadFailed = false

                fun queueBuffer(bufferId: Int, segment: Segment, pcm: ShortBuffer) {
                    alGetError()
                    alBufferData(bufferId, segment.alFormat, pcm, segment.sampleRate)
                    if (alGetError() != AL_NO_ERROR) {
                        uploadFailed = true
                        logger.error(LOG_TAG, "OpenAL rejected a ${segment.channels} channel buffer for ${segment.source.cacheKey}")
                        return
                    }
                    alSourceQueueBuffers(sourceId, bufferId)
                }

                fun enqueueFft(segment: Segment, pcm: ShortBuffer): Int {
                    val framesInThisBuffer = pcm.remaining() / segment.channels
                    val fftStepFrames = 256
                    for (offset in 0 until framesInThisBuffer step fftStepFrames) {
                        fftQueue.add(
                            processFftAt(
                                pcm, segment.channels, offset, segments.framesQueued + offset,
                                segment.mixWeights, segment.leftWeights, segment.rightWeights
                            )
                        )
                    }
                    return framesInThisBuffer
                }

                val producerScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
                var pcmChannel = session.pcmFlow.produceIn(producerScope)
                var streamFailure: Throwable? = null
                var draining = false
                var exhausted = false

                suspend fun receiveBuffer(): ShortBuffer? {
                    val result = pcmChannel.receiveCatching()
                    result.exceptionOrNull()?.let { streamFailure = it }
                    return result.getOrNull()
                }

                val stretch = if (timeStretchEnabled) {
                    TimeStretchStage(session.sampleRate, session.channels, { playbackSpeed }) { receiveBuffer() }
                } else null

                suspend fun nextChunk(): PcmChunk? {
                    if (stretch != null) {
                        val chunk = stretch.next()
                        if (chunk == null) exhausted = true
                        return chunk
                    }
                    while (true) {
                        val buffer = receiveBuffer()
                        if (buffer != null) {
                            val frames = buffer.remaining() / segments.queuing.channels
                            return PcmChunk(buffer, frames, frames.toLong())
                        }
                        if (exhausted || draining) return null
                        val ready = prepared as? Prepared.Ready
                        if (streamFailure != null || ready == null || segments.pending != null) {
                            if (streamFailure == null && ready == null) clearPrepared()
                            exhausted = true
                            return null
                        }
                        val current = segments.current
                        if (ready.session.sampleRate != current.sampleRate || ready.session.channels != current.channels) {
                            draining = true
                            return null
                        }
                        prepared = null
                        pcmChannel = ready.session.pcmFlow.produceIn(producerScope)
                        segments.attach(Segment(ready.songId, ready.source, ready.session, current.alFormat, ready.scope), ready.session.sampleRate)
                        logger.info(LOG_TAG, "Queueing ${ready.source.cacheKey} behind ${current.source.cacheKey} on the same source")
                    }
                }

                fun sourceEnded(): Boolean = when {
                    stretch != null -> exhausted
                    draining || segments.pending != null -> false
                    else -> pcmChannel.isClosedForReceive
                }

                fun queueChunk(bufferId: Int, chunk: PcmChunk) {
                    val segment = segments.queuing
                    segments.onChunkQueued(enqueueFft(segment, chunk.pcm))
                    queueBuffer(bufferId, segment, chunk.pcm)
                    if (!uploadFailed) queuedChunks.add(chunk)
                }

                suspend fun fillAndStart() {
                    for (i in 0 until numBuffers) {
                        val chunk = nextChunk() ?: break
                        queueChunk(buffers.get(i), chunk)
                        if (uploadFailed) break
                    }
                    alSourcef(sourceId, AL_GAIN, _volume.value * loudnessCompensation * fadeGain)
                    if (isDesiredPlaying) {
                        alSourcePlay(sourceId)
                        _isPlaying.value = true
                    } else {
                        // The source is AL_STOPPED after stopInternal and pausing a stopped source is a no-op,
                        // yet a stopped source reports every queued buffer as processed, which would let the loop
                        // below drain the track silently. Start it muted and pause to reach a real AL_PAUSED state.
                        alSourcef(sourceId, AL_GAIN, 0f)
                        alSourcePlay(sourceId)
                        alSourcePause(sourceId)
                        alSourcef(sourceId, AL_GAIN, _volume.value * loudnessCompensation * fadeGain)
                        _isPlaying.value = false
                    }
                }

                fun unqueueBuffer(): Int {
                    val bufferId = alSourceUnqueueBuffers(sourceId)
                    val size = alGetBufferi(bufferId, AL_SIZE)
                    val bChannels = alGetBufferi(bufferId, AL_CHANNELS)
                    val bBits = alGetBufferi(bufferId, AL_BITS)
                    val bytesPerFrame = (bChannels * bBits / 8).coerceAtLeast(1)
                    val outputFrames = size / bytesPerFrame
                    segments.onBufferUnqueued(outputFrames, queuedChunks.pollFirst()?.contentFrames ?: outputFrames.toLong())
                    return bufferId
                }

                fillAndStart()
                if (generation == loadGeneration) _isLoading.value = false

                val freed = ArrayDeque<Int>()
                var lastPositionPublish = System.nanoTime() - POSITION_PUBLISH_INTERVAL_NANOS
                while (isActive) {
                    var state = alGetSourcei(sourceId, AL_SOURCE_STATE)
                    val processed = if (_isPlaying.value || state == AL_PLAYING || state == AL_PAUSED || draining) {
                        alGetSourcei(sourceId, AL_BUFFERS_PROCESSED)
                    } else {
                        0
                    }
                    repeat(processed) { freed.add(unqueueBuffer()) }
                    while (freed.isNotEmpty()) {
                        val chunk = nextChunk() ?: break
                        if (state == AL_PLAYING && _isPlaying.value) {
                            val lateState = alGetSourcei(sourceId, AL_SOURCE_STATE)
                            if (lateState != AL_PLAYING && lateState != AL_PAUSED) {
                                state = lateState
                                repeat(alGetSourcei(sourceId, AL_BUFFERS_QUEUED)) { freed.add(unqueueBuffer()) }
                            }
                        }
                        queueChunk(freed.removeFirst(), chunk)
                        if (uploadFailed) break
                    }
                    freed.clear()

                    if (uploadFailed) break

                    if (_isPlaying.value) {
                        if (state != AL_PLAYING && state != AL_PAUSED) {
                            if (alGetSourcei(sourceId, AL_BUFFERS_QUEUED) > 0) {
                                alSourcePlay(sourceId)
                            } else if (sourceEnded()) {
                                break
                            }
                        }
                    }

                    var transitioned = false
                    if (draining && _isPlaying.value && alGetSourcei(sourceId, AL_BUFFERS_QUEUED) == 0) {
                        draining = false
                        val ready = prepared as? Prepared.Ready
                        val nextFormat = ready?.let { nativeFormatFor(it.session.channels) }
                        if (ready == null || nextFormat == null) {
                            if (ready != null) logger.warning(LOG_TAG, "Cannot join ${ready.source.cacheKey}: no OpenAL format for ${ready.session.channels} channels")
                            clearPrepared()
                            exhausted = true
                        } else {
                            prepared = null
                            val previous = segments.current
                            val next = Segment(ready.songId, ready.source, ready.session, nextFormat, ready.scope)
                            logger.info(LOG_TAG, "Restarting the source for ${next.source.cacheKey} after ${previous.source.cacheKey}: ${previous.sampleRate} Hz ${previous.channels} ch to ${next.sampleRate} Hz ${next.channels} ch")
                            alSourceStop(sourceId)
                            alSourcei(sourceId, AL_BUFFER, 0)
                            queuedChunks.clear()
                            fftQueue.clear()
                            pcmChannel = next.session.pcmFlow.produceIn(producerScope)
                            segments.resetForSoftJoin(next, next.sampleRate)
                            previous.scope?.cancel()
                            fadeGain = 1f
                            loudnessCompensation = next.loudnessCompensation
                            fillAndStart()
                            transitioned = true
                        }
                    }

                    val samplesInCurrentBuffer = alGetSourcei(sourceId, AL_SAMPLE_OFFSET)
                    val currentTotalSamples = segments.playheadFrame(samplesInCurrentBuffer)
                    val currentChunk = queuedChunks.peekFirst()
                    val contentInCurrentBuffer = if (currentChunk != null && currentChunk.outputFrames > 0 &&
                        currentChunk.contentFrames != currentChunk.outputFrames.toLong()
                    ) {
                        samplesInCurrentBuffer.toLong() * currentChunk.contentFrames / currentChunk.outputFrames
                    } else {
                        samplesInCurrentBuffer.toLong()
                    }
                    if (segments.transitionReached(samplesInCurrentBuffer)) {
                        val previous = segments.current
                        segments.switchToPending()
                        previous.scope?.cancel()
                        transitioned = true
                    }
                    val current = segments.current
                    val live = generation == loadGeneration
                    if (transitioned && live) {
                        fadeGain = 1f
                        publishSegment(current)
                        alSourcef(sourceId, AL_GAIN, _volume.value * loudnessCompensation * fadeGain)
                    }
                    val now = System.nanoTime()
                    if (live && (transitioned || processed > 0 || now - lastPositionPublish >= POSITION_PUBLISH_INTERVAL_NANOS)) {
                        _currentPosition.value = segments.positionMs(contentInCurrentBuffer)
                        lastPositionPublish = now
                    }

                    // Sync FFT
                    while (fftQueue.size > 1) {
                        val it = fftQueue.iterator()
                        it.next() // current
                        if (it.next().startFrame <= currentTotalSamples) {
                            fftQueue.removeFirst()
                        } else {
                            break
                        }
                    }
                    fftQueue.peekFirst()?.let { frame ->
                        if (frame.startFrame <= currentTotalSamples) {
                            fftAnalyzer.updateData(frame.mix)
                            _stereoFftData.value = frame.stereo
                        }
                    }

                    if (transitioned && live) {
                        lastTrack = LoadedTrack(current.source, current.songId)
                        current.songId?.let { _trackTransitions.tryEmit(it) }
                        logger.info(LOG_TAG, "Now playing ${current.source.cacheKey} after a gapless join")
                    }

                    (prepared as? Prepared.Requested)?.let { requested ->
                        if (stretch == null && (_duration.value <= 0 || _duration.value - _currentPosition.value <= PRELOAD_LEAD_MS)) {
                            openPrepared(requested)
                        }
                    }

                    if (alGetSourcei(sourceId, AL_BUFFERS_QUEUED) == 0 && sourceEnded()) {
                        break
                    }

                    delay(POLL_INTERVAL)
                }

                if (isActive && _isPlaying.value && alGetSourcei(sourceId, AL_BUFFERS_QUEUED) == 0) {
                    val current = segments.current
                    val failure = streamFailure
                    if (failure == null) {
                        _onFinished.emit(current.songId)
                    } else if (generation == loadGeneration) {
                        _isPlaying.value = false
                        _loadErrors.tryEmit(AudioLoadFailure(current.songId, AudioLoadError.Failed(failure.message)))
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error(LOG_TAG, "Playback of ${source.cacheKey} failed: ${e.message}", e)
                }
            } finally {
                coroutineContext.cancelChildren()
                timeline?.pending?.scope?.cancel()
                (timeline?.current?.scope ?: promoted?.scope)?.cancel()
                if (generation == loadGeneration) {
                    _isPlaying.value = false
                    _isLoading.value = false
                    fftAnalyzer.updateData(FloatArray(fftAnalyzer.fftData.value.size))
                    _stereoFftData.value = StereoSpectrum.EMPTY
                }
            }
        }
    }

    private fun processFftAt(
        buffer: ShortBuffer,
        channels: Int,
        frameOffset: Int,
        startFrame: Long,
        mixWeights: DoubleArray,
        leftWeights: FloatArray,
        rightWeights: FloatArray
    ): FftFrame {
        val startPos = buffer.position() + (frameOffset * channels)
        val framesInFullBuffer = buffer.remaining() / channels
        val framesToRead = (framesInFullBuffer - frameOffset).coerceIn(0, FFT_SIZE)

        if (channels == 1) {
            for (i in 0 until framesToRead) {
                fftMixScratch[i] = buffer.get(startPos + i)
            }
            fftMixScratch.fill(0, framesToRead, FFT_SIZE)
            val mix = fftAnalyzer.getMagnitudes(fftMixScratch)
            return FftFrame(startFrame, mix, StereoSpectrum(mix, mix))
        }

        for (i in 0 until framesToRead) {
            val offset = startPos + i * channels
            var sum = 0.0
            var leftSum = 0.0
            var rightSum = 0.0
            for (channel in 0 until channels) {
                val sample = buffer.get(offset + channel)
                sum += sample * mixWeights[channel]
                leftSum += sample * leftWeights[channel]
                rightSum += sample * rightWeights[channel]
            }
            fftMixScratch[i] = MonoMix.toPcm16(sum)
            fftLeftScratch[i] = MonoMix.toPcm16(leftSum)
            fftRightScratch[i] = MonoMix.toPcm16(rightSum)
        }
        fftMixScratch.fill(0, framesToRead, FFT_SIZE)
        fftLeftScratch.fill(0, framesToRead, FFT_SIZE)
        fftRightScratch.fill(0, framesToRead, FFT_SIZE)
        val mix = fftAnalyzer.getMagnitudes(fftMixScratch)
        val left = fftAnalyzer.getMagnitudes(fftLeftScratch)
        val right = fftAnalyzer.getMagnitudes(fftRightScratch)
        return FftFrame(startFrame, mix, StereoSpectrum(left, right))
    }

    override fun release() {
        runBlocking {
            stop()
            val job = scope.launch {
                if (sourceId != 0) alDeleteSources(sourceId)
                if (buffers.get(0) != 0) alDeleteBuffers(buffers)

                if (context != 0L) {
                    makeContextCurrent(0)
                    alcDestroyContext(context)
                }
                if (device != 0L) alcCloseDevice(device)
            }
            job.join()
            audioDispatcher.close()
            scope.cancel()
        }
    }

    private class FftFrame(val startFrame: Long, val mix: FloatArray, val stereo: StereoSpectrum)

    private class LoadedTrack(val source: AudioSource, val songId: PlatformUUID?)

    private sealed class Prepared(val songId: PlatformUUID, val scope: CoroutineScope) {
        class Requested(songId: PlatformUUID, val source: AudioSource, scope: CoroutineScope, val warmJob: Job) : Prepared(songId, scope)
        class Opening(songId: PlatformUUID, val source: AudioSource, scope: CoroutineScope) : Prepared(songId, scope)
        class Ready(songId: PlatformUUID, val source: AudioSource, val session: DecodedStream, scope: CoroutineScope) : Prepared(songId, scope)
        class Failed(songId: PlatformUUID, scope: CoroutineScope) : Prepared(songId, scope)
    }

    private class Segment(
        val songId: PlatformUUID?,
        val source: AudioSource,
        val session: DecodedStream,
        val alFormat: Int,
        val scope: CoroutineScope?
    ) {
        val sampleRate = session.sampleRate
        val channels = session.channels
        val loudnessCompensation = MonoMix.loudnessCompensation(channels).toFloat()
        val mixWeights = MonoMix.coefficients(channels)
        val leftWeights: FloatArray
        val rightWeights: FloatArray
        val durationMs: Long get() = session.durationMs ?: source.durationMsHint ?: 0L
        val bitRate: Long get() = source.bitRateHint ?: session.bitRate ?: 0L

        init {
            val (left, right) = MonoMix.sideCoefficients(channels)
            leftWeights = left
            rightWeights = right
        }
    }

    companion object {
        private const val MAX_SOURCE_GAIN = 4f
        private const val FFT_SIZE = 1024
        private const val DEFAULT_BUFFER_COUNT = 4
        private const val MIN_BUFFER_COUNT = 2
        private const val PRELOAD_LEAD_MS = 15_000L
        private const val POSITION_PUBLISH_INTERVAL_NANOS = 16_000_000L
        private val POLL_INTERVAL = 4.milliseconds
        private val LOG_TAG = LogTag("audio")
    }
}
