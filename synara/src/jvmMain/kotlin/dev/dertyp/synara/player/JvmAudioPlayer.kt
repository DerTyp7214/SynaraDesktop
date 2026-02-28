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
import java.nio.ShortBuffer
import java.util.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
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

    private val _sampleRate = MutableStateFlow(0)
    override val sampleRate = _sampleRate.asStateFlow()

    private val _bitsPerSample = MutableStateFlow(0)
    override val bitsPerSample = _bitsPerSample.asStateFlow()

    private val _bitRate = MutableStateFlow(0L)
    override val bitRate = _bitRate.asStateFlow()

    private val fftAnalyzer = FftAnalyzer(1024)
    override val fftData = fftAnalyzer.fftData

    private val _onFinished = MutableSharedFlow<Unit>()
    override val onFinished = _onFinished.asSharedFlow()

    private var playerJob: Job? = null
    private var lastSongId: PlatformUUID? = null

    private val dataSource = SongDataSource(songService)

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
            _bitRate.value = 0
            _sampleRate.value = 0
            _bitsPerSample.value = 0
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
                _sampleRate.value = session.sampleRate
                _bitsPerSample.value = session.bitsPerSample
                _bitRate.value = session.song.bitRate

                val sampleRate = session.sampleRate
                val channels = session.channels
                val alFormat = if (channels == 1) AL_FORMAT_MONO16 else AL_FORMAT_STEREO16
                var totalSamplesPlayedBase = (startTimeMs * sampleRate) / 1000
                
                // FFT synchronization queue
                val fftQueue = ArrayDeque<Pair<Long, FloatArray>>()
                var totalSamplesQueued = totalSamplesPlayedBase

                val pcmChannel = session.pcmFlow.produceIn(this)

                // Initial buffering
                for (i in 0 until numBuffers) {
                    val buffer = pcmChannel.receiveCatching().getOrNull() ?: break
                    val mags = processFft(buffer, channels)
                    fftQueue.add(totalSamplesQueued to mags)
                    totalSamplesQueued += buffer.remaining() / channels
                    
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
                            val mags = processFft(pcmData, channels)
                            fftQueue.add(totalSamplesQueued to mags)
                            totalSamplesQueued += pcmData.remaining() / channels
                            
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

                    delay(10)
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

    private fun processFft(buffer: ShortBuffer, channels: Int): FloatArray {
        val size = buffer.remaining()
        val pcm = ShortArray(size / channels)
        val startPos = buffer.position()
        for (i in pcm.indices) {
            if (channels == 2) {
                val left = buffer.get(startPos + i * 2)
                val right = buffer.get(startPos + i * 2 + 1)
                pcm[i] = ((left.toInt() + right.toInt()) / 2).toShort()
            } else {
                pcm[i] = buffer.get(startPos + i)
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
