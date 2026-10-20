package dev.dertyp.synara.services

import dev.dertyp.core.cleanTitle
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.synara.scrobble.MusicBrainzService
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException

class ShareLinkBuilder(
    private val musicBrainzService: IMusicBrainzService,
    private val directMusicBrainzService: MusicBrainzService,
    private val logger: Logger
) {
    private val webShareHost = "synara.audio"
    private val webSharePath = "share"

    private val typeParam = "type"
    private val titleParam = "title"
    private val artistParam = "artist"
    private val mbidParam = "mbid"
    private val durationParam = "duration"
    private val isrcParam = "isrc"
    private val barcodeParam = "barcode"

    suspend fun songLink(song: UserSong): String {
        val urlBuilder = shareUrl("song").apply {
            appendParam(titleParam, song.title.cleanTitle())
            appendParam(durationParam, song.duration.toString())
            song.artists.forEach { appendParam(artistParam, it.name) }
            song.isrc?.takeIf { it.isNotEmpty() }?.let { appendParam(isrcParam, it) }
        }
        try {
            val recording = song.musicBrainzId?.let { musicBrainzService.getRecording(it) }
                ?: directMusicBrainzService.searchMb(song)
            if (recording != null) {
                urlBuilder.appendParam(mbidParam, recording.id.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(LogTag.MUSICBRAINZ, e.message ?: "", e)
        }
        return urlBuilder.buildString()
    }

    suspend fun albumLink(album: Album): String {
        val urlBuilder = shareUrl("album").apply {
            appendParam(titleParam, album.name.cleanTitle())
            album.artists.forEach { appendParam(artistParam, it.name) }
            album.barcode?.takeIf { it.isNotEmpty() }?.let { appendParam(barcodeParam, it) }
        }
        try {
            val release = album.musicBrainzId?.let { musicBrainzService.getRelease(it) }
                ?: directMusicBrainzService.searchAlbumMb(album)
            if (release != null) {
                urlBuilder.appendParam(mbidParam, release.id.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(LogTag.MUSICBRAINZ, e.message ?: "", e)
        }
        return urlBuilder.buildString()
    }

    fun artistLink(artist: Artist): String {
        val urlBuilder = shareUrl("artist").apply {
            appendParam(titleParam, artist.name.cleanTitle())
            artist.musicBrainzId?.let { appendParam(mbidParam, it.toString()) }
        }
        return urlBuilder.buildString()
    }

    private fun shareUrl(type: String) = URLBuilder().apply {
        protocol = URLProtocol("https", 443)
        host = webShareHost
        pathSegments = listOf(webSharePath)
        appendParam(typeParam, type)
    }

    private fun URLBuilder.appendParam(name: String, value: String) {
        encodedParameters.append(name, value.encodeURLParameter())
    }
}
