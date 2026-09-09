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
                        version = "3.4.5",
                        date = "2026-09-09",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**Version tags**: markers like Remix, Live, Acoustic or Remaster are split off song titles and shown as chips next to the title, with a setting for whether media controls, Discord, Last.fm and menus still get the full title. The metadata editor can edit the title and its tags, and the offline library is converted on first start.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.4.4",
                        date = "2026-09-07",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Fixed, "The offline library no longer locks up with a database connection timeout while downloading or browsing long song lists: local database work runs on a small dedicated thread pool and download badges come from one shared snapshot instead of one query per row."),
                            Change(ChangeType.Fixed, "Removing a downloaded song, album, artist or playlist now updates the download badges immediately and also removes leftover artist, genre and playlist links."),
                            Change(ChangeType.Fixed, "Downloading a playlist no longer stores each song twice in the offline playlist."),
                            Change(ChangeType.Improved, "**Recently played** on the home screen updates once per listen instead of once per song, album and artist."),
                            Change(ChangeType.Improved, "The volume slider grows wider while hovered when the player bar has room.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.4.3",
                        date = "2026-09-07",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Fixed, "Pausing clears the server's **now playing** state after a short grace period, matching the mobile apps; the paused position is still reported.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.4.2",
                        date = "2026-09-07",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Fixed, "Songs no longer start a moment late: the leading-silence offset is only used by **Guess the Song**."),
                            Change(ChangeType.Improved, "Seeking within the first seconds of a song is sample-accurate.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.4.1",
                        date = "2026-09-06",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Share handlers are identified by their **id** instead of their title."),
                            Change(ChangeType.Updated, "Synchronized with the latest server API.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.4.0",
                        date = "2026-09-05",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**Server-driven UI**: settings pages, home cards, library entries, song menu actions and detail-screen cards provided by the server and its plugins now render in the app."),
                            Change(ChangeType.New, "**Home cards** can be pinned, unpinned and reordered from the edit button on the dashboard."),
                            Change(ChangeType.New, "**Playback reporting**: play, pause, seek and a periodic heartbeat are reported to the server with a synchronized clock, so server features can follow your current position."),
                            Change(ChangeType.New, "**Multichannel playback**: 5.1 and 7.1 FLAC, WAV, AIFF and Opus songs now play in their native channel layout through OpenAL, with loudness compensation so they match stereo material; the visualizer follows all channels."),
                            Change(ChangeType.New, "**Listen backup** admin screen to configure, test and trigger the server's listen backup."),
                            Change(ChangeType.New, "Listening stats can show the **last** week, month or year and rank top lists by **time listened** instead of listen count."),
                            Change(ChangeType.New, "Links in recent releases open through the server's intake handlers, and URLs or text dropped onto the window are offered to the server's share handlers."),
                            Change(ChangeType.Improved, "Song info shows the **codec and channel layout**; audio details come from the server's new audio info and work for downloaded songs too."),
                            Change(ChangeType.Improved, "Connection state is visible: a banner when the server is unreachable or the session fell back to an unencrypted connection (with a retry), the reason on the login screen after a forced logout, and server API and UI schema versions in the server settings."),
                            Change(ChangeType.Fixed, "Opus playback honours the encoder's pre-skip and output gain."),
                            Change(ChangeType.Removed, "The built-in importer screen; importing is now served by the server through the library entry and intake handlers."),
                            Change(ChangeType.Updated, "Synchronized with the latest server API (**v5**), including the UI schema header sent on every request.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.3.6",
                        date = "2026-08-26",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**Guess the Song**: a running game is now saved after every round and attempt, so you can close the app (or leave the tab) and pick up exactly where you left off — same round, attempt, score and snippet position."),
                            Change(ChangeType.Improved, "**Guess the Song**: giving up or finishing a game clears the saved state; if the saved song is no longer in your library the game returns to setup.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.3.5",
                        date = "2026-08-25",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**Guess the Song**: the revealed song now plays on the result screen, with a play/pause button and a seekbar over the whole track."),
                            Change(ChangeType.Improved, "**Guess the Song**: a guess is accepted when title and artist match, even if your library contains the song more than once (e.g. remastered or single versions).")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.3.4",
                        date = "2026-08-25",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**Guess the Song**: a new Songless-style game tab. Listen to a snippet starting at 0.1 s and guess the song — every wrong guess or skip unlocks a longer snippet. Choose the pool (all songs, liked songs or one or more playlists), filter by artists, pick 5–20 rounds and whether snippets start at the song start or a random position. Scores are kept in a local leaderboard."),
                            Change(ChangeType.Improved, "Game playback uses its own audio player, so it never touches your queue and is never scrobbled.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.3.3",
                        date = "2026-08-25",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Fixed, "Server, **Last.fm** and **ListenBrainz** scrobbles of the same listen now share one identical timestamp — the moment the song started playing — instead of each service stamping its own submission time.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.3.2",
                        date = "2026-08-25",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Fixed, "Server scrobbles are now only submitted — and counted in your listening stats — when a song was played for at least **3 seconds**, so quick skips no longer show up as listens."),
                            Change(ChangeType.New, "Listening stats now show your total **listening time** for the selected period (with a comparison to the previous period), plus the time spent on each top song, artist and album."),
                            Change(ChangeType.Updated, "Synchronized application services with the latest server API definitions.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.3.1",
                        date = "2026-08-24",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**WAV and AIFF** songs now stream and play in their original format instead of relying on a server-side FLAC conversion — including AIFF-C variants (sowt, in24, in32, fl32) and 32-bit/float WAV."),
                            Change(ChangeType.Improved, "The app now tells the server which API version it supports (**v2**) on every connection, enabling format-aware streaming."),
                            Change(ChangeType.Improved, "Unplayable songs now log a clear message instead of failing silently.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.3.0",
                        date = "2026-08-24",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "**Radio**: endless stations seeded from your listen history or pure randomness, plus admin-curated **radio channels** with full channel editing."),
                            Change(ChangeType.New, "**Collections**: group songs, albums, artists and playlists into server-side collections with in-collection search, custom covers and context-menu shortcuts."),
                            Change(ChangeType.New, "**Listening Stats**: listen counts with period comparison, top songs/artists/albums, listen clock, streaks, discoveries and unmatched-track linking."),
                            Change(ChangeType.New, "**Server scrobbling** with accurate played duration, offline queueing and a settings toggle."),
                            Change(ChangeType.New, "**Lyrics search** mode on the search screen."),
                            Change(ChangeType.New, "**User management** for admins: create users and assign capabilities."),
                            Change(ChangeType.New, "**API keys** with scopes and **Subsonic credentials** in settings."),
                            Change(ChangeType.New, "Custom cover upload for **user playlists**."),
                            Change(ChangeType.Improved, "Connection setup now supports a **custom server path** and an explicit **SSL toggle**, and the server can be changed later from settings."),
                            Change(ChangeType.Improved, "Capability-based UI gating: import, edit, delete and admin features are hidden when the account lacks the permission.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.2.0",
                        date = "2026-06-18",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Animated cover art is now served directly from the **server's own animated image library**, replacing the previous external metadata fetch."),
                            Change(ChangeType.Improved, "The player background now shows the animated cover's **first-frame still image** instantly while the video loads, then fades the animation in smoothly."),
                            Change(ChangeType.Improved, "Player color scheme is now derived from the animated cover's **first frame** instead of the static album art, for more accurate color extraction."),
                            Change(ChangeType.New, "**Animated Covers** count is now displayed on the server dashboard.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.1.0",
                        date = "2026-06-09",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Implemented **Artist Playlists**, allowing you to generate smart playlists from your favorite artists."),
                            Change(ChangeType.New, "Enhanced metadata discovery with support for **ISRC and Barcode** searching."),
                            Change(ChangeType.Fixed, "Resolved a stability issue when creating new playlists.")
                        )
                    ),
                    ChangelogEntry(
                        version = "3.0.0",
                        date = "2026-05-22",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Enhanced music matching accuracy with support for direct MusicBrainz ID lookups."),
                            Change(ChangeType.Improved, "Internal service optimizations and preparation for upcoming discovery features."),
                            Change(ChangeType.Updated, "Synchronized application services with the latest server API definitions.")
                        )
                    ),
                    ChangelogEntry(
                        version = "2.1.2",
                        date = "2026-05-13",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Refactored, "Rebranded all server-side download components to **Importer** to better reflect their role in the library ecosystem."),
                            Change(ChangeType.Improved, "Enhanced security by enforcing specific capabilities (`EDIT`, `DELETE`) for administrative actions like metadata editing and content removal."),
                            Change(ChangeType.Updated, "Updated internal RPC services and localized strings to align with the new importer terminology.")
                        )
                    ),
                    ChangelogEntry(
                        version = "2.1.1",
                        date = "2026-04-27",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Fixed, "Resolved an issue where the color scheme for animated covers would update with a slight delay after a song change."),
                            Change(ChangeType.Improved, "Overhauled **Artist Management** tools with advanced splitting, merging, and group management."),
                            Change(ChangeType.New, "Implemented **Artist Group Members** view to easily browse solo projects and collaborations within a group."),
                            Change(ChangeType.Improved, "Refined UI scrolling experience with animated top/bottom fades and improved dialog layouts."),
                            Change(ChangeType.Improved, "Added full localization for artist management tools and system notifications."),
                            Change(ChangeType.Removed, "Cleaned up legacy data migration logic that is no longer required for modern versions.")
                        )
                    ),
                    ChangelogEntry(
                        version = "2.1.0",
                        date = "2026-04-26",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Discover similar music based on **BPM, Energy, Mood**, or creators from songs, albums, and playlists."),
                            Change(ChangeType.New, "Support for high-quality **Opus and Ogg** audio streaming."),
                            Change(ChangeType.Improved, "Enhanced context menus with direct access to similarity-based discovery."),
                        )
                    ),
                    ChangelogEntry(
                        version = "2.0.0",
                        date = "2026-04-24",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Universal Downloader: Support for multiple sources including **YouTube**."),
                            Change(ChangeType.New, "Toggle remaining playback time by clicking on the duration in the player bar."),
                            Change(ChangeType.Improved, "Enhanced MusicBrainz integration with remote searching for more accurate metadata."),
                            Change(ChangeType.Improved, "Streamlined downloader UI and authorized source management."),
                            Change(ChangeType.Refactored, "Migrated internal Tidal services to a more flexible external source architecture.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.6.2",
                        date = "2026-04-20",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Added proper identification to MusicBrainz and ListenBrainz services."),
                            Change(ChangeType.Improved, "Enhanced error reporting for scrobbling failures.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.6.1",
                        date = "2026-04-18",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Fixed, "Fixed an issue that prevented the app from connecting to the server."),
                            Change(ChangeType.Improved, "Improved overall connection stability and reliability.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.6.0",
                        date = "2026-04-17",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Introduced a **smart data deduplication** system that significantly reduces network usage by eliminating redundant information in server responses."),
                            Change(ChangeType.Improved, "Enhanced server communication with a specialized **pack header** to optimize data transfer performance."),
                        )
                    ),
                    ChangelogEntry(
                        version = "1.5.0",
                        date = "2026-04-17",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Full support for the **Raycast** extension, allowing you to search music, manage the queue, and control playback directly from your launcher."),
                            Change(ChangeType.New, "Added a local web API, enabling other applications and tools to interact with Synara."),
                            Change(ChangeType.Improved, "Enhanced stability and reliability for platform-specific services.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.4.0",
                        date = "2026-04-17",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Support for the **Vicinae** launcher extension to search and control your music library."),
                            Change(ChangeType.New, "Integration with \"Now Playing\" status, real-time lyrics, and queue management."),
                            Change(ChangeType.New, "Expanded D-Bus API for deeper interaction with external tools.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.3.0",
                        date = "2026-04-15",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Support for animated track covers from Tidal."),
                            Change(ChangeType.New, "Dynamic UI colors that sync with the current video frame colors."),
                            Change(ChangeType.Improved, "Smoother video transitions and improved playback performance.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.2.2",
                        date = "2026-04-15",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Advanced audio configuration settings for buffer size, count, and target sample rate."),
                            Change(ChangeType.Improved, "Enhanced audio engine initialization and performance tuning.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.2.1",
                        date = "2026-04-15",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "More reliable album cover display in Discord Rich Presence."),
                            Change(ChangeType.Improved, "Internal performance optimizations for metadata fetching and service communication.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.2.0",
                        date = "2026-04-14",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Significantly smoother visual effects with a new high-performance GPU particle renderer."),
                            Change(ChangeType.Improved, "Particles now use a refined hexagon-based design to significantly boost rendering performance."),
                            Change(ChangeType.Improved, "Automatic frame rate matching for your monitor to ensure a fluid experience."),
                            Change(ChangeType.New, "Real-time performance metrics for particles available in the performance overlay.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.1.0",
                        date = "2026-04-14",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Significantly smoother and more fluid background glow and particle effects."),
                            Change(ChangeType.Improved, "Enhanced visualizer responsiveness with faster peak detection and natural decay."),
                            Change(ChangeType.Improved, "Tighter synchronization between audio and visuals through high-frequency frequency analysis."),
                            Change(ChangeType.Improved, "Lower latency audio playback for a more responsive experience.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.0.3",
                        date = "2026-04-13",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Enhanced the core connection and stability between the application and the music service."),
                            Change(ChangeType.Improved, "Added default paging to all lists (albums, artists, songs, playlists), making browsing smoother."),
                            Change(ChangeType.Fixed, "Resolved an issue where album art was not correctly displayed in the Linux media controller."),
                            Change(ChangeType.Improved, "Internal stability improvements and better error handling.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.0.2",
                        date = "2026-04-09",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Added support for manually linking albums to their MusicBrainz metadata.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.0.1",
                        date = "2026-04-09",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.New, "Added a **Scheduled Task Logs** screen to monitor background tasks like metadata updates and downloads.")
                        )
                    ),
                    ChangelogEntry(
                        version = "1.0.0",
                        date = "2026-04-09",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Major metadata upgrade: switched to a more robust format for MusicBrainz IDs to ensure your library stays perfectly organized.")
                        )
                    ),
                    ChangelogEntry(
                        version = "0.7.3",
                        date = "2026-04-06",
                        isPrerelease = true,
                        changes = listOf(
                            Change(ChangeType.Improved, "Decoupled scrobbling from the offline library. History and queue items now use independent JSON payloads instead of foreign key references."),
                            Change(ChangeType.Refactored, "Updated `LibraryRepository` to support filtering for explicitly saved items, ensuring scrobbled-only songs don't appear in the offline collection."),
                            Change(ChangeType.Updated, "Database migration `V1_2` to transition scrobble-related tables to the new decoupled schema."),
                        )
                    ),
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
