package dev.dertyp.synara.ui.components.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dertyp.data.PodcastIndexResult
import dev.dertyp.data.PodcastShow
import dev.dertyp.formatDate
import dev.dertyp.platformDateFromEpochMilliseconds
import dev.dertyp.synara.podcast.htmlToPlainText
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DirectoryResultDialog(
    result: PodcastIndexResult,
    onDismissRequest: () -> Unit,
    onSubscribe: suspend (String) -> PodcastShow,
    onSubscribed: (PodcastShow) -> Unit
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var isSubscribing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val description = remember(result.description) { htmlToPlainText(result.description) }
    val lastPublished = remember(result.lastPublishedAt) {
        result.lastPublishedAt?.let { platformDateFromEpochMilliseconds(it).formatDate() }
    }

    SynaraDialog(isOpen = true, onDismissRequest = { if (!isSubscribing) onDismissRequest() }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.width(560.dp).heightIn(max = 720.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    DirectoryArtwork(
                        imageUrl = result.imageUrl,
                        modifier = Modifier.size(140.dp),
                        shape = MaterialTheme.shapes.medium
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        result.author?.takeIf { it.isNotBlank() }?.let { author ->
                            Text(
                                text = author,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = stringResource(Res.string.podcast_browse_directory_source, result.indexName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val facts = buildList {
                            result.episodeCount?.let {
                                add(pluralStringResource(Res.plurals.podcast_episode_count, it, it))
                            }
                            result.language?.takeIf { it.isNotBlank() }?.let {
                                add(stringResource(Res.string.podcast_directory_language, it))
                            }
                            lastPublished?.let {
                                add(stringResource(Res.string.podcast_directory_last_published, it))
                            }
                        }
                        if (facts.isNotEmpty()) {
                            Text(
                                text = facts.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (result.explicit || result.categories.isNotEmpty()) {
                            FlowRow(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (result.explicit) {
                                    PodcastBadge(stringResource(Res.string.podcast_explicit), error = true)
                                }
                                result.categories.forEach { PodcastBadge(it) }
                            }
                        }
                    }
                }

                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SelectionContainer(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(Res.string.podcast_subscribe_failed, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val link = result.link?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    if (link != null) {
                        TextButton(onClick = { runCatching { uriHandler.openUri(link) } }) {
                            Icon(SynaraIcons.Globe.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.podcast_website))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissRequest, enabled = !isSubscribing) {
                        Text(stringResource(Res.string.cancel))
                    }
                    Button(
                        onClick = {
                            isSubscribing = true
                            error = null
                            scope.launch {
                                try {
                                    val show = onSubscribe(result.feedUrl)
                                    onSubscribed(show)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: ""
                                    isSubscribing = false
                                }
                            }
                        },
                        enabled = !isSubscribing
                    ) {
                        if (isSubscribing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.podcast_subscribing))
                        } else {
                            Icon(SynaraIcons.Add.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.podcast_subscribe))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PodcastBadge(text: String, error: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}
