package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

private val TIMECODE_AUTOMATION_TAG = LogTag("timecode-automation")

sealed interface TagCommand {
    data class Seek(val positionMs: Long, val fadeInMs: Long = 0L) : TagCommand
    data object FinishTrack : TagCommand
    data class FadeOut(val untilMs: Long, val fadeMs: Long) : TagCommand
}

object TimecodeTagRules {
    const val FADE_MS = 1500L

    private data class Span(val start: Long, val end: Long, val fadeIn: Boolean, val fadeOut: Boolean) {
        val fadeMs: Long get() = minOf(FADE_MS, (end - start) / 2)
    }

    fun isActionValid(tag: TimecodeTag): Boolean {
        val end = tag.endMs
        return when (tag.action) {
            TimecodeTagAction.NONE -> true
            TimecodeTagAction.PLAY_ONLY, TimecodeTagAction.SKIP ->
                tag.type == TimecodeTagType.CHAPTER && end != null && end > tag.timestampMs
            TimecodeTagAction.SKIP_TO, TimecodeTagAction.PLAY_UNTIL ->
                tag.type == TimecodeTagType.MARKER && end == null
        }
    }

    fun allowedActions(type: TimecodeTagType): List<TimecodeTagAction> = when (type) {
        TimecodeTagType.CHAPTER -> listOf(TimecodeTagAction.NONE, TimecodeTagAction.PLAY_ONLY, TimecodeTagAction.SKIP)
        TimecodeTagType.MARKER -> listOf(TimecodeTagAction.NONE, TimecodeTagAction.SKIP_TO, TimecodeTagAction.PLAY_UNTIL)
        TimecodeTagType.NOTE -> listOf(TimecodeTagAction.NONE)
    }

    fun decide(
        tags: List<TimecodeTag>,
        prevPos: Long,
        pos: Long,
        durationMs: Long,
        songStart: Boolean
    ): TagCommand? {
        val active = tags.filter { it.action != TimecodeTagAction.NONE && isActionValid(it) }
        if (active.isEmpty()) return null
        return if (songStart) decideStart(active, pos, durationMs) else decideCrossing(active, prevPos, pos, durationMs)
    }

    private fun chapterFade(tag: TimecodeTag): Long {
        val end = tag.endMs ?: return FADE_MS
        return minOf(FADE_MS, (end - tag.timestampMs) / 2)
    }

    private fun playOnlySpans(tags: List<TimecodeTag>): List<Span> {
        val spans = mutableListOf<Span>()
        tags.filter { it.action == TimecodeTagAction.PLAY_ONLY }
            .sortedBy { it.timestampMs }
            .forEach { tag ->
                val end = tag.endMs ?: return@forEach
                val last = spans.lastOrNull()
                if (last != null && tag.timestampMs <= last.end) {
                    if (end > last.end) spans[spans.lastIndex] = last.copy(end = end, fadeOut = tag.fade)
                } else {
                    spans += Span(tag.timestampMs, end, tag.fade, tag.fade)
                }
            }
        return spans
    }

    private fun skipTo(target: Long, durationMs: Long, fadeMs: Long): TagCommand =
        if (durationMs > 0 && target >= durationMs) TagCommand.FinishTrack else TagCommand.Seek(target, fadeMs)

    private fun decideStart(tags: List<TimecodeTag>, pos: Long, durationMs: Long): TagCommand? {
        val spans = playOnlySpans(tags)
        if (spans.isNotEmpty()) {
            if (spans.any { pos >= it.start && pos < it.end }) return null
            val next = spans.firstOrNull { it.start > pos } ?: return TagCommand.FinishTrack
            return skipTo(next.start, durationMs, if (next.fadeIn) next.fadeMs else 0L)
        }

        val marker = tags.filter { it.action == TimecodeTagAction.SKIP_TO && it.timestampMs > pos }
            .minByOrNull { it.timestampMs }
        if (marker != null) {
            return skipTo(marker.timestampMs, durationMs, if (marker.fade) FADE_MS else 0L)
        }

        val skipped = tags.firstOrNull { tag ->
            val end = tag.endMs
            tag.action == TimecodeTagAction.SKIP && end != null && pos >= tag.timestampMs && pos < end
        } ?: return null
        return skipTo(skipped.endMs ?: return null, durationMs, if (skipped.fade) chapterFade(skipped) else 0L)
    }

