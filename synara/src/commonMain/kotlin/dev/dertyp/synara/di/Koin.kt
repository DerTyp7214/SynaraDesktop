package dev.dertyp.synara.di

import com.russhwolf.settings.Settings
import dev.dertyp.getPlatformName
import dev.dertyp.logging.BaseLogger
import dev.dertyp.logging.Logger
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.IAuthService
import dev.dertyp.services.ICustomAudioService
import dev.dertyp.services.IFavSyncService
import dev.dertyp.services.IImageService
import dev.dertyp.services.ILyricsSearch
import dev.dertyp.services.IPlaybackService
import dev.dertyp.services.IPlaylistService
import dev.dertyp.services.IReleaseService
import dev.dertyp.services.IScheduledTaskLogService
import dev.dertyp.services.IServerStatsService
import dev.dertyp.services.ISessionService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IStorageService
import dev.dertyp.services.ISyncService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.IUserService
import dev.dertyp.services.download.IDownloadService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.synara.BuildConfig
import dev.dertyp.synara.logging.StdoutLogPersistence
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.AlbumServiceWrapper
import dev.dertyp.synara.rpc.services.ArtistServiceWrapper
import dev.dertyp.synara.rpc.services.AuthServiceWrapper
import dev.dertyp.synara.rpc.services.CustomAudioServiceWrapper
import dev.dertyp.synara.rpc.services.DownloadServiceWrapper
import dev.dertyp.synara.rpc.services.FavSyncServiceWrapper
import dev.dertyp.synara.rpc.services.ImageServiceWrapper
import dev.dertyp.synara.rpc.services.LyricsSearchWrapper
import dev.dertyp.synara.rpc.services.MetadataServiceWrapper
import dev.dertyp.synara.rpc.services.MusicBrainzServiceWrapper
import dev.dertyp.synara.rpc.services.PlaybackServiceWrapper
import dev.dertyp.synara.rpc.services.PlaylistServiceWrapper
import dev.dertyp.synara.rpc.services.ReleaseServiceWrapper
import dev.dertyp.synara.rpc.services.ScheduledTaskLogServiceWrapper
import dev.dertyp.synara.rpc.services.ServerStatsServiceWrapper
import dev.dertyp.synara.rpc.services.SessionServiceWrapper
import dev.dertyp.synara.rpc.services.SongServiceWrapper
import dev.dertyp.synara.rpc.services.StorageServiceWrapper
import dev.dertyp.synara.rpc.services.SyncServiceWrapper
import dev.dertyp.synara.rpc.services.UserPlaylistServiceWrapper
import dev.dertyp.synara.rpc.services.UserServiceWrapper
import dev.dertyp.synara.scrobble.DiscordScrobbler
import dev.dertyp.synara.scrobble.LastFmScrobbler
import dev.dertyp.synara.scrobble.ListenBrainzScrobbler
import dev.dertyp.synara.scrobble.LocalSongScrobbler
import dev.dertyp.synara.scrobble.MusicBrainzService
import dev.dertyp.synara.scrobble.RecentlyPlayedScrobbler
import dev.dertyp.synara.scrobble.ScrobbleQueue
import dev.dertyp.synara.scrobble.ScrobblerService
import dev.dertyp.synara.services.IDownloadManager
import dev.dertyp.synara.services.StubDownloadManager
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.ui.components.setupCoil
import dev.dertyp.synara.ui.models.PerformanceMonitor
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.ui.models.TrayState
import dev.dertyp.synara.utils.AppDispatchers
import dev.dertyp.synara.viewmodels.AlbumScreenModel
import dev.dertyp.synara.viewmodels.AllSongsScreenModel
import dev.dertyp.synara.viewmodels.ArtistAlbumsScreenModel
import dev.dertyp.synara.viewmodels.ArtistLikedSongsScreenModel
import dev.dertyp.synara.viewmodels.ArtistScreenModel
import dev.dertyp.synara.viewmodels.ArtistSongsScreenModel
import dev.dertyp.synara.viewmodels.DownloaderScreenModel
import dev.dertyp.synara.viewmodels.DownloadsScreenModel
import dev.dertyp.synara.viewmodels.GlobalStateModel
import dev.dertyp.synara.viewmodels.HomeScreenModel
import dev.dertyp.synara.viewmodels.LikedSongsScreenModel
import dev.dertyp.synara.viewmodels.LoginScreenModel
import dev.dertyp.synara.viewmodels.PlaylistScreenModel
import dev.dertyp.synara.viewmodels.SearchAlbumsViewModel
import dev.dertyp.synara.viewmodels.SearchArtistsViewModel
import dev.dertyp.synara.viewmodels.SearchPlaylistsViewModel
import dev.dertyp.synara.viewmodels.SearchScreenModel
import dev.dertyp.synara.viewmodels.SearchSongsViewModel
import dev.dertyp.synara.viewmodels.SessionsScreenModel
import dev.dertyp.synara.viewmodels.SetupScreenModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.serialization.kotlinx.json.json
import kotlinx.rpc.krpc.ktor.client.Krpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
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
        }
        install(DefaultRequest) {
            //header(SynaraPackHeader, "true")
        }
        install(Krpc) {
            serialization {
                //synaraCbor(cbor)
                cbor(cbor)
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
    factoryOf(::DownloaderScreenModel)
    factoryOf(::DownloadsScreenModel)

    singleOf(::AlbumServiceWrapper) bind IAlbumService::class
    singleOf(::ArtistServiceWrapper) bind IArtistService::class
    singleOf(::AuthServiceWrapper) bind IAuthService::class
    singleOf(::CustomAudioServiceWrapper) bind ICustomAudioService::class
    singleOf(::DownloadServiceWrapper) bind IDownloadService::class
    singleOf(::FavSyncServiceWrapper) bind IFavSyncService::class
    singleOf(::ImageServiceWrapper) bind IImageService::class
    singleOf(::LyricsSearchWrapper) bind ILyricsSearch::class
    singleOf(::MetadataServiceWrapper) bind IMetadataService::class
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
