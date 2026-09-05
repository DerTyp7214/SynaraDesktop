package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.data.effectiveAudio
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.audio.MonoMix
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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

    private var playerJob: Job? = null
    private var lastSongId: PlatformUUID? = null
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
            val currentSongId = lastSongId

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
                if (currentSongId != null) {
                    loadInternal(currentSongId, currentPos, wasPlaying)
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
        val songId = lastSongId ?: return
        loadInternal(songId, positionMs, isDesiredPlaying)
    }

    override fun setVolume(volume: Float) {
        _volume.value = volume
        scope.launch {
            if (sourceId != 0) {
                alSourcef(sourceId, AL_GAIN, volume * loudnessCompensation)
            }
        }
    }

    override fun load(songId: PlatformUUID, playImmediately: Boolean) {
        loadInternal(songId, 0L, playImmediately)
    }

    private fun loadInternal(songId: PlatformUUID, startTimeMs: Long, playImmediately: Boolean) {
        isDesiredPlaying = playImmediately
        stopInternal(false, resetPosition = false, resetIsPlaying = !playImmediately)
        _currentPosition.value = startTimeMs
        lastSongId = songId

        playerJob = scope.launch {
            try {
                val session = dataSource.createPlaybackSession(songId, startTimeMs, scope)
                if (session == null) {
                    println("Failed to create playback session for $songId (unsupported or unreadable audio stream)")
                    return@launch
                }
                val sampleRate = session.sampleRate
                val channels = session.channels
                loudnessCompensation = MonoMix.loudnessCompensation(channels).toFloat()
                val alFormat = nativeFormatFor(channels) ?: run {
                    val reason = if (supportsMultiChannelFormats) "unsupported channel count"
                    else "AL_EXT_MCFORMATS unavailable"
                    println("Failed to play $songId: no OpenAL format for $channels channels ($reason)")
                    return@launch
                }

                _duration.value = session.song.duration
                _sampleRate.value = session.sampleRate
                _bitsPerSample.value = session.bitsPerSample
                _bitRate.value = session.song.effectiveAudio?.bitRate ?: 0L
                if (session.startMs != startTimeMs) {
                    _currentPosition.value = session.startMs
                }

                var totalSamplesPlayedBase = (session.startMs * sampleRate) / 1000

                val fftQueue = ArrayDeque<Pair<Long, FloatArray>>()
                var totalSamplesQueued = totalSamplesPlayedBase

                var uploadFailed = false

                fun queueBuffer(bufferId: Int, pcm: ShortBuffer) {
                    alGetError()
                    alBufferData(bufferId, alFormat, pcm, sampleRate)
                    if (alGetError() != AL_NO_ERROR) {
                        uploadFailed = true
                        println("OpenAL rejected a $channels channel buffer for $songId")
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

                for (i in 0 until numBuffers) {
                    val buffer = pcmChannel.receiveCatching().getOrNull() ?: break
                    totalSamplesQueued += enqueueFft(buffer)
                    queueBuffer(buffers.get(i), buffer)
                    if (uploadFailed) break
                }

                alSourcef(sourceId, AL_GAIN, _volume.value * loudnessCompensation)
                if (isDesiredPlaying) {
                    alSourcePlay(sourceId)
                    _isPlaying.value = true
                } else {
                    alSourcePause(sourceId)
                    _isPlaying.value = false
                }

                while (isActive) {
                    val processed = alGetSourcei(sourceId, AL_BUFFERS_PROCESSED)
                    for (i in 0 until processed) {
                        val bufferId = alSourceUnqueueBuffers(sourceId)
                        
                        val size = alGetBufferi(bufferId, AL_SIZE)
                        val bChannels = alGetBufferi(bufferId, AL_CHANNELS)
                        val bBits = alGetBufferi(bufferId, AL_BITS)
                        val bytesPerFrame = (bChannels * bBits / 8).coerceAtLeast(1)
                        totalSamplesPlayedBase += size / bytesPerFrame

                        val pcmData = pcmChannel.receiveCatching().getOrNull()
                        if (pcmData != null) {
                            totalSamplesQueued += enqueueFft(pcmData)
                            queueBuffer(bufferId, pcmData)
                            if (uploadFailed) break
                        }
                    }

                    if (uploadFailed) break

                    if (_isPlaying.value) {
                        val state = alGetSourcei(sourceId, AL_SOURCE_STATE)
                        if (state != AL_PLAYING && state != AL_PAUSED) {
                            if (alGetSourcei(sourceId, AL_BUFFERS_QUEUED) > 0) {
                                alSourcePlay(sourceId)
                            } else if (pcmChannel.isClosedForReceive) {
                                break
                            }
                        }
                    }

                    val samplesInCurrentBuffer = alGetSourcei(sourceId, AL_SAMPLE_OFFSET)
                    val currentTotalSamples = totalSamplesPlayedBase + samplesInCurrentBuffer
                    _currentPosition.value = currentTotalSamples * 1000 / sampleRate
                    
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

                    if (alGetSourcei(sourceId, AL_BUFFERS_QUEUED) == 0 && pcmChannel.isClosedForReceive) {
                        break
                    }

                    delay(1.milliseconds)
                }

                if (isActive && alGetSourcei(sourceId, AL_BUFFERS_QUEUED) == 0) {
                    _onFinished.emit(Unit)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    println("JvmAudioPlayer error: ${e.message}")
                    e.printStackTrace()
                }
            } finally {
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
