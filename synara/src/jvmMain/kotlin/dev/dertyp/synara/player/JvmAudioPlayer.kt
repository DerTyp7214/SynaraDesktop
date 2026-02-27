package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.services.ISongService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.lwjgl.BufferUtils
import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.*
import java.nio.IntBuffer

@DelicateCoroutinesApi
@OptIn(ExperimentalCoroutinesApi::class)
class JvmAudioPlayer(
    songService: ISongService,
) : AudioPlayer {
    private val audioDispatcher = newSingleThreadContext("OpenAL-Audio")
    private val scope = CoroutineScope(audioDispatcher + SupervisorJob())
    
    private var device: Long = 0
    private var context: Long = 0
    private var sourceId: Int = 0
    private val numBuffers = 4
    private val buffers: IntBuffer = BufferUtils.createIntBuffer(numBuffers)
    
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration = _duration.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    override val volume = _volume.asStateFlow()

    private val _onFinished = MutableSharedFlow<Unit>()
    override val onFinished = _onFinished.asSharedFlow()

    private var playerJob: Job? = null
    private var lastSongId: PlatformUUID? = null

    private val dataSource = SongDataSource(songService, scope)

    init {
        scope.launch {
            try {
                initOpenAL()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun initOpenAL() {
        device = alcOpenDevice(null as java.nio.ByteBuffer?)
        if (device == 0L) throw RuntimeException("Failed to open OpenAL device")
        
        val deviceCaps = ALC.createCapabilities(device)
        context = alcCreateContext(device, null as IntBuffer?)
        if (context == 0L) throw RuntimeException("Failed to create OpenAL context")
        
        if (!alcMakeContextCurrent(context)) {
            throw RuntimeException("Failed to make OpenAL context current")
        }
        AL.createCapabilities(deviceCaps)

        sourceId = alGenSources()
        alGenBuffers(buffers)
    }

    override fun play() {
        scope.launch {
            if (sourceId != 0 && !_isPlaying.value) {
                alSourcePlay(sourceId)
                _isPlaying.value = true
            }
        }
    }

    override fun pause() {
        scope.launch {
            if (sourceId != 0 && _isPlaying.value) {
                alSourcePause(sourceId)
                _isPlaying.value = false
            }
        }
    }

    override fun stop() {
        playerJob?.cancel()
        scope.launch {
            if (sourceId != 0) {
                alSourceStop(sourceId)
                val queued = alGetSourcei(sourceId, AL_BUFFERS_QUEUED)
                repeat(queued) {
                    alSourceUnqueueBuffers(sourceId)
                }
                alSourcei(sourceId, AL_BUFFER, 0)
            }
            _isPlaying.value = false
            _currentPosition.value = 0
        }
    }

    override fun seekTo(positionMs: Long) {
        val songId = lastSongId ?: return
        loadInternal(songId, positionMs)
    }

    override fun setVolume(volume: Float) {
        _volume.value = volume
        scope.launch {
            if (sourceId != 0) {
                alSourcef(sourceId, AL_GAIN, volume)
            }
        }
    }

    override fun load(songId: PlatformUUID) {
        loadInternal(songId, 0L)
    }

    private fun loadInternal(songId: PlatformUUID, startTimeMs: Long) {
        stop()
        lastSongId = songId
        
        playerJob = scope.launch {
            try {
                val session = dataSource.createPlaybackSession(songId, startTimeMs, scope) ?: return@launch
                _duration.value = session.song.duration

                val sampleRate = session.sampleRate
                val channels = session.channels
                val alFormat = if (channels == 1) AL_FORMAT_MONO16 else AL_FORMAT_STEREO16
                var totalSamplesPlayedBase = (startTimeMs * sampleRate) / 1000

                val pcmChannel = session.pcmFlow.produceIn(this)

                // Initial buffering
                for (i in 0 until numBuffers) {
                    val buffer = pcmChannel.receiveCatching().getOrNull() ?: break
                    alBufferData(buffers.get(i), alFormat, buffer, sampleRate)
                    alSourceQueueBuffers(sourceId, buffers.get(i))
                }

                alSourcef(sourceId, AL_GAIN, _volume.value)
                alSourcePlay(sourceId)
                _isPlaying.value = true

                while (isActive) {
                    val processed = alGetSourcei(sourceId, AL_BUFFERS_PROCESSED)
                    for (i in 0 until processed) {
                        val bufferId = alSourceUnqueueBuffers(sourceId)
                        
                        val size = alGetBufferi(bufferId, AL_SIZE)
                        val bChannels = alGetBufferi(bufferId, AL_CHANNELS)
                        val bBits = alGetBufferi(bufferId, AL_BITS)
                        totalSamplesPlayedBase += size / (bChannels * bBits / 8)

                        val pcmData = pcmChannel.receiveCatching().getOrNull()
                        if (pcmData != null) {
                            alBufferData(bufferId, alFormat, pcmData, sampleRate)
                            alSourceQueueBuffers(sourceId, bufferId)
                        }
                    }

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
                    _currentPosition.value = (totalSamplesPlayedBase + samplesInCurrentBuffer) * 1000 / sampleRate
                    
                    if (alGetSourcei(sourceId, AL_BUFFERS_QUEUED) == 0 && pcmChannel.isClosedForReceive) {
                        break
                    }

                    delay(100)
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
            }
        }
    }

    override fun release() {
        runBlocking {
            stop()
            val job = scope.launch {
                if (sourceId != 0) alDeleteSources(sourceId)
                if (buffers.get(0) != 0) alDeleteBuffers(buffers)

                if (context != 0L) {
                    alcMakeContextCurrent(0)
                    alcDestroyContext(context)
                }
                if (device != 0L) alcCloseDevice(device)
            }
            job.join()
            audioDispatcher.close()
            scope.cancel()
        }
    }
}
