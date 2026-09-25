package dev.dertyp.synara.podcast

import dev.dertyp.data.EpisodePlaybackReport
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.sync.DeviceIdentity
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private val PODCAST_REPORT_TAG = LogTag("podcast-report")

class PodcastProgressReporter(
    private val podcastService: IPodcastService,
    private val progressStore: PodcastProgressStore,
    private val deviceIdentity: DeviceIdentity,
    private val logger: Logger,
    dispatchers: SynaraDispatchers
) {
    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private val reports = Channel<EpisodePlaybackReport>(Channel.UNLIMITED)

    val deviceId: String get() = deviceIdentity.deviceId

    init {
        scope.launch {
            for (report in reports) send(report)
        }
    }

    fun report(episode: PodcastEpisode, positionMs: Long, durationMs: Long?, completed: Boolean = false) {
        reports.trySend(
            EpisodePlaybackReport(
                episodeId = episode.id,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs?.takeIf { it > 0 },
                completed = completed,
                deviceId = deviceId
            )
        )
    }

    private suspend fun send(report: EpisodePlaybackReport) {
        runCatching { podcastService.reportPlayback(report) }
            .onSuccess { progressStore.record(it) }
            .onFailure {
                if (it is CancellationException) throw it
                logger.error(PODCAST_REPORT_TAG, it.message ?: "Episode report failed", it)
            }
    }
}
