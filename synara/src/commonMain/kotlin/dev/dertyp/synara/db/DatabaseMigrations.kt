package dev.dertyp.synara.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

object DatabaseMigrations {
    fun migrate(driver: SqlDriver) {
        val needsMigration = driver.executeQuery(null, "PRAGMA table_info(RecentlyPlayedSongEntity)", { cursor ->
            var foundUserId = false
            while (cursor.next().value) {
                if (cursor.getString(1) == "userId") {
                    foundUserId = true
                    break
                }
            }
            QueryResult.Value(!foundUserId)
        }, 0).value

        val tableExists = driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' AND name='RecentlyPlayedSongEntity'", { cursor ->
            QueryResult.Value(cursor.next().value)
        }, 0).value

        if (tableExists && needsMigration) {
            val tablesToMigrate = listOf(
                "RecentlyPlayedSongEntity", "RecentlyPlayedAlbumEntity", "RecentlyPlayedArtistEntity",
                "ScrobbleQueueEntity", "LocalHistoryEntity"
            )

            val existingTables = tablesToMigrate.filter { table ->
                driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'", { cursor ->
                    QueryResult.Value(cursor.next().value)
                }, 0).value
            }

            existingTables.forEach { table ->
                driver.execute(null, "ALTER TABLE $table RENAME TO ${table}_old", 0)
            }

            SynaraDatabase.Schema.create(driver)

            if (existingTables.contains("RecentlyPlayedSongEntity"))
                driver.execute(null, "INSERT INTO RecentlyPlayedSongEntity (userId, id, timestamp, payload) SELECT '', id, timestamp, payload FROM RecentlyPlayedSongEntity_old", 0)
            if (existingTables.contains("RecentlyPlayedAlbumEntity"))
                driver.execute(null, "INSERT INTO RecentlyPlayedAlbumEntity (userId, id, timestamp, payload) SELECT '', id, timestamp, payload FROM RecentlyPlayedAlbumEntity_old", 0)
            if (existingTables.contains("RecentlyPlayedArtistEntity"))
                driver.execute(null, "INSERT INTO RecentlyPlayedArtistEntity (userId, id, timestamp, payload) SELECT '', id, timestamp, payload FROM RecentlyPlayedArtistEntity_old", 0)
            if (existingTables.contains("ScrobbleQueueEntity"))
                driver.execute(null, "INSERT INTO ScrobbleQueueEntity (userId, payload, timestamp, target) SELECT '', payload, timestamp, target FROM ScrobbleQueueEntity_old", 0)
            if (existingTables.contains("LocalHistoryEntity"))
                driver.execute(null, "INSERT INTO LocalHistoryEntity (userId, song_id, timestamp, payload) SELECT '', song_id, timestamp, payload FROM LocalHistoryEntity_old", 0)

            existingTables.forEach { table ->
                driver.execute(null, "DROP TABLE ${table}_old", 0)
            }

            createTriggers(driver)
        } else {
            SynaraDatabase.Schema.create(driver)
            createTriggers(driver)
        }
    }

    private fun createTriggers(driver: SqlDriver) {
        val triggers = listOf(
            "prune_songs_trigger" to "RecentlyPlayedSongEntity",
            "prune_albums_trigger" to "RecentlyPlayedAlbumEntity",
            "prune_artists_trigger" to "RecentlyPlayedArtistEntity"
        )

        triggers.forEach { (name, table) ->
            driver.execute(null, "DROP TRIGGER IF EXISTS $name", 0)
            driver.execute(null, """
                CREATE TRIGGER $name
                AFTER INSERT ON $table
                BEGIN
                    DELETE FROM $table
                    WHERE userId = NEW.userId AND id NOT IN (
                        SELECT id FROM $table
                        WHERE userId = NEW.userId
                        ORDER BY timestamp DESC
                        LIMIT 100
                    );
                END;
            """.trimIndent(), 0)
        }
    }
}
