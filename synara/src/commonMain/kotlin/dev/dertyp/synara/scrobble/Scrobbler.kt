package dev.dertyp.synara.scrobble

import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.PlayerModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val LogTag.Companion.RECENTLY_PLAYED get() = LogTag("recently_played")

class ScrobbleTimer(private val scope: CoroutineScope) {

    private val _time = MutableStateFlow(0)
    val time: StateFlow<Int> = _time.asStateFlow()

    private var timerJob: Job? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _time.value += 1
            }
        }
    }

    fun pause() {
        isRunning = false
        timerJob?.cancel()
    }

    fun reset() {
        _time.value = 0
    }

    fun stop() {
        pause()
        _time.value = 0
    }
}

class ScrobblerService(
    private val playerModel: PlayerModel,
    private val logger: Logger
) : KoinComponent {
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val jobs = mutableListOf<Job>()

    private var isRunning = false

    private val scrobblers = mutableListOf<BaseScrobbler>()

    private val _triggeredSong = MutableStateFlow<UserSong?>(null)
    val triggeredSong: StateFlow<UserSong?> = _triggeredSong

    private val _newSong = MutableStateFlow<UserSong?>(null)
    val newSong: StateFlow<UserSong?> = _newSong

    private val scrobblerTimer = ScrobbleTimer(scope)
    val scrobbledFor: StateFlow<Int> = scrobblerTimer.time

    private val _resetTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetTrigger: SharedFlow<Unit> = _resetTrigger

    fun start() {
        if (isRunning) return
        isRunning = true
        jobs += scope.launch {
            scrobblers.forEach { it.startScrobbler() }

            playerModel.currentSong
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { song ->
                    if (song != null) {
                        scrobblerTimer.reset()
                        _resetTrigger.emit(Unit)
                    }
                    _triggeredSong.value = null
                    _newSong.value = song
                }
        }
        jobs += scope.launch {
            scrobblerTimer.time
                .filter {
                    newSong.value != null && BaseScrobbler.isScrobbled(
                        it.seconds,
                        (newSong.value?.duration ?: Long.MAX_VALUE).milliseconds
                    )
                }
                .filter { _triggeredSong.value?.id != newSong.value?.id }
                .collectLatest {
                    _triggeredSong.value = newSong.value
                }
        }
        jobs += scope.launch {
            playerModel.isPlaying.collectLatest { isPlaying ->
                if (isPlaying) scrobblerTimer.start()
                else scrobblerTimer.pause()
            }
        }
        logger.info(LogTag.SCROBBLER, "ScrobblerService started")
    }

    fun <T : BaseScrobbler> registerScrobbler(scrobblerClass: KClass<T>) {
        logger.info(LogTag.SCROBBLER, "Registering: ${scrobblerClass.simpleName}")
        if (scrobblers.any { it::class == scrobblerClass }) return

        val scrobblerInstance = getKoin().get<T>(clazz = scrobblerClass)

        if (isRunning) scrobblerInstance.startScrobbler()
        scrobblers.add(scrobblerInstance)

        logger.info(LogTag.SCROBBLER, "Registered: ${scrobblerClass.simpleName}")
    }

    fun <T : BaseScrobbler> unregisterScrobbler(scrobblerClass: KClass<T>) {
        logger.info(LogTag.SCROBBLER, "Unregistering: ${scrobblerClass.simpleName}")
        val scrobblerInstance = scrobblers.find { it::class == scrobblerClass } ?: return

        if (scrobblerInstance.isRunning) scrobblerInstance.stopScrobbler()
        scrobblers.remove(scrobblerInstance)

        logger.info(LogTag.SCROBBLER, "Unregistered: ${scrobblerClass.simpleName}")
    }

    fun registeredScrobblers(): List<BaseScrobbler> {
        return scrobblers
    }

    fun stop() {
        while (jobs.isNotEmpty()) jobs.removeAt(0).cancel()

        scrobblers.forEach { it.stopScrobbler() }
        isRunning = false
        logger.info(LogTag.SCROBBLER, "ScrobblerService stopped")
    }
}

abstract class BaseScrobbler : KoinComponent {
    companion object {
        const val MIN_SCROBBLE_DIV = 2

        fun requiredDuration(position: Duration, duration: Duration): Duration = when {
            (duration / MIN_SCROBBLE_DIV) > 3.minutes -> 3.minutes
            else -> (duration / MIN_SCROBBLE_DIV)
        } - position

        fun isScrobbled(position: Duration, duration: Duration): Boolean {
            return requiredDuration(position, duration) <= Duration.ZERO
        }
    }

    open val tintIcon: Boolean = true

    abstract val icon: String
    abstract val name: String
    open val sortOrder: Int = 0

    private val scrobblerService: ScrobblerService by inject()
    internal val logger: Logger by inject()
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableListOf<Job>()

    var isRunning = false
        private set

    operator fun plusAssign(job: Job) {
        jobs.add(job)
    }

    operator fun minusAssign(job: Job) {
        jobs.remove(job)
    }

    open fun onStart() {}

    fun startScrobbler() {
        if (isRunning) return
        isRunning = true
        jobs += scope.launch {
            scrobblerService.triggeredSong.collectLatest { song ->
                if (song != null) try {
                    triggered(song)
                } catch (e: Exception) {
                    logger.error(
                        LogTag.SCROBBLER,
                        "Error in triggered scrobbler ${this@BaseScrobbler::class.simpleName}",
                        e
                    )
                }
            }
        }
        jobs += scope.launch {
            scrobblerService.newSong.collectLatest { song ->
                try {
                    newSong(song)
                } catch (e: Exception) {
                    logger.error(
                        LogTag.SCROBBLER,
                        "Error in newSong scrobbler ${this@BaseScrobbler::class.simpleName}",
                        e
                    )
                }
            }
        }
        jobs += scope.launch {
            scrobblerService.resetTrigger.collectLatest {
                reset()
            }
        }
        try {
            onStart()
        } catch (_: Exception) {
        }
    }

    fun stopScrobbler() {
        while (jobs.isNotEmpty()) jobs.removeAt(0).cancel()
        isRunning = false
        try {
            onStop()
        } catch (_: Exception) {
        }
    }

    open suspend fun newSong(song: UserSong?) {}
    open suspend fun triggered(song: UserSong) {}
    open suspend fun reset() {}
    open fun onStop() {}
}
