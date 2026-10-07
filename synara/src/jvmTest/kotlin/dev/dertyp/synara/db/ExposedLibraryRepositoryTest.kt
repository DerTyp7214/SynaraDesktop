package dev.dertyp.synara.db

import dev.dertyp.data.Album
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExposedLibraryRepositoryTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedLibraryRepository

    private val artist = ArtistCredit(randomPlatformUUID(), "Mac Miller", false)

    private val anniversaryTags = listOf(TitleTag(TitleTagKind.VERSION, "10th Anniversary"))

    private fun album(tags: List<TitleTag>) = Album(
        id = randomPlatformUUID(),
        name = "The Divine Feminine",
        artists = listOf(artist),
        songCount = 11,
        releaseDate = null,
        totalDuration = 1000,
        tags = tags
    )

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-library", ".db")
        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(
                DownloadedImages,
                DownloadedSongs,
                DownloadedAlbums,
                DownloadedArtists,
                DownloadedGenres,
                DownloadedSongArtists,
                DownloadedAlbumArtists,
                DownloadedArtistMembers,
                DownloadedSongGenres,
                DownloadedAlbumGenres,
                DownloadedArtistGenres
            )
        }
        repository = ExposedLibraryRepository()
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    @Test
    fun downloadedAlbumKeepsItsTags() = runBlocking {
        val anniversary = album(anniversaryTags)
        repository.saveAlbumMetadata(anniversary, explicitlySaved = true)

        assertEquals(anniversary, repository.getAlbum(anniversary.id))
        assertEquals(listOf(anniversary), repository.getAlbums(explicitlySavedOnly = true))
    }

    @Test
    fun editionsOfOneAlbumStayApart() = runBlocking {
        val standard = album(emptyList())
        val anniversary = album(anniversaryTags)
        val remaster = album(listOf(TitleTag(TitleTagKind.VERSION, "Deluxe Edition"), TitleTag(TitleTagKind.REMASTER, "2026 Remaster")))
        listOf(standard, anniversary, remaster).forEach { repository.saveAlbumMetadata(it, explicitlySaved = true) }

        assertEquals(emptyList(), repository.getAlbum(standard.id)?.tags)
        assertEquals(anniversary.tags, repository.getAlbum(anniversary.id)?.tags)
        assertEquals(remaster.tags, repository.getAlbum(remaster.id)?.tags)
        assertEquals(
            setOf("The Divine Feminine"),
            repository.getAlbums(explicitlySavedOnly = true).mapTo(HashSet()) { it.name }
        )
    }

    @Test
    fun savingAnAlbumAgainReplacesItsTags() = runBlocking {
        val anniversary = album(anniversaryTags)
        repository.saveAlbumMetadata(anniversary, explicitlySaved = true)
        repository.saveAlbumMetadata(anniversary.copy(tags = emptyList()), explicitlySaved = false)

        assertEquals(emptyList(), repository.getAlbum(anniversary.id)?.tags)
    }

    @Test
    fun downloadedSongCarriesItsAlbumTags() = runBlocking {
        val anniversary = album(anniversaryTags)
        val song = UserSong(
            id = randomPlatformUUID(),
            title = "Stay",
            artists = listOf(artist),
            album = anniversary,
            duration = 1000,
            explicit = false,
            path = "/stay.flac"
        )
        repository.saveSongMetadata(song, explicitlySaved = true)

        assertEquals(anniversary.tags, repository.getSong(song.id)?.album?.tags)
        assertEquals(anniversary.name, repository.getSong(song.id)?.album?.name)
    }
}
