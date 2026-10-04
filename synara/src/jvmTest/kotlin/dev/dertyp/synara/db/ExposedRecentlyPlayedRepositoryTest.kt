package dev.dertyp.synara.db

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppJson
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExposedRecentlyPlayedRepositoryTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedRecentlyPlayedRepository

    private val userId = randomPlatformUUID()
    private val otherUserId = randomPlatformUUID()

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-recently-played", ".db")
        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(DownloadedImages, DownloadedUsers, RecentlyPlayedSongs, RecentlyPlayedAlbums, RecentlyPlayedArtists)
            listOf(userId, otherUserId).forEach { id ->
                DownloadedUsers.insert {
                    it[DownloadedUsers.id] = id
                    it[username] = id.toString()
                    it[passwordHash] = ""
                }
            }
        }
        repository = ExposedRecentlyPlayedRepository(mockk(), mockk(), mockk(), AppJson)
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun artist(index: Int) = ArtistCredit(randomPlatformUUID(), "Artist $index", false)

    private fun album(index: Int) = Album(
        id = randomPlatformUUID(),
        name = "Album $index",
        artists = emptyList(),
        releaseDate = null,
        totalDuration = 0
    )

    private fun song(index: Int) = UserSong(
        id = randomPlatformUUID(),
        title = "Song $index",
        artists = listOf(artist(index)),
        album = album(index),
        duration = 1000,
        explicit = false,
        path = "/song$index.flac"
    )

    private fun keys(
        userIdColumn: Column<EntityID<PlatformUUID>>,
        keyColumn: Column<String>,
        timestampColumn: Column<Long>,
        user: PlatformUUID
    ): List<String> =
        transaction(database) {
            keyColumn.table.select(keyColumn)
                .where { userIdColumn eq user }
                .orderBy(timestampColumn, SortOrder.DESC)
                .map { it[keyColumn] }
        }

    @Test
    fun insertsKeepTheNewestRowsPerUser() = runBlocking {
        val otherSongs = (0 until 5).map { song(it) }
        otherSongs.forEachIndexed { index, song -> repository.insertListen(otherUserId, song, index.toLong()) }

        val songs = (0 until 40).map { song(it) }
        songs.forEachIndexed { index, song -> repository.insertListen(userId, song, 1000L + index) }

        val newest = songs.reversed()
        assertEquals(
            newest.take(RECENTLY_PLAYED_SONGS_LIMIT.toInt()).map { it.id.toString() },
            keys(RecentlyPlayedSongs.userId, RecentlyPlayedSongs.songId, RecentlyPlayedSongs.timestamp, userId)
        )
        assertEquals(
            newest.take(RECENTLY_PLAYED_ALBUMS_LIMIT.toInt()).map { it.album!!.id.toString() },
            keys(RecentlyPlayedAlbums.userId, RecentlyPlayedAlbums.albumId, RecentlyPlayedAlbums.timestamp, userId)
        )
        assertEquals(
            newest.take(RECENTLY_PLAYED_ARTISTS_LIMIT.toInt()).map { it.artists.single().id.toString() },
            keys(RecentlyPlayedArtists.userId, RecentlyPlayedArtists.artistId, RecentlyPlayedArtists.timestamp, userId)
        )

        val otherNewest = otherSongs.reversed()
        assertEquals(
            otherNewest.map { it.id.toString() },
            keys(RecentlyPlayedSongs.userId, RecentlyPlayedSongs.songId, RecentlyPlayedSongs.timestamp, otherUserId)
        )
        assertEquals(
            otherNewest.map { it.album!!.id.toString() },
            keys(RecentlyPlayedAlbums.userId, RecentlyPlayedAlbums.albumId, RecentlyPlayedAlbums.timestamp, otherUserId)
        )
        assertEquals(
            otherNewest.map { it.artists.single().id.toString() },
            keys(RecentlyPlayedArtists.userId, RecentlyPlayedArtists.artistId, RecentlyPlayedArtists.timestamp, otherUserId)
        )

        assertEquals(
            newest.take(RECENTLY_PLAYED_SONGS_LIMIT.toInt()).map { it.id },
            repository.getSongs(userId, RECENTLY_PLAYED_SONGS_LIMIT).map { it.id }
        )
    }

    @Test
    fun singleInsertsTrimToo() = runBlocking {
        val albums = (0 until 30).map { album(it) }
        albums.forEachIndexed { index, album -> repository.insertAlbum(userId, album, index.toLong()) }
        val artists = (0 until 30).map { artist(it) }
        artists.forEachIndexed { index, artist -> repository.insertArtist(userId, artist, index.toLong()) }
        val songs = (0 until 30).map { song(it) }
        songs.forEachIndexed { index, song -> repository.insertSong(userId, song, index.toLong()) }

        assertEquals(
            songs.reversed().take(RECENTLY_PLAYED_SONGS_LIMIT.toInt()).map { it.id.toString() },
            keys(RecentlyPlayedSongs.userId, RecentlyPlayedSongs.songId, RecentlyPlayedSongs.timestamp, userId)
        )
        assertEquals(
            albums.reversed().take(RECENTLY_PLAYED_ALBUMS_LIMIT.toInt()).map { it.id.toString() },
            keys(RecentlyPlayedAlbums.userId, RecentlyPlayedAlbums.albumId, RecentlyPlayedAlbums.timestamp, userId)
        )
        assertEquals(
            artists.reversed().take(RECENTLY_PLAYED_ARTISTS_LIMIT.toInt()).map { it.id.toString() },
            keys(RecentlyPlayedArtists.userId, RecentlyPlayedArtists.artistId, RecentlyPlayedArtists.timestamp, userId)
        )
    }

    @Test
    fun replayingAnOldItemKeepsItAsNewest() = runBlocking {
        val songs = (0 until RECENTLY_PLAYED_SONGS_LIMIT.toInt() + 5).map { song(it) }
        songs.forEachIndexed { index, song -> repository.insertSong(userId, song, index.toLong()) }
        repository.insertSong(userId, songs.first(), 10_000L)

        val kept = keys(RecentlyPlayedSongs.userId, RecentlyPlayedSongs.songId, RecentlyPlayedSongs.timestamp, userId)
        assertEquals(RECENTLY_PLAYED_SONGS_LIMIT.toInt(), kept.size)
        assertEquals(songs.first().id.toString(), kept.first())
        assertEquals(songs.reversed().take(RECENTLY_PLAYED_SONGS_LIMIT.toInt() - 1).map { it.id.toString() }, kept.drop(1))
    }
}
