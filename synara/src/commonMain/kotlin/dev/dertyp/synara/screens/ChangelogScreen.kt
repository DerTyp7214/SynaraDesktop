package dev.dertyp.synara.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.BuildConfig
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.back
import synara.synara.generated.resources.changelog
import synara.synara.generated.resources.current

enum class ChangeType {
    New, Fixed, Improved, Removed, Updated, Refactored
}

data class Change(
    val type: ChangeType,
    val description: String
)

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<Change>,
    val isPrerelease: Boolean = false,
    val prereleasePrefix: String? = null
)

class ChangelogScreen : Screen {

    override val key: ScreenKey = uniqueScreenKey

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val isAppPrerelease = BuildConfig.PRERELEASE
        val currentVersionName = BuildConfig.VERSION

        val rawChangelog = remember {
            listOf(
                "1.0.0" to listOf(
                    ChangelogEntry(
                        version = "0.7.2",
                        date = "2026-04-05",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Enhanced `VisualizerView` with a dynamic glow effect for a more immersive playback experience."),
                        )
                    ),
                    ChangelogEntry(
                        version = "0.7.1",
                        date = "2026-04-04",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Automated script for checking dependency updates (`check_updates.py`)."),
                            Change(ChangeType.Updated, "Numerous core dependencies including **Compose**, **Material3**, **SQLDelight**, **Exposed**, and **Flyway**."),
                        )
                    ),
                    ChangelogEntry(
                        version = "0.7.0",
                        date = "2026-04-03",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**Download feature (Alpha)**: Initial support for downloading songs, albums, artists, and playlists for offline playback. **(Note: This feature is currently buggy and in alpha)**."),
                            Change(ChangeType.Improved, "Database performance and stability with **HikariCP** connection pooling and **Exposed** ORM."),
                        )
                    ),
                    ChangelogEntry(
                        version = "0.6.1",
                        date = "2026-04-02",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Added an indicator for new releases in the Recent Releases view.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.6.0",
                        date = "2026-04-02",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Recent releases view for discovering new music from followed artists."),
                            Change(ChangeType.New, "Tidal download screen with URL support and favorite synchronization."),
                            Change(ChangeType.New, "Dedicated `ReleaseService` for artist following and release tracking."),
                            Change(ChangeType.New, "Genre metadata support and display for songs, albums, and artists."),
                            Change(ChangeType.Improved, "MusicBrainz indicators in context menus for enhanced metadata visibility."),
                            Change(ChangeType.Updated, "German and English localizations with new strings for Tidal and metadata features."),
                            Change(ChangeType.Improved, "Music metadata formatting and UI consistency across screens."),
                            Change(ChangeType.Updated, "`common-rpc` submodule with extended service definitions and models.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.5.1",
                        date = "2026-04-01",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Fixed, "CPU load calculation in `TaskManagerScreen` and `PerformanceOverlay`."),
                        )
                    ),
                    ChangelogEntry(
                        version = "0.5.0",
                        date = "2026-04-01",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Audio output device selection in the settings screen."),
                            Change(ChangeType.New, "Support for switching audio output devices at runtime (JVM)."),
                            Change(ChangeType.Improved, "`ArtistService` and `AlbumService` with MusicBrainz metadata resolution and update support."),
                            Change(ChangeType.New, "Dedicated RPC flows for identifying artists and albums without MusicBrainz IDs."),
                            Change(ChangeType.Updated, "`common-rpc` submodule to the latest version.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.4.0",
                        date = "2026-03-27",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "`MetadataEditScreen` for editing song artists, lyrics, and MusicBrainz IDs."),
                            Change(ChangeType.Refactored, "`PlayerBar` into modular sub-components for improved maintainability."),
                            Change(ChangeType.New, "Detailed scrobble status dialog with per-provider tracking."),
                            Change(ChangeType.New, "Menu icon to the `ArtistScreen`, `AlbumScreen`, and `PlaylistScreen` top bars for quick access to actions."),
                            Change(ChangeType.Improved, "Scrobble indicator with overall state visualization and official brand logos."),
                            Change(ChangeType.Improved, "All text inputs to use the `InternalTextField` component with custom themes."),
                            Change(ChangeType.Improved, "Icon system with support for static brand assets and status indicators."),
                            Change(ChangeType.New, "MusicBrainz logo display next to song quality information in the player bar.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.8",
                        date = "2026-03-27",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "`setGroup` for artist group management."),
                            Change(ChangeType.New, "`setArtists` for updating song-artist associations."),
                            Change(ChangeType.Updated, "`common-rpc` submodule to the latest version.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.7",
                        date = "2026-03-26",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Updated, "`common-rpc` submodule."),
                            Change(ChangeType.New, "Kover for code coverage analysis."),
                            Change(ChangeType.New, "Test infrastructure with **JUnit 5**, **MockK**, **Turbine**, and **Koin Test**.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.6",
                        date = "2026-03-21",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "`getAllUsers` endpoint in `UserServiceWrapper`."),
                            Change(ChangeType.Updated, "`common-rpc` submodule to include latest service definitions.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.5",
                        date = "2026-03-19",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Native system tray support for Windows and macOS."),
                            Change(ChangeType.Refactored, "Linux system tray implementation into its own class."),
                            Change(ChangeType.New, "Factory for platform-specific system tray creation."),
                            Change(ChangeType.Refactored, "`OSUtils` to the tray module for better architectural separation."),
                            Change(ChangeType.Updated, "`common-rpc` submodule to the latest version.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.4",
                        date = "2026-03-18",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "`SongServiceWrapper` to support configurable `chunkSize` for song streaming and downloads."),
                            Change(ChangeType.Updated, "`common-rpc` submodule to the latest version.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.3",
                        date = "2026-03-17",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Fixed, "LastFm request encoding.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.2",
                        date = "2026-03-17",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "`SongServiceWrapper` with `downloadSong` and `getDownloadSize` to support music downloads with quality selection.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.1",
                        date = "2026-03-16",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Automatic persistence of resolved MusicBrainz IDs to the database during scrobbling."),
                            Change(ChangeType.Updated, "JVM distribution modules to include `java.management` and related components for the Task Manager."),
                            Change(ChangeType.Improved, "GitHub Actions release workflows to use standard authentication tokens and secrets."),
                            Change(ChangeType.Improved, "`TaskManagerScreen` resource monitoring stability and internal UI refinements."),
                            Change(ChangeType.Improved, "`SongServiceWrapper` with methods for MusicBrainz ID retrieval and persistence.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.0",
                        date = "2026-03-16",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "`HAS_MUSICBRAINZ_ID` tag support and automated MusicBrainz ID resolution."),
                            Change(ChangeType.New, "Task Manager and Performance Monitor for real-time tracking of application resource usage."),
                            Change(ChangeType.Improved, "`ListenBrainzScrobbler` with caching and automatic metadata resolution via MusicBrainz."),
                            Change(ChangeType.Improved, "`DetachedWindow` and `SynaraView` for more robust and stable multi-window operations."),
                            Change(ChangeType.Improved, "Theme management and UI polish across several core screens."),
                            Change(ChangeType.Refactored, "Multiple screen models to provide a more stable and consistent state management."),
                            Change(ChangeType.New, "Support for **Lucide**, **Material**, and **Phosphor** icon packs with improved icon mapping generation.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.2.1",
                        date = "2026-03-15",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Automatic changelog display on application updates."),
                            Change(ChangeType.New, "`LastSeenVersion` tracking to ensure the changelog only appears once per update."),
                            Change(ChangeType.Refactored, "`SynaraImage` to use centralized `onClick` handlers."),
                            Change(ChangeType.Improved, "`SnackbarManager` to dismiss active snackbars before showing new ones."),
                            Change(ChangeType.Improved, "Settings menu with direct access to the application changelog."),
                            Change(ChangeType.New, "`BuildConfig` with a `PRERELEASE` flag for version-specific feature management.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.2.0",
                        date = "2026-03-15",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**Phosphor** icon pack support and updated the icon processor."),
                            Change(ChangeType.New, "KSP-based icon mapping generation for automated icon library handling."),
                            Change(ChangeType.New, "Flexible icon pack system supporting **Material Symbols**, **Lucide**, and **Phosphor**."),
                            Change(ChangeType.New, "Icon styling system and support for song tag filtering.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.1.0",
                        date = "2026-03-10",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**Haze** integration for glass blur effects across the UI."),
                            Change(ChangeType.New, "Artist merge and split functionality for better library management."),
                            Change(ChangeType.New, "Multi-user database support and home screen UI enhancements."),
                            Change(ChangeType.Improved, "macOS system media integration and playback state handling."),
                            Change(ChangeType.New, "Logout functionality and icon generation robustness improvements."),
                            Change(ChangeType.New, "Proxy support and refactored settings UI."),
                            Change(ChangeType.New, "Cross-platform system media controls."),
                            Change(ChangeType.New, "Token expiration display and RPC authentication handling improvements."),
                            Change(ChangeType.New, "Song context menu to the PlayerBar."),
                            Change(ChangeType.Improved, "`ParticleView` performance for a smoother visual experience."),
                            Change(ChangeType.Refactored, "RPC initialization and streamlined playlist management."),
                            Change(ChangeType.New, "Playlist creation and \"add to playlist\" functionality.")
                        )
                    ),
                ),
            ).flatMap { (prereleasePrefix, entries) ->
                entries.map {
                    it.copy(
                        prereleasePrefix = prereleasePrefix
                    )
                }
            }
        }

        val displayChangelog = remember(isAppPrerelease) {
            if (isAppPrerelease) {
                rawChangelog.map { entry ->
                    if (entry.isPrerelease && entry.prereleasePrefix != null) {
                        entry.copy(version = "${entry.prereleasePrefix}-prerelease${entry.version}")
                    } else entry
                }
            } else {
                rawChangelog.filter { !it.isPrerelease }
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(Res.string.changelog),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = SynaraIcons.Back.get(),
                                contentDescription = stringResource(Res.string.back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 580.dp)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(displayChangelog) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    MaterialTheme.shapes.medium
                                )
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "v${entry.version}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (entry.isPrerelease) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                )
                                if (entry.version == currentVersionName) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.current),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(
                                                horizontal = 6.dp,
                                                vertical = 2.dp
                                            ),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = entry.date,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            val typeOrder = listOf(
                                ChangeType.New,
                                ChangeType.Improved,
                                ChangeType.Fixed,
                                ChangeType.Updated,
                                ChangeType.Refactored,
                                ChangeType.Removed
                            )

                            entry.changes
                                .groupBy { it.type }
                                .toSortedMap(compareBy { typeOrder.indexOf(it) })
                                .forEach { (type, changes) ->
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(
                                            text = type.name.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Black,
                                            color = when (type) {
                                                ChangeType.New -> MaterialTheme.colorScheme.primary
                                                ChangeType.Fixed -> MaterialTheme.colorScheme.error
                                                ChangeType.Improved -> MaterialTheme.colorScheme.tertiary
                                                ChangeType.Removed -> MaterialTheme.colorScheme.secondary
                                                ChangeType.Updated -> MaterialTheme.colorScheme.secondary
                                                ChangeType.Refactored -> MaterialTheme.colorScheme.outline
                                            },
                                            letterSpacing = 1.sp,
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        )

                                        changes.forEach { change ->
                                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                                Text(
                                                    text = "• ",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                        alpha = 0.5f
                                                    )
                                                )
                                                Text(
                                                    text = parseMarkdown(change.description),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var current = 0
        val regex = Regex("""(\*\*|__|`)(.*?)\1""")
        val matches = regex.findAll(text)

        for (match in matches) {
            append(text.substring(current, match.range.first))
            val delimiter = match.groupValues[1]
            val content = match.groupValues[2]

            val style = when (delimiter) {
                "**" -> SpanStyle(fontWeight = FontWeight.Bold)
                "__" -> SpanStyle(textDecoration = TextDecoration.Underline)
                "`" -> SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    color = MaterialTheme.colorScheme.primary
                )
                else -> SpanStyle()
            }

            withStyle(style) {
                append(content)
            }
            current = match.range.last + 1
        }
        append(text.substring(current))
    }
}
