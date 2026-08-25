package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import dev.dertyp.core.joinArtists
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.formatDateTime
import dev.dertyp.synara.game.*
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import dev.dertyp.synara.viewmodels.SongGuessScreenModel
import dev.dertyp.synara.viewmodels.SongGuessScreenModel.Phase
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class SongGuessScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<SongGuessScreenModel>()
        val state by screenModel.state.collectAsState()
        val leaderboard by screenModel.leaderboardEntries.collectAsState()
        val playlists by screenModel.userPlaylists.collectAsState()

        var showClearDialog by remember { mutableStateOf(false) }
        var leaderboardRounds by remember { mutableStateOf<Int?>(null) }

        val lazyListState = rememberLazyListState()

        Scaffold(containerColor = Color.Transparent) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(Res.string.song_guess),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (state.phase == Phase.PLAYING || state.phase == Phase.REVEAL) {
                                TextButton(onClick = { screenModel.abortGame() }) {
                                    Text(stringResource(Res.string.song_guess_give_up))
                                }
                            }
                        }
                    }

                    when (state.phase) {
                        Phase.SETUP, Phase.STARTING -> {
                            item { SetupSection(screenModel, state, playlists) }
                            item {
                                LeaderboardSection(
                                    entries = leaderboard,
                                    state = state,
                                    selectedRounds = leaderboardRounds,
                                    onSelectRounds = { leaderboardRounds = it },
                                    onClear = { showClearDialog = true }
                                )
                            }
                        }

                        Phase.PLAYING -> item { PlayingSection(screenModel, state) }
                        Phase.REVEAL -> item { RevealSection(screenModel, state) }
                        Phase.FINISHED -> item { FinishedSection(screenModel, state) }
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }

        SynaraAlertDialog(
            isOpen = showClearDialog,
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(Res.string.song_guess_clear_leaderboard)) },
            text = { Text(stringResource(Res.string.song_guess_clear_leaderboard_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    screenModel.clearLeaderboard()
                    showClearDialog = false
                }) {
                    Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }

    // ------------------------------------------------------------------ setup

    @Composable
    private fun SetupSection(
        screenModel: SongGuessScreenModel,
        state: SongGuessScreenModel.SongGuessState,
        playlists: List<dev.dertyp.data.UserPlaylist>
    ) {
        val config = state.config
        val isStarting = state.phase == Phase.STARTING

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(Res.string.song_guess_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingsCard {
                SectionTitle(stringResource(Res.string.song_guess_source))
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    PoolSource.entries.forEachIndexed { index, source ->
                        SegmentedButton(
                            selected = config.source == source,
                            onClick = { screenModel.setSource(source) },
                            enabled = !isStarting,
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = PoolSource.entries.size)
                        ) {
                            Text(
                                when (source) {
                                    PoolSource.ALL -> stringResource(Res.string.song_guess_source_all)
                                    PoolSource.LIKED -> stringResource(Res.string.song_guess_source_liked)
                                    PoolSource.PLAYLISTS -> stringResource(Res.string.song_guess_source_playlists)
                                }
                            )
                        }
                    }
                }

                if (config.source == PoolSource.PLAYLISTS) {
                    Spacer(Modifier.height(12.dp))
                    if (playlists.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.song_guess_no_playlists),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { screenModel.setAllPlaylists(true) }) {
                                Text(stringResource(Res.string.song_guess_select_all))
                            }
                            TextButton(onClick = { screenModel.setAllPlaylists(false) }) {
                                Text(stringResource(Res.string.song_guess_select_none))
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            playlists.forEach { playlist ->
                                FilterChip(
                                    selected = playlist.id in config.playlistIds,
                                    onClick = { screenModel.togglePlaylist(playlist.id) },
                                    enabled = !isStarting,
                                    label = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                }
            }

            SettingsCard {
                SectionTitle(stringResource(Res.string.song_guess_artists))
                Text(
                    text = stringResource(Res.string.song_guess_artists_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (state.selectedArtists.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        state.selectedArtists.forEach { artist ->
                            InputChip(
                                selected = true,
                                onClick = { screenModel.removeArtist(artist.id) },
                                enabled = !isStarting,
                                label = { Text(artist.name) },
                                trailingIcon = {
                                    Icon(SynaraIcons.Close.get(), contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                InternalTextField(
                    value = state.artistQuery,
                    onValueChange = { screenModel.searchArtists(it) },
                    placeholder = { Text(stringResource(Res.string.song_guess_search_artists)) },
                    leadingIcon = { Icon(SynaraIcons.Search.get(), contentDescription = null) },
                    singleLine = true,
                    enabled = !isStarting,
                    modifier = Modifier.fillMaxWidth()
                )
                state.artistSearchResults
                    .filter { r -> state.selectedArtists.none { it.id == r.id } }
                    .forEach { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { screenModel.addArtist(artist) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SynaraImage(
                                imageId = artist.imageId,
                                size = 32.dp,
                                shape = MaterialTheme.shapes.extraLarge,
                                fallbackIcon = SynaraIcons.Artists
                            )
                            Text(artist.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsCard(modifier = Modifier.weight(1f)) {
                    SectionTitle(stringResource(Res.string.song_guess_rounds))
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ROUND_OPTIONS.forEachIndexed { index, rounds ->
                            SegmentedButton(
                                selected = config.rounds == rounds,
                                onClick = { screenModel.setRounds(rounds) },
                                enabled = !isStarting,
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = ROUND_OPTIONS.size)
                            ) { Text(rounds.toString()) }
                        }
                    }
                }
                SettingsCard(modifier = Modifier.weight(1f)) {
                    SectionTitle(stringResource(Res.string.song_guess_snippet_start))
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SnippetStart.entries.forEachIndexed { index, start ->
                            SegmentedButton(
                                selected = config.snippetStart == start,
                                onClick = { screenModel.setSnippetStart(start) },
                                enabled = !isStarting,
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = SnippetStart.entries.size)
                            ) {
                                Text(
                                    when (start) {
                                        SnippetStart.SONG_START -> stringResource(Res.string.song_guess_snippet_start_song)
                                        SnippetStart.RANDOM -> stringResource(Res.string.song_guess_snippet_start_random)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    text = if (error == SongGuessScreenModel.ERROR_NOT_ENOUGH_SONGS)
                        stringResource(Res.string.song_guess_not_enough_songs) else error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            val canStart = !isStarting &&
                (config.source != PoolSource.PLAYLISTS || config.playlistIds.isNotEmpty())
            Button(
                onClick = { screenModel.startGame() },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isStarting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.song_guess_starting))
                } else {
                    Icon(SynaraIcons.Play.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.song_guess_start))
                }
            }
        }
    }

    // ------------------------------------------------------------------ playing

    @Composable
    private fun PlayingSection(screenModel: SongGuessScreenModel, state: SongGuessScreenModel.SongGuessState) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScoreHeader(state)

            SettingsCard {
                Text(
                    text = stringResource(Res.string.song_guess_attempt_of, state.attempt + 1, MAX_ATTEMPTS),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                SnippetLadder(attempt = state.attempt)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledIconButton(
                        onClick = { if (state.isSnippetPlaying) screenModel.stopSnippet() else screenModel.playSnippet() },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            if (state.isSnippetPlaying) SynaraIcons.Pause.get() else SynaraIcons.Play.get(),
                            contentDescription = stringResource(Res.string.song_guess_play_snippet)
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(
                                Res.string.song_guess_snippet_length,
                                formatSeconds(SNIPPET_LADDER_MS[state.attempt])
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                        LinearProgressIndicator(
                            progress = { state.snippetProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            SettingsCard {
                InternalTextField(
                    value = state.guessQuery,
                    onValueChange = { screenModel.updateGuess(it) },
                    placeholder = { Text(stringResource(Res.string.song_guess_guess_hint)) },
                    leadingIcon = { Icon(SynaraIcons.Search.get(), contentDescription = null) },
                    isError = state.lastGuessWrong,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                state.suggestions.forEach { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { screenModel.selectGuess(song) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column {
                            Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                song.artists.joinArtists(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (state.lastGuessWrong) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.song_guess_wrong),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { screenModel.submitGuess() },
                        enabled = state.selectedGuess != null,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(Res.string.song_guess_submit)) }
                    OutlinedButton(onClick = { screenModel.skip() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.song_guess_skip))
                    }
                }
            }
        }
    }

    @Composable
    private fun SnippetLadder(attempt: Int) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            SNIPPET_LADDER_MS.forEachIndexed { index, ms ->
                val color = when {
                    index < attempt -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    index == attempt -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(color),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        formatSeconds(ms),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index <= attempt) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ reveal / finished

    @Composable
    private fun RevealSection(screenModel: SongGuessScreenModel, state: SongGuessScreenModel.SongGuessState) {
        val result = state.roundResults.lastOrNull() ?: return
        val isLast = state.roundIndex + 1 >= state.config.rounds
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScoreHeader(state)
            SettingsCard {
                Text(
                    text = if (result.solved) stringResource(Res.string.song_guess_correct)
                    else stringResource(Res.string.song_guess_failed),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (result.solved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SynaraImage(imageId = result.coverId, size = 96.dp, shape = MaterialTheme.shapes.medium)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(result.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(Res.string.song_guess_points, result.points),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(Res.string.song_guess_attempt_of, result.attemptsUsed, MAX_ATTEMPTS),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { screenModel.nextRound() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isLast) stringResource(Res.string.song_guess_finish) else stringResource(Res.string.song_guess_next))
                }
            }
        }
    }

    @Composable
    private fun FinishedSection(screenModel: SongGuessScreenModel, state: SongGuessScreenModel.SongGuessState) {
        val maxScore = state.roundResults.size * MAX_POINTS_PER_ROUND
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(SynaraIcons.Trophy.get(), contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.song_guess_finished), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = stringResource(Res.string.song_guess_final_score, state.score, maxScore),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    state.error?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (error == SongGuessScreenModel.ERROR_NOT_ENOUGH_SONGS)
                                stringResource(Res.string.song_guess_not_enough_songs) else error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            SettingsCard {
                state.roundResults.forEachIndexed { index, result ->
                    RoundResultRow(index + 1, result)
                    if (index < state.roundResults.lastIndex) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            Button(onClick = { screenModel.backToSetup() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.song_guess_play_again))
            }
        }
    }

    @Composable
    private fun RoundResultRow(number: Int, result: RoundResult) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$number.", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
            SynaraImage(imageId = result.coverId, size = 40.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(result.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(result.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = stringResource(Res.string.song_guess_points, result.points),
                style = MaterialTheme.typography.labelLarge,
                color = if (result.solved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }

    @Composable
    private fun ScoreHeader(state: SongGuessScreenModel.SongGuessState) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(Res.string.song_guess_round_of, state.roundIndex + 1, state.config.rounds),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "${stringResource(Res.string.song_guess_score)}: ${state.score}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    // ------------------------------------------------------------------ leaderboard

    @Composable
    private fun LeaderboardSection(
        entries: List<LeaderboardEntry>,
        state: SongGuessScreenModel.SongGuessState,
        selectedRounds: Int?,
        onSelectRounds: (Int?) -> Unit,
        onClear: () -> Unit
    ) {
        val filtered = entries.filter { selectedRounds == null || it.config.rounds == selectedRounds }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(SynaraIcons.Trophy.get(), contentDescription = null)
                    Text(stringResource(Res.string.song_guess_leaderboard), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(Res.string.song_guess_clear_leaderboard), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedRounds == null,
                    onClick = { onSelectRounds(null) },
                    label = { Text(stringResource(Res.string.song_guess_all_rounds)) }
                )
                ROUND_OPTIONS.forEach { rounds ->
                    FilterChip(
                        selected = selectedRounds == rounds,
                        onClick = { onSelectRounds(rounds) },
                        label = { Text(rounds.toString()) }
                    )
                }
            }
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(Res.string.song_guess_leaderboard_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SettingsCard {
                    filtered.forEachIndexed { index, entry ->
                        LeaderboardRow(index + 1, entry, state)
                        if (index < filtered.lastIndex) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun LeaderboardRow(rank: Int, entry: LeaderboardEntry, state: SongGuessScreenModel.SongGuessState) {
        val config = entry.config
        val unknown = stringResource(Res.string.song_guess_unknown)
        val sourceText = when (config.source) {
            PoolSource.ALL -> stringResource(Res.string.song_guess_source_all)
            PoolSource.LIKED -> stringResource(Res.string.song_guess_source_liked)
            PoolSource.PLAYLISTS -> config.playlistIds.joinToString(", ") { state.playlistNames[it] ?: unknown }
        }
        val artistText = config.artistIds.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { state.artistNames[it] ?: unknown }
        val startText = when (config.snippetStart) {
            SnippetStart.SONG_START -> stringResource(Res.string.song_guess_snippet_start_song)
            SnippetStart.RANDOM -> stringResource(Res.string.song_guess_snippet_start_random)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "#$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = listOfNotNull(sourceText, artistText).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${stringResource(Res.string.song_guess_rounds)}: ${config.rounds} · $startText · ${entry.playedAt.formatDateTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(Res.string.song_guess_final_score, entry.score, entry.maxScore),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }

    private fun formatSeconds(ms: Long): String {
        val seconds = ms / 1000.0
        return if (seconds == seconds.toLong().toDouble()) seconds.toLong().toString() else seconds.toString()
    }
}