    private fun decideCrossing(tags: List<TimecodeTag>, prevPos: Long, pos: Long, durationMs: Long): TagCommand? {
        if (prevPos < 0 || pos <= prevPos) return null
        fun crossed(at: Long) = prevPos < at && at <= pos

        val candidates = mutableListOf<Pair<Long, TagCommand>>()

        val spans = playOnlySpans(tags)
        spans.forEachIndexed { index, span ->
            if (crossed(span.end)) {
                val next = spans.getOrNull(index + 1)
                candidates += span.end to if (next != null) {
                    skipTo(next.start, durationMs, if (next.fadeIn) next.fadeMs else 0L)
                } else {
                    TagCommand.FinishTrack
                }
            }
            val fadeMs = span.fadeMs
            if (span.fadeOut && fadeMs > 0 && crossed(span.end - fadeMs) && pos < span.end) {
                candidates += (span.end - fadeMs) to TagCommand.FadeOut(span.end, fadeMs)
            }
        }

        tags.forEach { tag ->
            when (tag.action) {
                TimecodeTagAction.SKIP -> {
                    val end = tag.endMs ?: return@forEach
                    val fadeMs = if (tag.fade) chapterFade(tag) else 0L
                    if (crossed(tag.timestampMs) && pos < end) {
                        candidates += tag.timestampMs to skipTo(end, durationMs, fadeMs)
                    }
                    val fadeStart = tag.timestampMs - fadeMs
                    if (fadeMs > 0 && fadeStart >= 0 && crossed(fadeStart) && pos < tag.timestampMs) {
                        candidates += fadeStart to TagCommand.FadeOut(tag.timestampMs, fadeMs)
                    }
                }

                TimecodeTagAction.PLAY_UNTIL -> {
                    if (crossed(tag.timestampMs)) {
                        candidates += tag.timestampMs to TagCommand.FinishTrack
                    }
                    val fadeMs = if (tag.fade) minOf(FADE_MS, tag.timestampMs) else 0L
                    if (fadeMs > 0 && crossed(tag.timestampMs - fadeMs) && pos < tag.timestampMs) {
                        candidates += (tag.timestampMs - fadeMs) to TagCommand.FadeOut(tag.timestampMs, fadeMs)
                    }
                }

                else -> Unit
            }
        }

        return candidates
            .minWithOrNull(compareBy<Pair<Long, TagCommand>> { it.first }.thenBy { if (it.second is TagCommand.FadeOut) 1 else 0 })
            ?.second
    }
}

