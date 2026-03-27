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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>,
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
                        version = "0.3.8",
                        date = "2026-03-27",
                        isPrerelease = true,
                        changes = listOf(
                            "Implemented `setGroup` for artist group management.",
                            "Implemented `setArtists` for updating song-artist associations.",
                            "Updated `common-rpc` submodule to the latest version."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.7",
                        date = "2026-03-26",
                        isPrerelease = true,
                        changes = listOf(
                            "Updated `common-rpc` submodule.",
                            "Added Kover for code coverage analysis.",
                            "Expanded test infrastructure with JUnit 5, MockK, Turbine, and Koin Test."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.6",
                        date = "2026-03-21",
                        isPrerelease = true,
                        changes = listOf(
                            "Implemented `getAllUsers` endpoint in `UserServiceWrapper`.",
                            "Updated `common-rpc` submodule to include latest service definitions."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.5",
                        date = "2026-03-19",
                        isPrerelease = true,
                        changes = listOf(
                            "Implemented native system tray support for Windows and macOS.",
                            "Refactored Linux system tray implementation into its own class.",
                            "Introduced a factory for platform-specific system tray creation.",
                            "Moved `OSUtils` to the `tray` module for better architectural separation.",
                            "Updated `common-rpc` submodule to the latest version."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.4",
                        date = "2026-03-18",
                        isPrerelease = true,
                        changes = listOf(
                            "Updated `SongServiceWrapper` to support configurable `chunkSize` for song streaming and downloads.",
                            "Updated `common-rpc` submodule to the latest version."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.3",
                        date = "2026-03-17",
                        isPrerelease = true,
                        changes = listOf(
                            "Fix lastFm request encoding."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.2",
                        date = "2026-03-17",
                        isPrerelease = true,
                        changes = listOf(
                            "Enhanced `SongServiceWrapper` with `downloadSong` and `getDownloadSize` to support music downloads with quality selection."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.1",
                        date = "2026-03-16",
                        isPrerelease = true,
                        changes = listOf(
                            "Automatically persist resolved MusicBrainz IDs to the database during scrobbling for better metadata consistency.",
                            "Updated JVM distribution modules to include `java.management` and related components for the Task Manager.",
                            "Refined GitHub Actions release workflows to use standard authentication tokens and secrets.",
                            "Improved `TaskManagerScreen` resource monitoring stability and internal UI refinements.",
                            "Enhanced `SongServiceWrapper` with methods for MusicBrainz ID retrieval and persistence."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.3.0",
                        date = "2026-03-16",
                        isPrerelease = true,
                        changes = listOf(
                            "Added `HAS_MUSICBRAINZ_ID` tag support and automated MusicBrainz ID resolution for better metadata enrichment.",
                            "Implemented a new Task Manager and Performance Monitor for real-time tracking of application resource usage.",
                            "Optimized `ListenBrainzScrobbler` with caching and automatic metadata resolution via MusicBrainz.",
                            "Improved `DetachedWindow` and `SynaraView` for more robust and stable multi-window operations.",
                            "Enhanced theme management and applied UI polish across several core screens for a better visual experience.",
                            "Refactored multiple screen models to provide a more stable and consistent state management.",
                            "Expanded support for Lucide, Material, and Phosphor icon packs with improved icon mapping generation."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.2.1",
                        date = "2026-03-15",
                        isPrerelease = true,
                        changes = listOf(
                            "Implemented automatic changelog display on application updates.",
                            "Added `LastSeenVersion` tracking to ensure the changelog only appears once per update.",
                            "Refactored `SynaraImage` to use centralized `onClick` handlers for better consistency and cleaner code.",
                            "Optimized `SnackbarManager` to dismiss active snackbars before showing new ones, preventing notification stacking.",
                            "Improved settings menu with direct access to the application changelog.",
                            "Enhanced `BuildConfig` with a `PRERELEASE` flag for version-specific feature management."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.2.0",
                        date = "2026-03-15",
                        isPrerelease = true,
                        changes = listOf(
                            "Added Phosphor icon pack support and updated the icon processor.",
                            "Implemented KSP-based icon mapping generation for automated icon library handling.",
                            "Introduced a flexible icon pack system supporting Material Symbols, Lucide, and Phosphor.",
                            "Implemented icon styling system and added support for song tag filtering."
                        )
                    ),
                    ChangelogEntry(
                        version = "0.1.0",
                        date = "2026-03-10",
                        isPrerelease = true,
                        changes = listOf(
                            "Integrated Haze for glass blur effects across the UI.",
                            "Added artist merge and split functionality for better library management.",
                            "Implemented multi-user database support and enhanced home screen UI.",
                            "Improved macOS system media integration and playback state handling.",
                            "Added logout functionality and improved icon generation robustness.",
                            "Introduced proxy support and refactored the settings UI.",
                            "Implemented cross-platform system media controls.",
                            "Added token expiration display and improved RPC authentication handling.",
                            "Added song context menu to the PlayerBar.",
                            "Optimized ParticleView performance for a smoother visual experience.",
                            "Refactored RPC initialization and streamlined playlist management.",
                            "Implemented playlist creation and \"add to playlist\" functionality."
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

                            Spacer(modifier = Modifier.height(8.dp))

                            entry.changes.forEach { change ->
                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(
                                        text = "• ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (entry.isPrerelease) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = change,
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
