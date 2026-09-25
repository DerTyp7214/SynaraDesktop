package dev.dertyp.synara.ui.components.podcast

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastTranscript
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.podcast.ParsedTranscript
import dev.dertyp.synara.podcast.PodcastTranscriptParser
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.LyricLine
import dev.dertyp.synara.ui.components.LyricsView
import dev.dertyp.synara.ui.components.SynaraMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

private sealed class TranscriptListState {
    data object Loading : TranscriptListState()
    data object Failed : TranscriptListState()
    data class Loaded(val transcripts: List<PodcastTranscript>) : TranscriptListState()
}

private sealed class TranscriptContentState {
    data object Loading : TranscriptContentState()
    data object Failed : TranscriptContentState()
    data object Empty : TranscriptContentState()
    data class Loaded(val transcript: ParsedTranscript) : TranscriptContentState()
}

@Composable
fun TranscriptPanel(
    episode: PodcastEpisode?,
    position: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    podcastService: IPodcastService = koinInject()
) {
    val episodeId = episode?.id
    val hasTranscript = episode?.hasTranscript == true

    val listState by produceState<TranscriptListState>(TranscriptListState.Loading, episodeId, hasTranscript) {
        value = TranscriptListState.Loading
        if (episodeId == null || !hasTranscript) {
            value = TranscriptListState.Loaded(emptyList())
            return@produceState
        }
        value = try {
            TranscriptListState.Loaded(podcastService.getTranscripts(episodeId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TranscriptListState.Failed
        }
    }

    val transcripts = (listState as? TranscriptListState.Loaded)?.transcripts.orEmpty()
    val languages = remember(transcripts) { PodcastTranscriptParser.languages(transcripts) }
    val systemLanguage = remember { Locale.current.language }
    var selectedLanguage by remember(transcripts) {
        mutableStateOf(PodcastTranscriptParser.preferredLanguage(transcripts, systemLanguage))
    }
    val candidates = remember(transcripts, selectedLanguage) {
        PodcastTranscriptParser.candidates(transcripts, selectedLanguage)
    }

    val contentState by produceState<TranscriptContentState>(TranscriptContentState.Loading, candidates) {
        value = TranscriptContentState.Loading
        if (candidates.isEmpty()) {
            value = TranscriptContentState.Empty
            return@produceState
        }
        var failed = false
        for (candidate in candidates) {
            try {
                val content = podcastService.getTranscript(candidate.id) ?: continue
                val parsed = PodcastTranscriptParser.parse(content.content, content.transcript.type)
                if (!parsed.isEmpty) {
                    value = TranscriptContentState.Loaded(parsed)
                    return@produceState
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
        value = if (failed) TranscriptContentState.Failed else TranscriptContentState.Empty
    }

    val effectiveState = when (listState) {
        TranscriptListState.Loading -> TranscriptContentState.Loading
        TranscriptListState.Failed -> TranscriptContentState.Failed
        is TranscriptListState.Loaded -> if (transcripts.isEmpty()) TranscriptContentState.Empty else contentState
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = effectiveState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { state ->
                when (state) {
                    is TranscriptContentState.Loaded -> state.transcript
                    else -> state
                }
            },
            label = "transcriptContent",
            modifier = Modifier.fillMaxSize()
        ) { state ->
            when (state) {
                TranscriptContentState.Loading -> TranscriptMessage(stringResource(Res.string.podcast_transcript_loading), loading = true)
                TranscriptContentState.Failed -> TranscriptMessage(stringResource(Res.string.podcast_transcript_failed))
                TranscriptContentState.Empty -> TranscriptMessage(stringResource(Res.string.podcast_no_transcript))
                is TranscriptContentState.Loaded -> {
                    val parsed = state.transcript
                    if (parsed.timed) {
                        val lines = remember(parsed) {
                            parsed.cues.map { LyricLine(time = it.startMs, content = it.text, label = it.speaker) }
                                .let(::dropRepeatedLabels)
                        }
                        LyricsView(
                            lines = lines,
                            position = position,
                            onSeek = onSeek,
                            followLabel = stringResource(Res.string.podcast_transcript_follow)
                        )
                    } else {
                        UntimedTranscript(parsed)
                    }
                }
            }
        }

        if (languages.size > 1) {
            LanguagePicker(
                languages = languages,
                selected = selectedLanguage,
                onSelect = { selectedLanguage = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )
        }
    }
}

private fun dropRepeatedLabels(lines: List<LyricLine>): List<LyricLine> {
    var previous: String? = null
    return lines.map { line ->
        val label = line.label
        val result = if (label != null && label == previous) line.copy(label = null) else line
        if (label != null) previous = label
        result
    }
}

@Composable
private fun TranscriptMessage(text: String, loading: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color.White.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun UntimedTranscript(transcript: ParsedTranscript) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val fadeHeight = 80.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        fadeHeight / size.height to Color.Black,
                        (size.height - fadeHeight) / size.height to Color.Black,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            },
        contentPadding = PaddingValues(vertical = 80.dp, horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(transcript.cues, key = { index, _ -> index }) { _, cue ->
            Text(
                text = cue.text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LanguagePicker(
    languages: List<String?>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val unknown = stringResource(Res.string.podcast_transcript_language_unknown)
    fun labelOf(language: String?) = language?.uppercase() ?: unknown

    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Icon(
                SynaraIcons.Globe.get(),
                contentDescription = stringResource(Res.string.podcast_transcript_language),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(labelOf(selected), style = MaterialTheme.typography.labelLarge)
            Icon(SynaraIcons.ChevronDown.get(), contentDescription = null, modifier = Modifier.size(18.dp))
        }

        SynaraMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(labelOf(language)) },
                    onClick = {
                        onSelect(language)
                        expanded = false
                    },
                    trailingIcon = if (language == selected) {
                        { Icon(SynaraIcons.Confirm.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                    } else null
                )
            }
        }
    }
}
