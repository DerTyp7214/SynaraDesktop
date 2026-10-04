package dev.dertyp.synara.db.migration

import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.UserSong
import dev.dertyp.PlatformUUID
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppJson
import dev.dertyp.synara.db.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.*
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.io.path.deleteIfExists
import kotlin.time.measureTimedValue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V1_10__SharedModelRenamesTest {
    private lateinit var file: Path
    private lateinit var url: String
    private lateinit var database: Database

    private val ownerId = randomPlatformUUID()
    private val songMbid = randomPlatformUUID()
    private val albumMbid = randomPlatformUUID()
    private val groupMbid = randomPlatformUUID()
    private val memberMbid = randomPlatformUUID()
    private val albumArtistMbid = randomPlatformUUID()
    private val trackId = randomPlatformUUID()

    private fun oldArtist(name: String, mbid: PlatformUUID, members: List<JsonObject> = emptyList()) = buildJsonObject {
        put("id", randomPlatformUUID().toString())
        put("name", name)
        put("isGroup", members.isNotEmpty())
        put("artists", JsonArray(members))
        put("about", "Bio of $name")
        put("musicbrainzId", mbid.toString())
    }

    private val oldGroup = oldArtist("Group", groupMbid, listOf(oldArtist("Member", memberMbid)))

    private val oldAlbum = buildJsonObject {
        put("id", randomPlatformUUID().toString())
        put("name", "Album")
        put("artists", JsonArray(listOf(oldArtist("Album Artist", albumArtistMbid))))
        put("releaseDate", JsonNull)
        put("totalDuration", 1000)
        put("musicbrainzId", albumMbid.toString())
    }

    private val oldSong = buildJsonObject {
        put("id", trackId.toString())
        put("title", "Song")
        put("artists", JsonArray(listOf(oldGroup)))
        put("album", oldAlbum)
        put("duration", 1000)
        put("explicit", true)
        put("path", "/song.flac")
        put("musicBrainzId", songMbid.toString())
    }

    private val scrobbleRequest = buildJsonObject {
        put("songId", trackId.toString())
        put("listenedAt", 1L)
        put("msPlayed", 5000L)
    }

    private val currentSong = AppJson.encodeToString(
        UserSong(
            id = randomPlatformUUID(),
            title = "Current",
            artists = listOf(ArtistCredit(randomPlatformUUID(), "Artist", false, musicBrainzId = randomPlatformUUID())),
            album = Album(
                id = randomPlatformUUID(),
                name = "Current Album",
                artists = emptyList(),
                releaseDate = null,
                totalDuration = 0,
                musicBrainzId = randomPlatformUUID()
            ),
            duration = 1000,
            explicit = false,
            path = "/current.flac"
        )
    )

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-migration", ".db")
        url = "jdbc:sqlite:$file"
        database = Database.connect(url, driver = "org.sqlite.JDBC")
        transaction(database) {
            SchemaUtils.create(
                DownloadedImages,
                DownloadedUsers,
                RecentlyPlayedSongs,
                RecentlyPlayedAlbums,
                RecentlyPlayedArtists,
                ScrobbleQueue,
                LocalHistory
            )
            DownloadedUsers.insert {
                it[id] = ownerId
                it[username] = "user"
                it[passwordHash] = ""
            }
            RecentlyPlayedSongs.insert {
                it[RecentlyPlayedSongs.userId] = ownerId
                it[RecentlyPlayedSongs.songId] = "old"
                it[timestamp] = 1L
                it[payload] = oldSong.toString()
            }
            RecentlyPlayedSongs.insert {
                it[RecentlyPlayedSongs.userId] = ownerId
                it[RecentlyPlayedSongs.songId] = "current"
                it[timestamp] = 2L
                it[payload] = currentSong
            }
            RecentlyPlayedAlbums.insert {
                it[RecentlyPlayedAlbums.userId] = ownerId
                it[albumId] = "album"
                it[timestamp] = 1L
                it[payload] = oldAlbum.toString()
            }
            RecentlyPlayedArtists.insert {
                it[RecentlyPlayedArtists.userId] = ownerId
                it[artistId] = "artist"
                it[timestamp] = 1L
                it[payload] = oldGroup.toString()
            }
            ScrobbleQueue.insert {
                it[ScrobbleQueue.userId] = ownerId
                it[ScrobbleQueue.songId] = trackId.toString()
                it[timestamp] = 1L
                it[target] = "listenbrainz"
                it[payload] = oldSong.toString()
            }
            ScrobbleQueue.insert {
                it[ScrobbleQueue.userId] = ownerId
                it[ScrobbleQueue.songId] = trackId.toString()
                it[timestamp] = 2L
                it[target] = "server"
                it[payload] = scrobbleRequest.toString()
            }
            LocalHistory.insert {
                it[LocalHistory.userId] = ownerId
                it[LocalHistory.songId] = trackId.toString()
                it[timestamp] = 1L
                it[payload] = oldSong.toString()
            }
        }
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private class RecordingConnection(
        private val delegate: Connection,
        private val statements: MutableList<String>
    ) : Connection by delegate {
        override fun prepareStatement(sql: String): PreparedStatement =
            delegate.prepareStatement(sql).also { statements += sql }

        override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement =
            delegate.prepareStatement(sql, autoGeneratedKeys).also { statements += sql }

        override fun prepareStatement(sql: String, columnIndexes: IntArray): PreparedStatement =
            delegate.prepareStatement(sql, columnIndexes).also { statements += sql }

        override fun prepareStatement(sql: String, columnNames: Array<String>): PreparedStatement =
            delegate.prepareStatement(sql, columnNames).also { statements += sql }

        override fun prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement =
            delegate.prepareStatement(sql, resultSetType, resultSetConcurrency).also { statements += sql }

        override fun prepareStatement(
            sql: String,
            resultSetType: Int,
            resultSetConcurrency: Int,
            resultSetHoldability: Int
        ): PreparedStatement =
            delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability)
                .also { statements += sql }
    }

    private fun runMigration(): List<String> {
        val statements = mutableListOf<String>()
        DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            val context = mockk<Context>()
            every { context.connection } returns RecordingConnection(connection, statements)
            V1_10__SharedModelRenames().migrate(context)
            connection.commit()
        }
        return statements.map { it.replace("\"", "").replace("`", "") }
    }

    private fun updatesOf(statements: List<String>, table: String): List<String> =
        statements.filter { it.trimStart().startsWith("UPDATE $table ", ignoreCase = true) }

    private fun payloads(column: Column<String>): List<String> =
        transaction(database) { column.table.select(column).map { it[column] } }

    private fun allPayloads(): List<List<String>> = listOf(
        RecentlyPlayedSongs.payload,
        RecentlyPlayedAlbums.payload,
        RecentlyPlayedArtists.payload,
        ScrobbleQueue.payload,
        LocalHistory.payload
    ).map { payloads(it) }

    private fun assertSongRestored(payload: String) {
        val song = AppJson.decodeFromString<UserSong>(payload)
        assertEquals(songMbid, song.musicBrainzId)
        assertTrue(song.explicit)
        assertEquals(true, AppJson.parseToJsonElement(payload).jsonObject["explicit"]?.jsonPrimitive?.boolean)
        assertEquals(groupMbid, song.artists.single().musicBrainzId)
        assertEquals(memberMbid, song.artists.single().artists.single().musicBrainzId)
        assertEquals(albumMbid, song.album?.musicBrainzId)
        assertEquals(albumArtistMbid, song.album?.artists?.single()?.musicBrainzId)
        assertTrue("musicbrainzId" !in payload)
    }

    @Test
    fun legacyMusicBrainzIdsSurvive() {
        runMigration()

        val songs = payloads(RecentlyPlayedSongs.payload)
        assertSongRestored(songs.first { it != currentSong })
        assertTrue(currentSong in songs)

        val album = AppJson.decodeFromString<Album>(payloads(RecentlyPlayedAlbums.payload).single())
        assertEquals(albumMbid, album.musicBrainzId)
        assertEquals(albumArtistMbid, album.artists.single().musicBrainzId)

        val artist = AppJson.decodeFromString<Artist>(payloads(RecentlyPlayedArtists.payload).single())
        assertEquals(groupMbid, artist.musicBrainzId)
        assertEquals(memberMbid, artist.artists.single().musicBrainzId)
        assertEquals("Bio of Group", artist.about)

        val queue = payloads(ScrobbleQueue.payload)
        assertTrue(scrobbleRequest.toString() in queue)
        assertSongRestored(queue.single { it != scrobbleRequest.toString() })

        assertSongRestored(payloads(LocalHistory.payload).single())
    }

    @Test
    fun runningTwiceIsANoOp() {
        runMigration()
        val once = allPayloads()
        val second = runMigration()
        assertEquals(once, allPayloads())
        assertEquals(emptyList<String>(), second.filter { it.trimStart().startsWith("UPDATE", ignoreCase = true) })
    }

    private fun lyrics(index: Int): String = buildString {
        var line = 0
        while (length < 6000) {
            append("Verse $index line $line of a long song text that keeps going\n")
            line++
        }
    }

    private fun oldSongVariant(index: Int) = JsonObject(
        oldSong + mapOf(
            "id" to JsonPrimitive(randomPlatformUUID().toString()),
            "title" to JsonPrimitive("Song $index"),
            "lyrics" to JsonPrimitive(lyrics(index))
        )
    )

    @Test
    fun largeHistoryMigratesEveryRowByKey() {
        val distinctSongs = 200
        val plays = 20_000
        val variants = (0 until distinctSongs).map { oldSongVariant(it).toString() }
        transaction(database) {
            LocalHistory.batchInsert((0 until plays).toList(), shouldReturnGeneratedValues = false) { index ->
                this[LocalHistory.userId] = ownerId
                this[LocalHistory.songId] = trackId.toString()
                this[LocalHistory.timestamp] = 10L + index
                this[LocalHistory.payload] = variants[index % distinctSongs]
            }
        }
        val rowCount = plays + 1

        val (statements, duration) = measureTimedValue { runMigration() }
        println("V1_10 migrated $rowCount localHistory rows (${variants.first().length} chars per payload) in $duration")

        val updates = updatesOf(statements, "localHistory")
        assertEquals(rowCount, updates.size)
        val byId = Regex("""WHERE\s+\(?\s*localHistory\.id\s*=\s*\?\s*\)?\s*$""", RegexOption.IGNORE_CASE)
        updates.forEach { sql ->
            assertTrue(byId.containsMatchIn(sql), sql)
            assertTrue("payload" !in sql.substringAfter("WHERE", "").lowercase(), sql)
        }
        assertTrue(statements.none { it.contains("DISTINCT", ignoreCase = true) && it.contains("localHistory") })

        val migrated = payloads(LocalHistory.payload)
        assertEquals(rowCount, migrated.size)
        migrated.forEach { assertSongRestored(it) }
        assertEquals(distinctSongs + 1, migrated.toSet().size)

        val second = runMigration()
        assertEquals(emptyList<String>(), updatesOf(second, "localHistory"))
        assertEquals(migrated, payloads(LocalHistory.payload))
    }

    private fun keysOf(userIdColumn: Column<EntityID<PlatformUUID>>, keyColumn: Column<String>, user: PlatformUUID): Set<String> =
        transaction(database) {
            keyColumn.table.select(keyColumn).where { userIdColumn eq user }.mapTo(HashSet()) { it[keyColumn] }
        }

    @Test
    fun migrationTrimsRecentlyPlayedPerUser() {
        val otherId = randomPlatformUUID()
        val extra = 25
        transaction(database) {
            DownloadedUsers.insert {
                it[id] = otherId
                it[username] = "other"
                it[passwordHash] = ""
            }
            (0 until extra).forEach { index ->
                RecentlyPlayedSongs.insert {
                    it[RecentlyPlayedSongs.userId] = ownerId
                    it[RecentlyPlayedSongs.songId] = "song$index"
                    it[timestamp] = 100L + index
                    it[payload] = oldSong.toString()
                }
                RecentlyPlayedAlbums.insert {
                    it[RecentlyPlayedAlbums.userId] = ownerId
                    it[albumId] = "album$index"
                    it[timestamp] = 100L + index
                    it[payload] = oldAlbum.toString()
                }
                RecentlyPlayedArtists.insert {
                    it[RecentlyPlayedArtists.userId] = ownerId
                    it[artistId] = "artist$index"
                    it[timestamp] = 100L + index
                    it[payload] = oldGroup.toString()
                }
            }
            (0 until RECENTLY_PLAYED_SONGS_LIMIT.toInt()).forEach { index ->
                RecentlyPlayedSongs.insert {
                    it[RecentlyPlayedSongs.userId] = otherId
                    it[RecentlyPlayedSongs.songId] = "other$index"
                    it[timestamp] = 0L
                    it[payload] = oldSong.toString()
                }
            }
            (0 until RECENTLY_PLAYED_ALBUMS_LIMIT.toInt()).forEach { index ->
                RecentlyPlayedAlbums.insert {
                    it[RecentlyPlayedAlbums.userId] = otherId
                    it[albumId] = "other$index"
                    it[timestamp] = 0L
                    it[payload] = oldAlbum.toString()
                }
            }
            (0 until RECENTLY_PLAYED_ARTISTS_LIMIT.toInt()).forEach { index ->
                RecentlyPlayedArtists.insert {
                    it[RecentlyPlayedArtists.userId] = otherId
                    it[artistId] = "other$index"
                    it[timestamp] = 0L
                    it[payload] = oldGroup.toString()
                }
            }
        }

        runMigration()

        fun newest(prefix: String, limit: Long) = (extra - limit.toInt() until extra).mapTo(HashSet()) { "$prefix$it" }
        fun other(limit: Long) = (0 until limit.toInt()).mapTo(HashSet()) { "other$it" }

        assertEquals(newest("song", RECENTLY_PLAYED_SONGS_LIMIT), keysOf(RecentlyPlayedSongs.userId, RecentlyPlayedSongs.songId, ownerId))
        assertEquals(newest("album", RECENTLY_PLAYED_ALBUMS_LIMIT), keysOf(RecentlyPlayedAlbums.userId, RecentlyPlayedAlbums.albumId, ownerId))
        assertEquals(newest("artist", RECENTLY_PLAYED_ARTISTS_LIMIT), keysOf(RecentlyPlayedArtists.userId, RecentlyPlayedArtists.artistId, ownerId))
        assertEquals(other(RECENTLY_PLAYED_SONGS_LIMIT), keysOf(RecentlyPlayedSongs.userId, RecentlyPlayedSongs.songId, otherId))
        assertEquals(other(RECENTLY_PLAYED_ALBUMS_LIMIT), keysOf(RecentlyPlayedAlbums.userId, RecentlyPlayedAlbums.albumId, otherId))
        assertEquals(other(RECENTLY_PLAYED_ARTISTS_LIMIT), keysOf(RecentlyPlayedArtists.userId, RecentlyPlayedArtists.artistId, otherId))

        payloads(RecentlyPlayedSongs.payload).forEach { assertSongRestored(it) }
        payloads(RecentlyPlayedAlbums.payload).forEach {
            val album = AppJson.decodeFromString<Album>(it)
            assertEquals(albumMbid, album.musicBrainzId)
            assertEquals(albumArtistMbid, album.artists.single().musicBrainzId)
        }
        payloads(RecentlyPlayedArtists.payload).forEach {
            val artist = AppJson.decodeFromString<Artist>(it)
            assertEquals(groupMbid, artist.musicBrainzId)
            assertEquals(memberMbid, artist.artists.single().musicBrainzId)
        }
        assertSongRestored(payloads(LocalHistory.payload).single())
    }

    @Test
    fun emptyTablesAreFine() {
        transaction(database) {
            listOf(RecentlyPlayedSongs, RecentlyPlayedAlbums, RecentlyPlayedArtists, ScrobbleQueue, LocalHistory)
                .forEach { SchemaUtils.drop(it) }
            SchemaUtils.create(RecentlyPlayedSongs, RecentlyPlayedAlbums, RecentlyPlayedArtists, ScrobbleQueue, LocalHistory)
        }
        runMigration()
        assertEquals(List(5) { emptyList<String>() }, allPayloads())
    }
}
