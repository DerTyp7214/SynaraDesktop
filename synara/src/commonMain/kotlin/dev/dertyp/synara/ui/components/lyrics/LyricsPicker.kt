package dev.dertyp.synara.ui.components.lyrics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.models.LyricsCandidate
import dev.dertyp.services.models.LyricsMatch
import dev.dertyp.services.models.LyricsSearchResult
import dev.dertyp.services.models.LyricsSyncLevel
import dev.dertyp.synara.lyrics.LyricsRepository
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*
import kotlin.math.abs

private val LYRICS_PICKER_TAG = LogTag("lyricsPicker")

const val LYRICS_PICKER_BUTTON_TAG = "lyricsPickerButton"
const val LYRICS_PICKER_SEARCH_TAG = "lyricsPickerSearch"
const val LYRICS_PICKER_MAKE_DEFAULT_TAG = "lyricsPickerMakeDefault"
fun lyricsPickerCandidateTag(candidateId: PlatformUUID) = "lyricsPickerCandidate-$candidateId"

class LyricsPickerModel(
    private val songId: PlatformUUID,
    private val repository: LyricsRepository,
    private val logger: Logger,
    private val scope: CoroutineScope
) {
    private val _candidates = MutableStateFlow<List<LyricsCandidate>>(emptyList())
    val candidates: StateFlow<List<LyricsCandidate>> = _candidates.asStateFlow()

    private val _defaultCandidateId = MutableStateFlow<PlatformUUID?>(null)
    val defaultCandidateId: StateFlow<PlatformUUID?> = _defaultCandidateId.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var searchJob: Job? = null

    fun load() {
        scope.launch {
            try {
                apply(repository.candidates(songId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(LYRICS_PICKER_TAG, "Loading lyrics candidates of $songId failed", e)
            }
        }
    }

    fun search() {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            _searching.value = true
            try {
                repository.search(songId).collect { candidate ->
                    _candidates.update { merge(it, candidate) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(LYRICS_PICKER_TAG, "Searching lyrics of $songId failed", e)
            } finally {
                _searching.value = false
            }
        }
    }

    fun select(candidateId: PlatformUUID) {
        repository.pick(songId, candidateId.takeUnless { it == _defaultCandidateId.value })
    }

    fun makeDefaultOffered(user: User?, candidateId: PlatformUUID?): Boolean {
        return candidateId != null &&
            candidateId != _defaultCandidateId.value &&
            user?.hasCapability(UserCapability.EDIT) == true
    }

    fun makeDefault(candidateId: PlatformUUID) {
        scope.launch {
            try {
                apply(repository.makeDefault(songId, candidateId))
                repository.pick(songId, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(LYRICS_PICKER_TAG, "Making $candidateId the default lyrics of $songId failed", e)
            }
        }
    }

    private fun apply(result: LyricsSearchResult) {
        _defaultCandidateId.value = result.defaultCandidateId
        _candidates.update { existing -> result.candidates.fold(existing) { list, candidate -> merge(list, candidate) } }
    }

    companion object {
        fun merge(existing: List<LyricsCandidate>, incoming: LyricsCandidate): List<LyricsCandidate> {
            val index = existing.indexOfFirst { it.id == incoming.id }
            val merged = if (index >= 0) {
                existing.toMutableList().also { it[index] = incoming }
            } else {
                existing + incoming
            }
            return merged.sortedBy { it.rank }
        }
    }
}

@Composable
fun LyricsPicker(
    songId: PlatformUUID,
    shown: LyricsCandidate?,
    modifier: Modifier = Modifier,
    repository: LyricsRepository = koinInject(),
    globalState: GlobalStateModel = koinInject(),
    logger: Logger = koinInject()
) {
    val scope = rememberCoroutineScope()
    val model = remember(songId) { LyricsPickerModel(songId, repository, logger, scope) }
    val candidates by model.candidates.collectAsState()
    val searching by model.searching.collectAsState()
    val user by globalState.user.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(model) { model.load() }

    Box(modifier = modifier) {
        TextButton(
            onClick = {
                model.load()
                expanded = true
            },
            modifier = Modifier.testTag(LYRICS_PICKER_BUTTON_TAG)
        ) {
            Icon(
                SynaraIcons.Lyrics.get(),
                contentDescription = stringResource(Res.string.lyrics_versions),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (shown != null) {
                CandidateLabel(shown, style = MaterialTheme.typography.labelLarge)
            } else {
                Text(stringResource(Res.string.lyrics_versions), style = MaterialTheme.typography.labelLarge)
            }
            Icon(SynaraIcons.ChevronDown.get(), contentDescription = null, modifier = Modifier.size(18.dp))
        }

        SynaraMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            candidates.forEach { candidate ->
                DropdownMenuItem(
                    text = { CandidateLabel(candidate, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        model.select(candidate.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag(lyricsPickerCandidateTag(candidate.id)),
                    trailingIcon = if (candidate.id == shown?.id) {
                        { Icon(SynaraIcons.Confirm.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                    } else null
                )
            }

            if (candidates.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            DropdownMenuItem(
                text = { Text(stringResource(Res.string.lyrics_search_providers)) },
                onClick = { model.search() },
                enabled = !searching,
                modifier = Modifier.testTag(LYRICS_PICKER_SEARCH_TAG),
                leadingIcon = {
                    if (searching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(SynaraIcons.Search.get(), contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            )

            if (model.makeDefaultOffered(user, shown?.id)) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.lyrics_make_default)) },
                    onClick = {
                        shown?.let { model.makeDefault(it.id) }
                        expanded = false
                    },
                    modifier = Modifier.testTag(LYRICS_PICKER_MAKE_DEFAULT_TAG),
                    leadingIcon = {
                        Icon(SynaraIcons.Pin.get(), contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                )
            }
        }
    }
}

@Composable
private fun CandidateLabel(candidate: LyricsCandidate, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(candidate.provider.name, style = style)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            stringResource(syncLevelLabel(candidate.syncLevel)),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (candidate.match == LyricsMatch.VERIFIED) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                SynaraIcons.CheckCircle.get(),
                contentDescription = stringResource(Res.string.lyrics_verified),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        candidate.durationGapSec?.takeIf { it != 0 }?.let { gap ->
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                stringResource(Res.string.lyrics_duration_gap, abs(gap)),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun syncLevelLabel(level: LyricsSyncLevel) = when (level) {
    LyricsSyncLevel.PLAIN -> Res.string.lyrics_sync_plain
    LyricsSyncLevel.LINE -> Res.string.lyrics_sync_line
    LyricsSyncLevel.WORD -> Res.string.lyrics_sync_word
}
