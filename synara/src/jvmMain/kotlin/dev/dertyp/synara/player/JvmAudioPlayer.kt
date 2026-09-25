package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.audio.AudioLoadError
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.MonoMix
import dev.dertyp.synara.player.audio.PcmChunk
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
    private val settings: Settings
) : AudioPlayer {
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
    private var numBuffers = settings.get(SettingKey.AudioBufferCount, 4)
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

    private val fftAnalyzer = FftAnalyzer(1024)
    override val fftData = fftAnalyzer.fftData

    private val _onFinished = MutableSharedFlow<Unit>()
    override val onFinished = _onFinished.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadErrors = MutableSharedFlow<AudioLoadError>(extraBufferCapacity = 8)
    val loadErrors: SharedFlow<AudioLoadError> = _loadErrors.asSharedFlow()

    @Volatile
    var timeStretchEnabled = false

    @Volatile
    private var playbackSpeed = 1f

    private var playerJob: Job? = null
    private var lastSource: AudioSource? = null
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
            } catch (e: Exception) {
                e.printStackTrace()
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
            val bSize = bufferSize ?: 1024
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
            val wasPlaying = _isPlaying.value
            val currentPos = _currentPosition.value
            val currentSource = lastSource

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
                if (currentSource != null) {
                    loadInternal(currentSource, currentPos, wasPlaying)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
            _bitRate.value = 0
            _sampleRate.value = 0
            _bitsPerSample.value = 0
        }
    }

    override fun seekTo(positionMs: Long) {
        val source = lastSource ?: return
        loadInternal(source, positionMs, isDesiredPlaying)
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

    override fun load(songId: PlatformUUID, playImmediately: Boolean) {
        fadeGain = 1f
        loadInternal(dataSource.source(songId), 0L, playImmediately)
    }

    fun loadSource(source: AudioSource, startMs: Long = 0L, playImmediately: Boolean = true) {
        fadeGain = 1f
        loadInternal(source, startMs.coerceAtLeast(0L), playImmediately)
    }

    private fun loadInternal(source: AudioSource, startTimeMs: Long, playImmediately: Boolean) {
        isDesiredPlaying = playImmediately
        stopInternal(false, resetPosition = false, resetIsPlaying = !playImmediately)
        _currentPosition.value = startTimeMs
        lastSource = source
        val generation = ++loadGeneration
        _isLoading.value = true
        source.durationMsHint?.let { _duration.value = it }

        playerJob = scope.launch {
            try {
                val session = try {
                    AudioDecoders.open(source, startTimeMs, this)
                } catch (e: UnsupportedAudioFormatException) {
                    println("Failed to play ${source.cacheKey}: ${e.message}")
                    _loadErrors.tryEmit(AudioLoadError.UnsupportedFormat)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("Failed to create playback session for ${source.cacheKey}: ${e.message}")
                    _loadErrors.tryEmit(AudioLoadError.Failed(e.message))
                    return@launch
                }
                if (session == null) {
                    println("Failed to create playback session for ${source.cacheKey} (unsupported or unreadable audio stream)")
                    _loadErrors.tryEmit(AudioLoadError.Unavailable)
                    return@launch
                }
                val sampleRate = session.sampleRate
                val channels = session.channels
                loudnessCompensation = MonoMix.loudnessCompensation(channels).toFloat()
                val alFormat = nativeFormatFor(channels) ?: run {
                    val reason = if (supportsMultiChannelFormats) "unsupported channel count"
                    else "AL_EXT_MCFORMATS unavailable"
                    println("Failed to play ${source.cacheKey}: no OpenAL format for $channels channels ($reason)")
                    _loadErrors.tryEmit(AudioLoadError.Failed(reason))
                    return@launch
                }

                _duration.value = session.durationMs ?: source.durationMsHint ?: 0L
                _sampleRate.value = session.sampleRate
                _bitsPerSample.value = session.bitsPerSample
                _bitRate.value = source.bitRateHint ?: session.bitRate ?: 0L

                var totalSamplesPlayedBase = (session.startMs * sampleRate) / 1000
                var contentFramesPlayedBase = totalSamplesPlayedBase
                val queuedChunks = ArrayDeque<PcmChunk>()

                val fftQueue = ArrayDeque<Pair<Long, FloatArray>>()
                var totalSamplesQueued = totalSamplesPlayedBase

                var uploadFailed = false

                fun queueBuffer(bufferId: Int, pcm: ShortBuffer) {
                    alGetError()
                    alBufferData(bufferId, alFormat, pcm, sampleRate)
                    if (alGetError() != AL_NO_ERROR) {
                        uploadFailed = true
                        println("OpenAL rejected a $channels channel buffer for ${source.cacheKey}")
                        return
                    }
                    alSourceQueueBuffers(sourceId, bufferId)
                }

                fun enqueueFft(pcm: ShortBuffer): Int {
                    val framesInThisBuffer = pcm.remaining() / channels
                    val fftStepFrames = 256
                    for (offset in 0 until framesInThisBuffer step fftStepFrames) {
                        val mags = processFftAt(pcm, channels, offset)
                        fftQueue.add((totalSamplesQueued + offset) to mags)
                    }
                    return framesInThisBuffer
                }

                val pcmChannel = session.pcmFlow.produceIn(this)
                val stretch = if (timeStretchEnabled) {
                    TimeStretchStage(sampleRate, channels, { playbackSpeed }) {
                        pcmChannel.receiveCatching().getOrNull()
                    }
                } else null
                var exhausted = false

                suspend fun nextChunk(): PcmChunk? {
                    val chunk = if (stretch != null) {
                        stretch.next()
                    } else {
                        pcmChannel.receiveCatching().getOrNull()?.let { buffer ->
                            val frames = buffer.remaining() / channels
                            PcmChunk(buffer, frames, frames.toLong())
                        }
                    }
                    if (chunk == null) exhausted = true
                    return chunk
                }

                fun sourceEnded(): Boolean =
                    if (stretch == null) pcmChannel.isClosedForReceive else exhausted

                fun queueChunk(bufferId: Int, chunk: PcmChunk) {
                    totalSamplesQueued += enqueueFft(chunk.pcm)
                    queueBuffer(bufferId, chunk.pcm)
                    if (!uploadFailed) queuedChunks.add(chunk)
                }

                for (i in 0 until numBuffers) {
                    val chunk = nextChunk() ?: break
                    queueChunk(buffers.get(i), chunk)
                    if (uploadFailed) break
                }
                if (generation == loadGeneration) _isLoading.value = false

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

                while (isActive) {
                    val state = alGetSourcei(sourceId, AL_SOURCE_STATE)
                    val processed = if (_isPlaying.value || state == AL_PLAYING || state == AL_PAUSED) {
                        alGetSourcei(sourceId, AL_BUFFERS_PROCESSED)
                    } else {
                        0
                    }
                    for (i in 0 until processed) {
                        val bufferId = alSourceUnqueueBuffers(sourceId)
                        
                        val size = alGetBufferi(bufferId, AL_SIZE)
                        val bChannels = alGetBufferi(bufferId, AL_CHANNELS)
                        val bBits = alGetBufferi(bufferId, AL_BITS)
                        val bytesPerFrame = (bChannels * bBits / 8).coerceAtLeast(1)
                        val outputFrames = size / bytesPerFrame
                        totalSamplesPlayedBase += outputFrames
                        contentFramesPlayedBase += queuedChunks.pollFirst()?.contentFrames ?: outputFrames.toLong()

                        val chunk = nextChunk()
                        if (chunk != null) {
                            queueChunk(bufferId, chunk)
                            if (uploadFailed) break
                        }
                    }

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

                    val samplesInCurrentBuffer = alGetSourcei(sourceId, AL_SAMPLE_OFFSET)
                    val currentTotalSamples = totalSamplesPlayedBase + samplesInCurrentBuffer
                    val currentChunk = queuedChunks.peekFirst()
                    val contentInCurrentBuffer = if (currentChunk != null && currentChunk.outputFrames > 0 &&
                        currentChunk.contentFrames != currentChunk.outputFrames.toLong()
                    ) {
                        samplesInCurrentBuffer.toLong() * currentChunk.contentFrames / currentChunk.outputFrames
                    } else {
                        samplesInCurrentBuffer.toLong()
                    }
                    _currentPosition.value = (contentFramesPlayedBase + contentInCurrentBuffer) * 1000 / sampleRate
                    
                    // Sync FFT
                    while (fftQueue.size > 1) {
                        val it = fftQueue.iterator()
                        it.next() // current
                        if (it.next().first <= currentTotalSamples) {
                            fftQueue.removeFirst()
                        } else {
                            break
                        }
                    }
                    fftQueue.peekFirst()?.let { (start, mags) ->
                        if (start <= currentTotalSamples) {
                            fftAnalyzer.updateData(mags)
                        }
                    }

                    if (alGetSourcei(sourceId, AL_BUFFERS_QUEUED) == 0 && sourceEnded()) {
                        break
                    }

                    delay(1.milliseconds)
                }

                if (isActive && _isPlaying.value && alGetSourcei(sourceId, AL_BUFFERS_QUEUED) == 0) {
                    _onFinished.emit(Unit)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    println("JvmAudioPlayer error: ${e.message}")
                    e.printStackTrace()
                }
            } finally {
                if (generation == loadGeneration) _isLoading.value = false
                coroutineContext.cancelChildren()
                _isPlaying.value = false
                fftAnalyzer.updateData(FloatArray(fftAnalyzer.fftData.value.size))
            }
        }
    }

    private fun processFftAt(buffer: ShortBuffer, channels: Int, frameOffset: Int): FloatArray {
        val fftSize = 1024
        val pcm = ShortArray(fftSize)
        val startPos = buffer.position() + (frameOffset * channels)
        val framesInFullBuffer = buffer.remaining() / channels
        val framesToRead = (framesInFullBuffer - frameOffset).coerceAtMost(fftSize)

        if (channels == 1) {
            for (i in 0 until framesToRead) {
                pcm[i] = buffer.get(startPos + i)
            }
        } else {
            val weights = MonoMix.coefficients(channels)
            for (i in 0 until framesToRead) {
                val offset = startPos + i * channels
                var sum = 0.0
                for (channel in 0 until channels) {
                    sum += buffer.get(offset + channel) * weights[channel]
                }
                pcm[i] = MonoMix.toPcm16(sum)
            }
        }
        return fftAnalyzer.getMagnitudes(pcm)
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

    companion object {
        private const val MAX_SOURCE_GAIN = 4f
    }
}
