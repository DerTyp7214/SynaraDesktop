package dev.dertyp.synara.di

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.*
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.*
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.ui.components.setupCoil
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
            agent = "Synara/1.0.0"
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
    
    val settingsFactory = SettingsFactory()
    single<Settings> { settingsFactory.create() }
    singleOf(::RpcServiceManager)
    singleOf(::GlobalStateModel)
    single { PlayerModel(get(), get(), get(), settingsFactory.getStatePath("player_state.pb")) }

    factoryOf(::SetupScreenModel)
    factoryOf(::LoginScreenModel)
    factoryOf(::HomeScreenModel)

    factory { (albumId: PlatformUUID) -> AlbumScreenModel(albumId, get(), get(), get()) }
    factory { (playlistId: PlatformUUID, isUserPlaylist: Boolean) ->
        PlaylistScreenModel(
            playlistId,
            isUserPlaylist,
            get(),
            get(),
            get(),
            get()
        )
    }

    singleOf(::AlbumServiceWrapper) bind IAlbumService::class
    singleOf(::ArtistServiceWrapper) bind IArtistService::class
    singleOf(::AuthServiceWrapper) bind IAuthService::class
    singleOf(::CustomAudioServiceWrapper) bind ICustomAudioService::class
    singleOf(::FavSyncServiceWrapper) bind IFavSyncService::class
    singleOf(::ImageServiceWrapper) bind IImageService::class
    singleOf(::LyricsSearchWrapper) bind ILyricsSearch::class
    singleOf(::PlaybackServiceWrapper) bind IPlaybackService::class
    singleOf(::PlaylistServiceWrapper) bind IPlaylistService::class
    singleOf(::ServerStatsServiceWrapper) bind IServerStatsService::class
    singleOf(::SessionServiceWrapper) bind ISessionService::class
    singleOf(::SongServiceWrapper) bind ISongService::class
    singleOf(::StorageServiceWrapper) bind IStorageService::class
    singleOf(::SyncServiceWrapper) bind ISyncService::class
    singleOf(::UserPlaylistServiceWrapper) bind IUserPlaylistService::class
    singleOf(::UserServiceWrapper) bind IUserService::class
}

fun initializeSynara() {
    platformInit()
    setupCoil()
    initKoin()
}

fun initKoin() {
    startKoin {
        modules(appModule, platformModule())
    }
}
