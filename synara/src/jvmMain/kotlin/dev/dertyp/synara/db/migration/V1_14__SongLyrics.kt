package dev.dertyp.synara.db.migration

import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.models.LyricLine
import dev.dertyp.services.models.LyricsMatch
import dev.dertyp.services.models.LyricsSyncLevel
import dev.dertyp.services.models.SyncedLyrics
import dev.dertyp.synara.db.DownloadedSongLyrics
import dev.dertyp.synara.db.DownloadedSongs
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.ByteBuffer
import java.sql.Connection
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val LEGACY_LYRICS = "lyrics"
private const val SONG_PROVIDER = "song"
private const val SONG_PROVIDER_NAME = "Song"

class V1_14__SongLyrics : BaseJavaMigration() {
    private class Schema(val createLyrics: List<String>, val createSong: String, val songIndices: List<String>)

    private class MovedLyrics(val songId: Any, val syncLevel: LyricsSyncLevel, val content: SyncedLyrics?, val plain: String)

    private val lineStamp = Regex("^\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val conn = context.connection
        val song = DownloadedSongs.tableName
        val replacement = "${song}_rebuilt"
        val schema = schema(replacement)

        val columns = buildList {
            conn.metaData.getColumns(null, null, song, null).use { rs ->
                while (rs.next()) add(rs.getString("COLUMN_NAME"))
            }
        }
        if (columns.none { it.equals(LEGACY_LYRICS, ignoreCase = true) }) {
            conn.createStatement().use { statement -> schema.createLyrics.forEach(statement::execute) }
            return
        }

        val defined = DownloadedSongs.columns.map { it.name.lowercase() }.toSet()
        val kept = columns.filter { it.lowercase() in defined }.joinToString(", ") { "\"$it\"" }
        val moved = movedLyrics(conn, song)

        val autoCommit = conn.autoCommit
        conn.autoCommit = true
        val enforced = conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_keys").use { rs -> rs.next() && rs.getInt(1) == 1 }
        }
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
        try {
            conn.autoCommit = false
            try {
                conn.createStatement().use { statement ->
                    schema.createLyrics.forEach(statement::execute)
                }
                insert(conn, moved)
                conn.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS \"$replacement\"")
                    statement.execute(schema.createSong)
                    statement.execute("INSERT INTO \"$replacement\" ($kept) SELECT $kept FROM \"$song\"")
                    statement.execute("DROP TABLE \"$song\"")
                    statement.execute("ALTER TABLE \"$replacement\" RENAME TO \"$song\"")
                    schema.songIndices.forEach(statement::execute)
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        } finally {
            conn.autoCommit = true
            if (enforced) conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            conn.autoCommit = autoCommit
        }
    }

    private fun schema(replacement: String): Schema =
        transaction(Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")) {
            val songPrefix = "CREATE TABLE IF NOT EXISTS ${identity(DownloadedSongs)} ("
            Schema(
                createLyrics = SchemaUtils.createStatements(DownloadedSongLyrics)
                    .filter { it.contains(DownloadedSongLyrics.tableName, ignoreCase = true) },
                createSong = "CREATE TABLE \"$replacement\" (" +
                    DownloadedSongs.createStatement().single { it.startsWith(songPrefix) }.removePrefix(songPrefix),
                songIndices = DownloadedSongs.indices.flatMap { it.createStatement() }
            )
        }

    private fun movedLyrics(conn: Connection, song: String): List<MovedLyrics> =
        conn.prepareStatement("SELECT \"id\", \"$LEGACY_LYRICS\" FROM \"$song\"").use { select ->
            select.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val text = rs.getString(LEGACY_LYRICS)
                        if (text.isNullOrBlank()) continue
                        val lines = parseLrc(text)
                        add(
                            if (lines.isEmpty()) {
                                MovedLyrics(rs.getObject("id"), LyricsSyncLevel.PLAIN, null, text.trim())
                            } else {
                                MovedLyrics(rs.getObject("id"), LyricsSyncLevel.LINE, SyncedLyrics(lines), lines.joinToString("\n") { it.text.orEmpty() })
                            }
                        )
                    }
                }
            }
        }

    private fun insert(conn: Connection, moved: List<MovedLyrics>) {
        if (moved.isEmpty()) return
        val columns = listOf(
            DownloadedSongLyrics.songId,
            DownloadedSongLyrics.candidateId,
            DownloadedSongLyrics.provider,
            DownloadedSongLyrics.providerName,
            DownloadedSongLyrics.providerEnabled,
            DownloadedSongLyrics.providerPriority,
            DownloadedSongLyrics.syncLevel,
            DownloadedSongLyrics.instrumental,
            DownloadedSongLyrics.match,
            DownloadedSongLyrics.rank,
            DownloadedSongLyrics.preferred,
            DownloadedSongLyrics.content,
            DownloadedSongLyrics.plain,
            DownloadedSongLyrics.fetchedAt
        )
        val names = columns.joinToString(", ") { "\"${it.name}\"" }
        val values = columns.joinToString(", ") { "?" }
        val now = System.currentTimeMillis()
        conn.prepareStatement("INSERT INTO \"${DownloadedSongLyrics.tableName}\" ($names) VALUES ($values)").use { insert ->
            for (row in moved) {
                val candidateId = UUID.randomUUID()
                insert.setObject(1, row.songId)
                insert.setBytes(2, ByteBuffer.allocate(16).putLong(candidateId.mostSignificantBits).putLong(candidateId.leastSignificantBits).array())
                insert.setString(3, SONG_PROVIDER)
                insert.setString(4, SONG_PROVIDER_NAME)
                insert.setBoolean(5, true)
                insert.setInt(6, 0)
                insert.setString(7, row.syncLevel.name)
                insert.setBoolean(8, false)
                insert.setString(9, LyricsMatch.PROVIDER.name)
                insert.setInt(10, 1)
                insert.setBoolean(11, false)
                insert.setBytes(12, row.content?.let { AppCbor.encodeToByteArray(SyncedLyrics.serializer(), it) })
                insert.setString(13, row.plain)
                insert.setLong(14, now)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private fun parseLrc(text: String): List<LyricLine> {
        val timed = buildList {
            for (raw in text.lines()) {
                var rest = raw.trim()
                val stamps = mutableListOf<Duration>()
                while (true) {
                    val match = lineStamp.find(rest) ?: break
                    stamps += stampOf(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                    rest = rest.substring(match.range.last + 1)
                }
                val line = rest.trim()
                if (line.isEmpty()) continue
                stamps.forEach { add(it to line) }
            }
        }.sortedBy { it.first }

        return timed.mapIndexed { index, (start, line) ->
            val next = timed.getOrNull(index + 1)?.first ?: start
            LyricLine(startTime = start, endTime = maxOf(next, start), words = emptyList(), text = line)
        }
    }

    private fun stampOf(minutes: String, seconds: String, fraction: String): Duration {
        val fractionMs = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').toLong()
        return (minutes.toLong() * 60_000 + seconds.toLong() * 1000 + fractionMs).milliseconds
    }
}