class TimecodeTagAutomation(
    private val playerModel: PlayerModel,
    private val tagStore: TimecodeTagStore,
    private val logger: Logger,
    dispatchers: SynaraDispatchers
) {
    companion object {
        private const val START_WINDOW_MS = 3000L
        private const val MAX_STEP_MS = 1500L
        private const val FADE_OVERRUN_MS = 250L
        private const val MAX_OWN_SEEKS = 8
    }

    private val scope = CoroutineScope(dispatchers.createNamed("TimecodeTagAutomation") + SupervisorJob())
    private var started = false

    private var songId: PlatformUUID? = null
    private var observedSongId: PlatformUUID? = null
    private var songTags: List<TimecodeTag>? = null
    private var songDurationMs = 0L
    private var prevPos = -1L
    private var pendingStart = false
    private var seekGuard: Long? = null
    private val ownSeeks = ArrayDeque<Long>()
    private var fadeOut: TagCommand.FadeOut? = null
    private var fadeIn: Pair<Long, Long>? = null
    private var gain = 1f

    fun start() {
        if (started) return
        started = true

        scope.launch { playerModel.trackStarts.collect { guarded("track start") { onTrackStart(it) } } }
        scope.launch { playerModel.seekEvents.collect { guarded("seek") { onSeek(it) } } }
        scope.launch {
            guarded("current song") { syncCurrentSong(playerModel.currentPosition.value) }
            playerModel.currentPosition.collect { guarded("tick") { onTick(it) } }
        }
    }

    private inline fun guarded(event: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TIMECODE_AUTOMATION_TAG, "Timecode tag automation failed on $event for song $songId", e)
        }
    }

    private fun onTrackStart(start: PlayerModel.TrackStart) {
        val id = start.songId
        songId = id
        songTags = start.song?.playbackTags
        songDurationMs = start.song?.duration ?: 0L
        tagStore.refreshIfCached(id)
        prevPos = -1L
        pendingStart = true
        resetTransientState()
    }

    private fun syncCurrentSong(pos: Long) {
        val current = playerModel.currentSong.value ?: return
        if (current.id == observedSongId) return
        observedSongId = current.id
        if (current.id != songId) adopt(current, pos)
    }

    private fun adopt(song: UserSong, pos: Long) {
        songId = song.id
        songTags = song.playbackTags
        songDurationMs = song.duration
        tagStore.ensureLoaded(song.id)
        prevPos = -1L
        pendingStart = pos in 0..START_WINDOW_MS
        resetTransientState()
    }

    private fun resetTransientState() {
        seekGuard = null
        ownSeeks.clear()
        fadeOut = null
        fadeIn = null
        applyGain(1f)
    }

    private fun onSeek(positionMs: Long) {
        if (ownSeeks.remove(positionMs)) return
        pendingStart = false
        prevPos = -1L
        seekGuard = null
        clearFades()
    }

    private fun onTick(pos: Long) {
        syncCurrentSong(pos)
        if (playerModel.isRemote) {
            prevPos = -1L
            clearFades()
            return
        }
        val id = songId ?: return
        val guard = seekGuard
        if (guard != null) {
            if (pos < guard) return
            seekGuard = null
        }
        val tags = playbackTags(id)
        if (tags == null) {
            prevPos = pos
            return
        }
        val durationMs = songDurationMs.takeIf { it > 0 } ?: playerModel.duration.value

        if (pendingStart) {
            prevPos = pos
            if (pos > START_WINDOW_MS) return
            pendingStart = false
            TimecodeTagRules.decide(tags, pos, pos, durationMs, songStart = true)?.let { execute(it, pos) }
            return
        }

        val prev = prevPos
        prevPos = pos
        if (prev < 0 || pos <= prev || pos - prev > MAX_STEP_MS) {
            updateFade(pos)
            return
        }

        val command = TimecodeTagRules.decide(tags, prev, pos, durationMs, songStart = false)
        if (command != null) execute(command, pos) else updateFade(pos)
    }

    private fun playbackTags(id: PlatformUUID): List<TimecodeTag>? {
        tagStore.cached(id)?.let { cached -> return cached.filter { it.action != TimecodeTagAction.NONE } }
        tagStore.ensureLoaded(id)
        songTags?.let { return it }
        return playerModel.currentSong.value?.takeIf { it.id == id }?.playbackTags
    }

    private fun execute(command: TagCommand, pos: Long) {
        logger.info(TIMECODE_AUTOMATION_TAG, "Executing $command at ${pos}ms for song $songId")
        when (command) {
            is TagCommand.FadeOut -> {
                fadeOut = command
                fadeIn = null
                updateFade(pos)
            }

            is TagCommand.Seek -> {
                fadeOut = null
                if (command.fadeInMs > 0) {
                    fadeIn = command.positionMs to command.fadeInMs
                    applyGain(0f)
                } else {
                    fadeIn = null
                    applyGain(1f)
                }
                ownSeeks.addLast(command.positionMs)
                while (ownSeeks.size > MAX_OWN_SEEKS) ownSeeks.removeFirst()
                prevPos = command.positionMs - 1
                seekGuard = command.positionMs
                playerModel.seekTo(command.positionMs)
            }

            TagCommand.FinishTrack -> {
                fadeOut = null
                fadeIn = null
                prevPos = -1L
                gain = 1f
                playerModel.finishTrack()
            }
        }
    }

    private fun updateFade(pos: Long) {
        val out = fadeOut
        if (out != null) {
            if (pos > out.untilMs + FADE_OVERRUN_MS || pos < out.untilMs - out.fadeMs) {
                clearFades()
            } else {
                applyGain(((out.untilMs - pos).toFloat() / out.fadeMs).coerceIn(0f, 1f))
            }
            return
        }
        val (from, fadeMs) = fadeIn ?: return
        if (pos >= from + fadeMs || pos < from) {
            fadeIn = null
            applyGain(1f)
        } else {
            applyGain(((pos - from).toFloat() / fadeMs).coerceIn(0f, 1f))
        }
    }

    private fun clearFades() {
        fadeOut = null
        fadeIn = null
        applyGain(1f)
    }

    private fun applyGain(value: Float) {
        if (value == gain) return
        if (value != 0f && value != 1f && abs(value - gain) < 0.01f) return
        gain = value
        playerModel.setFadeGain(value)
    }
}
