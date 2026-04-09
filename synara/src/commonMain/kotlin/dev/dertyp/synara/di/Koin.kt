package dev.dertyp.synara.di

import com.russhwolf.settings.Settings
import dev.dertyp.getPlatformName
import dev.dertyp.logging.BaseLogger
import dev.dertyp.logging.Logger
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.*
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.tdn.IDownloadService
import dev.dertyp.synara.BuildConfig
import dev.dertyp.synara.logging.StdoutLogPersistence
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.*
import dev.dertyp.synara.scrobble.*
import dev.dertyp.synara.services.IDownloadManager
import dev.dertyp.synara.services.StubDownloadManager
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.ui.components.setupCoil
import dev.dertyp.synara.ui.models.PerformanceMonitor
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.ui.models.TrayState
import dev.dertyp.synara.utils.AppDispatchers
import dev.dertyp.synara.viewmodels.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.serialization.kotlinx.json.json
import kotlinx.rpc.krpc.ktor.client.Krpc
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds
import kotlinx.rpc.krpc.serialization.cbor.cbor as krpcCbor

@OptIn(ExperimentalSerializationApi::class)
private fun buildHttpClient(cbor: Cbor, json: Json): HttpClient {
    return HttpClient {
        install(UserAgent) {
            agent = "Synara/Synara Desktop (${BuildConfig.VERSION}; ${getPlatformName()})"
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 30000
        }
        install(WebSockets) {
            pingInterval = 15.seconds
            maxFrameSize = Long.MAX_VALUE
        }
        install(Krpc) {
            serialization {
                krpcCbor(cbor)
            }
        }
        install(ContentNegotiation) {
            json(json)
        }
    }
}

expect fun platformModule(): Module
expect fun platformInit()

@OptIn(ExperimentalSerializationApi::class)
val appModule = module {
    single<Json> { AppJson }
    single<Cbor> { AppCbor }
    singleOf(::buildHttpClient)

    singleOf(::SettingsFactory)
    single<Settings> { get<SettingsFactory>().create() }
    
    single { AppDispatchers }

    single<Logger> { BaseLogger(StdoutLogPersistence(), get()) }

    singleOf(::RpcServiceManager)
    singleOf(::GlobalStateModel)
    singleOf(::ScrobblerService)
    singleOf(::MusicBrainzService)
    singleOf(::ScrobbleQueue)
    singleOf(::SongCache)
    singleOf(::PlayerModel)
    singleOf(::TrayState)
    singleOf(::SnackbarManager)
    singleOf(::PerformanceMonitor)

    single<IDownloadManager> { StubDownloadManager() }

    factoryOf(::SetupScreenModel)
    factoryOf(::LoginScreenModel)
    factoryOf(::HomeScreenModel)
    factoryOf(::SearchScreenModel)
    factoryOf(::LikedSongsScreenModel)
    factoryOf(::AllSongsScreenModel)
    factoryOf(::SessionsScreenModel)

    factoryOf(::ArtistScreenModel)
    factoryOf(::ArtistSongsScreenModel)
    factoryOf(::ArtistAlbumsScreenModel)
    factoryOf(::ArtistLikedSongsScreenModel)
    factoryOf(::AlbumScreenModel)
    factoryOf(::PlaylistScreenModel)

    factoryOf(::SearchSongsViewModel)
    factoryOf(::SearchArtistsViewModel)
    factoryOf(::SearchAlbumsViewModel)
    factoryOf(::SearchPlaylistsViewModel)
    factoryOf(::TidalDownloadScreenModel)
    factoryOf(::DownloadsScreenModel)

    singleOf(::AlbumServiceWrapper) bind IAlbumService::class
    singleOf(::ArtistServiceWrapper) bind IArtistService::class
    singleOf(::AuthServiceWrapper) bind IAuthService::class
    singleOf(::CustomAudioServiceWrapper) bind ICustomAudioService::class
    singleOf(::DownloadServiceWrapper) bind IDownloadService::class
    singleOf(::FavSyncServiceWrapper) bind IFavSyncService::class
    singleOf(::ImageServiceWrapper) bind IImageService::class
    singleOf(::LyricsSearchWrapper) bind ILyricsSearch::class
    singleOf(::MusicBrainzServiceWrapper) bind IMusicBrainzService::class
    singleOf(::PlaybackServiceWrapper) bind IPlaybackService::class
    singleOf(::PlaylistServiceWrapper) bind IPlaylistService::class
    singleOf(::ReleaseServiceWrapper) bind IReleaseService::class
    singleOf(::ScheduledTaskLogServiceWrapper) bind IScheduledTaskLogService::class
    singleOf(::ServerStatsServiceWrapper) bind IServerStatsService::class
    singleOf(::SessionServiceWrapper) bind ISessionService::class
    singleOf(::SongServiceWrapper) bind ISongService::class
    singleOf(::StorageServiceWrapper) bind IStorageService::class
    singleOf(::SyncServiceWrapper) bind ISyncService::class
    singleOf(::UserPlaylistServiceWrapper) bind IUserPlaylistService::class
    singleOf(::UserServiceWrapper) bind IUserService::class

    singleOf(::LocalSongScrobbler)
    singleOf(::ListenBrainzScrobbler)
    singleOf(::LastFmScrobbler)
    singleOf(::DiscordScrobbler)
    singleOf(::RecentlyPlayedScrobbler)
}

fun initializeSynara() {
    setupCoil()
    initKoin()
    platformInit()
}

fun initKoin() {
    startKoin {
        allowOverride(true)
        modules(appModule, platformModule())
    }
}
