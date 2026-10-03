package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.*
import kotlinx.serialization.json.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection

private const val LEGACY_MUSIC_BRAINZ_ID = "musicbrainzId"
private const val MUSIC_BRAINZ_ID = "musicBrainzId"

class V1_10__SharedModelRenames : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = MigrationConnection(context.connection)
        val database = Database.connect(getNewConnection = { connection })
        try {
            transaction(database) {
                rewrite(RecentlyPlayedSongs.payload) { it.migrateSong() }
                rewrite(RecentlyPlayedAlbums.payload) { it.migrateAlbumOrArtist() }
                rewrite(RecentlyPlayedArtists.payload) { it.migrateAlbumOrArtist() }
                rewrite(ScrobbleQueue.payload) { it.migrateSong() }
                rewrite(LocalHistory.payload) { it.migrateSong() }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun rewrite(column: Column<String>, migrate: (JsonElement) -> JsonElement) {
        val table = column.table
        val changes = table.select(column).withDistinct().mapNotNull { row ->
            val raw = row[column]
            val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return@mapNotNull null
            val migrated = migrate(element)
            if (migrated == element) null else raw to migrated.toString()
        }
        changes.forEach { (raw, migrated) ->
            table.update({ column eq raw }) { it[column] = migrated }
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
