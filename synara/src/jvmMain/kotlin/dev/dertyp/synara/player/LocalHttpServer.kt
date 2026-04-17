package dev.dertyp.synara.player

import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.BuildConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.freedesktop.dbus.types.Variant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.BindException

class LocalHttpServer(
    private val api: ISynaraApi
) : KoinComponent {
    private val logger by inject<Logger>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        logger.info(LogTag("HTTP"), "Starting local HTTP server...")
        scope.launch {
            val ports = listOf(10767, 10768, 10769)
            for (port in ports) {
                try {
                    val engine = embeddedServer(CIO, port = port) {
                        intercept(ApplicationCallPipeline.Plugins) {
                            logger.info(LogTag("HTTP"), "Incoming request: ${call.request.httpMethod.value} ${call.request.uri}")
                        }
                        install(ContentNegotiation) {
                            json(Json {
                                ignoreUnknownKeys = true
                                encodeDefaults = true
                            })
                        }
                        install(CORS) {
                            allowHost("localhost")
                            allowHost("127.0.0.1")
                            anyMethod()
                            allowHeader(HttpHeaders.ContentType)
                        }

                        routing {
                            get("/ping") {
                                call.respond(mapOf("app" to "synara", "version" to BuildConfig.VERSION))
                            }

                            route("/api") {
                                get("/info") {
                                    call.respondText(api.GetServerInfo(), ContentType.Application.Json)
                                }
                                get("/playback") {
                                    call.respondText(api.GetPlaybackState(), ContentType.Application.Json)
                                }
                                get("/now-playing") {
                                    val song = api.GetCurrentSong()
                                    if (song.isEmpty()) call.respond(HttpStatusCode.NoContent)
                                    else call.respondText(song, ContentType.Application.Json)
                                }
                                get("/search") {
                                    val query = call.request.queryParameters["q"] ?: ""
                                    val includeSongs = call.request.queryParameters["songs"]?.toBoolean() ?: true
                                    val includeAlbums = call.request.queryParameters["albums"]?.toBoolean() ?: true
                                    val includeArtists = call.request.queryParameters["artists"]?.toBoolean() ?: true
                                    val includePlaylists = call.request.queryParameters["playlists"]?.toBoolean() ?: true

                                    val options = mutableMapOf<SearchFilter, Variant<*>>()
                                    options[SearchFilter.includeSongs] = Variant(includeSongs)
                                    options[SearchFilter.includeAlbums] = Variant(includeAlbums)
                                    options[SearchFilter.includeArtists] = Variant(includeArtists)
                                    options[SearchFilter.includePlaylists] = Variant(includePlaylists)

                                    call.respondText(api.Search(query, options), ContentType.Application.Json)
                                }
                                get("/lyrics") {
                                    call.respond(mapOf("lyrics" to api.GetCurrentLyrics()))
                                }
                                get("/queue") {
                                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                                    call.respondText(api.GetQueue(limit), ContentType.Application.Json)
                                }

                                post("/action/{action}") {
                                    val action = call.parameters["action"]
                                    val id = call.request.queryParameters["id"]
                                    val type = call.request.queryParameters["type"] ?: "song"

                                    when (action) {
                                        "play" -> {
                                            if (id != null) {
                                                when (type) {
                                                    "song" -> api.PlaySong(id)
                                                    "album" -> api.PlayAlbum(id)
                                                    "artist" -> api.PlayArtist(id)
                                                    "playlist" -> api.PlayPlaylist(id)
                                                }
                                                call.respond(HttpStatusCode.OK)
                                            } else call.respond(HttpStatusCode.BadRequest)
                                        }
                                        "play-next" -> {
                                            if (id != null) {
                                                api.PlayNext(id, type)
                                                call.respond(HttpStatusCode.OK)
                                            } else call.respond(HttpStatusCode.BadRequest)
                                        }
                                        "add-to-queue" -> {
                                            if (id != null) {
                                                api.AddToQueue(id, type)
                                                call.respond(HttpStatusCode.OK)
                                            } else call.respond(HttpStatusCode.BadRequest)
                                        }
                                        "play-queue-item" -> {
                                            val queueId = call.request.queryParameters["queueId"]
                                            if (queueId != null) {
                                                api.PlayQueueItem(queueId)
                                                call.respond(HttpStatusCode.OK)
                                            } else call.respond(HttpStatusCode.BadRequest)
                                        }
                                        else -> call.respond(HttpStatusCode.NotFound)
                                    }
                                }
                            }
                        }
                    }
                    server = engine
                    engine.start(wait = false)
                    logger.info(LogTag("HTTP"), "Synara local API server started at http://localhost:$port")
                    break
                } catch (_: BindException) {
                    logger.warning(LogTag("HTTP"), "Port $port is already in use, trying next...")
                } catch (e: Exception) {
                    logger.error(LogTag("HTTP"), "Failed to start local API server: ${e.message}")
                    break
                }
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
    }
}
