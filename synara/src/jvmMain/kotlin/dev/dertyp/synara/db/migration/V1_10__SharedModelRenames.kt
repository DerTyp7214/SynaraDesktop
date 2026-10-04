package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.*
import kotlinx.serialization.json.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.util.UUID

private const val LEGACY_MUSIC_BRAINZ_ID = "musicbrainzId"
private const val MUSIC_BRAINZ_ID = "musicBrainzId"
private const val PAGE_SIZE = 500

class V1_10__SharedModelRenames : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = MigrationConnection(context.connection)
        val database = Database.connect(getNewConnection = { connection })
        try {
            transaction(database) {
                distinctUsers(RecentlyPlayedSongs.userId).forEach { trimRecentlyPlayedSongs(it) }
                distinctUsers(RecentlyPlayedAlbums.userId).forEach { trimRecentlyPlayedAlbums(it) }
                distinctUsers(RecentlyPlayedArtists.userId).forEach { trimRecentlyPlayedArtists(it) }

                rewriteByKey(RecentlyPlayedSongs.userId, RecentlyPlayedSongs.songId, RecentlyPlayedSongs.payload) { it.migrateSong() }
                rewriteByKey(RecentlyPlayedAlbums.userId, RecentlyPlayedAlbums.albumId, RecentlyPlayedAlbums.payload) { it.migrateAlbumOrArtist() }
                rewriteByKey(RecentlyPlayedArtists.userId, RecentlyPlayedArtists.artistId, RecentlyPlayedArtists.payload) { it.migrateAlbumOrArtist() }
                rewriteById(ScrobbleQueue, ScrobbleQueue.payload) { it.migrateSong() }
                rewriteById(LocalHistory, LocalHistory.payload) { it.migrateSong() }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun distinctUsers(userIdColumn: Column<EntityID<UUID>>): List<UUID> =
        userIdColumn.table.select(userIdColumn).withDistinct().map { it[userIdColumn].value }

    private fun migrated(raw: String, migrate: (JsonElement) -> JsonElement): String? {
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val migrated = migrate(element)
        return if (migrated == element) null else migrated.toString()
    }

    private fun rewriteByKey(
        userIdColumn: Column<EntityID<UUID>>,
        keyColumn: Column<String>,
        payload: Column<String>,
        migrate: (JsonElement) -> JsonElement
    ) {
        val table = payload.table
        val changes = table.select(userIdColumn, keyColumn, payload).mapNotNull { row ->
            migrated(row[payload], migrate)?.let { Triple(row[userIdColumn].value, row[keyColumn], it) }
        }
        changes.forEach { (userId, key, value) ->
            table.update({ (userIdColumn eq userId) and (keyColumn eq key) }) { it[payload] = value }
        }
    }

    private fun rewriteById(table: IntIdTable, payload: Column<String>, migrate: (JsonElement) -> JsonElement) {
        var lastId = 0
        while (true) {
            val page = table.select(table.id, payload)
                .where { table.id greater lastId }
                .orderBy(table.id, SortOrder.ASC)
                .limit(PAGE_SIZE)
                .map { it[table.id].value to it[payload] }
            if (page.isEmpty()) return
            page.forEach { (id, raw) ->
                migrated(raw, migrate)?.let { value -> table.update({ table.id eq id }) { it[payload] = value } }
            }
            lastId = page.last().first
        }
    }

    private class MigrationConnection(delegate: Connection) : Connection by delegate {
        override fun close() = Unit
        override fun commit() = Unit
        override fun rollback() = Unit
        override fun setAutoCommit(autoCommit: Boolean) = Unit
        override fun setReadOnly(readOnly: Boolean) = Unit
        override fun setTransactionIsolation(level: Int) = Unit
    }
}

private fun JsonObject.renameMusicBrainzId(): JsonObject {
    val legacy = this[LEGACY_MUSIC_BRAINZ_ID] ?: return this
    val value = this[MUSIC_BRAINZ_ID]?.takeUnless { it is JsonNull } ?: legacy
    return JsonObject(filterKeys { it != LEGACY_MUSIC_BRAINZ_ID } + (MUSIC_BRAINZ_ID to value))
}

private fun JsonObject.mapArray(key: String, migrate: (JsonElement) -> JsonElement): JsonObject {
    val array = this[key] as? JsonArray ?: return this
    return JsonObject(this + (key to JsonArray(array.map(migrate))))
}

private fun JsonObject.mapObject(key: String, migrate: (JsonElement) -> JsonElement): JsonObject {
    val value = this[key] as? JsonObject ?: return this
    return JsonObject(this + (key to migrate(value)))
}

private fun JsonElement.migrateAlbumOrArtist(): JsonElement =
    (this as? JsonObject)?.renameMusicBrainzId()?.mapArray("artists") { it.migrateAlbumOrArtist() } ?: this

private fun JsonElement.migrateSong(): JsonElement =
    (this as? JsonObject)
        ?.mapArray("artists") { it.migrateAlbumOrArtist() }
        ?.mapObject("album") { it.migrateAlbumOrArtist() }
        ?: this
