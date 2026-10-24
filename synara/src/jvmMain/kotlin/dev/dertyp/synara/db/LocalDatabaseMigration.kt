package dev.dertyp.synara.db

import org.flywaydb.core.Flyway
import javax.sql.DataSource

object LocalDatabaseMigration {
    fun flyway(dataSource: DataSource): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations(MigrationBase.LOCATION)
        .ignoreMigrationPatterns("versioned:missing", "*:future")
        .placeholderReplacement(false)
        .baselineOnMigrate(false)
        .load()

    fun migrate(dataSource: DataSource) {
        val flyway = flyway(dataSource)
        MigrationBaseCheck(flyway).refusal()?.let { throw it }
        flyway.migrate()
    }
}
