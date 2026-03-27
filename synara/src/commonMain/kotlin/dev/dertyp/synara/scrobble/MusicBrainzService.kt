package dev.dertyp.synara.scrobble

import dev.dertyp.core.cleanTitle
import dev.dertyp.data.Album
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MusicBrainzService(
    globalJson: Json,
    private val logger: Logger
) {

    private val json = Json(globalJson) {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val mbBaseUrl = "https://musicbrainz.org/ws/2"

    suspend fun getRecording(id: String): MbRecording? {
        return try {
            val response: MbRecording = httpClient.get("$mbBaseUrl/recording/$id") {
                parameter("inc", "artist-credits+releases")
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }.body()

            logger.info(LogTag.MUSICBRAINZ, "Found recording for $id: ${response.title}")
            response
        } catch (e: Exception) {
            logger.error(LogTag.MUSICBRAINZ, "Error getting MusicBrainz recording for $id", e)
            null
        }
    }

    suspend fun searchRecordings(query: String, limit: Int = 20): List<MbRecording> {
        return try {
            val response: MbSearchResponse = httpClient.get("$mbBaseUrl/recording") {
                parameter("query", query)
                parameter("limit", limit)
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }.body()

            response.recordings ?: emptyList()
        } catch (e: Exception) {
            logger.error(LogTag.MUSICBRAINZ, "Error searching MusicBrainz for $query", e)
            emptyList()
        }
    }

    suspend fun searchMb(song: UserSong): MbRecording? {
        val queryParts = mutableListOf<String>()
        queryParts.add("recording:\"${song.title.cleanTitle()}\"")
        song.artists.forEach { queryParts.add("artist:\"${it.name}\"") }

        song.album?.name?.takeIf { it != song.title }?.let {
            queryParts.add("release:\"${it.cleanTitle()}\"")
        }

        song.album?.artists?.forEach { artist ->
            queryParts.add("artistname:\"${artist.name}\"")
        }

        val query = queryParts.joinToString(" AND ")

        return try {
            val response: MbSearchResponse = httpClient.get("$mbBaseUrl/recording") {
                parameter("query", query)
                parameter("limit", 1)
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }.body()

            logger.info(LogTag.MUSICBRAINZ, "Search result for $query: ${response.recordings?.firstOrNull()?.id}")
            response.recordings?.firstOrNull()
        } catch (e: Exception) {
            logger.error(LogTag.MUSICBRAINZ, "Error searching MusicBrainz for $query", e)
            null
        }
    }

    suspend fun searchAlbumMb(album: Album): MbRelease? {
        val queryParts = mutableListOf<String>()
        queryParts.add("release:\"${album.name.cleanTitle()}\"")
        album.artists.forEach { queryParts.add("artist:\"${it.name}\"") }

        val query = queryParts.joinToString(" AND ")

        return try {
            val response: MbReleaseSearchResponse = httpClient.get("$mbBaseUrl/release") {
                parameter("query", query)
                parameter("limit", 1)
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }.body()

            logger.info(LogTag.MUSICBRAINZ, "Search result for $query: ${response.releases?.firstOrNull()?.id}")
            response.releases?.firstOrNull()
        } catch (e: Exception) {
            logger.error(LogTag.MUSICBRAINZ, "Error searching MusicBrainz for $query", e)
            null
        }
    }

    @Serializable
    data class MbSearchResponse(
        val recordings: List<MbRecording>? = null
    )

    @Serializable
    data class MbReleaseSearchResponse(
        val releases: List<MbRelease>? = null
    )

    @Serializable
    data class MbRecording(
        val id: String,
        val title: String? = null,
        @SerialName("artist-credit") val artistCredit: List<MbArtistCredit>? = null,
        val length: Long? = null,
        val releases: List<MbRelease>? = null,
    )

    @Serializable
    data class MbArtistCredit(
        val name: String? = null,
        val artist: MbArtist? = null,
        val joinphrase: String? = null
    )

    @Serializable
    data class MbArtist(
        val id: String,
        val name: String? = null
    )

    @Serializable
    data class MbRelease(
        val id: String,
        val title: String? = null
    )
}
