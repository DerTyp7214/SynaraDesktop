package dev.dertyp.synara.db

object MigrationBase {
    const val VERSION = "1.14"
    const val LOCATION = "classpath:db/migrations/sqlite"
    const val STEPPING_STONE = "3.17.0"
    const val STEPPING_STONE_RELEASE_URL = "https://github.com/DerTyp7214/SynaraDesktop/releases/tag/v$STEPPING_STONE"
    const val STEPPING_STONE_PACMAN_SERVER = "https://github.com/DerTyp7214/SynaraDesktop/releases/download/v$STEPPING_STONE"
}

class DatabaseNotAtBaseException(
    val reason: Reason,
    val foundVersion: String?,
) : IllegalStateException(
    "The local database cannot be used by this Synara version: ${reason.description}. " +
        "Found schema version: ${foundVersion ?: "none"}. " +
        "Required: schema version ${MigrationBase.VERSION}. " +
        "Start Synara ${MigrationBase.STEPPING_STONE} (${MigrationBase.STEPPING_STONE_RELEASE_URL}) once on this data, " +
        "then update again. " +
        "Nothing was changed in the database."
) {
    enum class Reason(val description: String) {
        TABLES_WITHOUT_HISTORY("it has tables but no migration history"),
        FAILED_MIGRATION("its migration history contains a failed migration"),
        BELOW_BASE("its schema is older than the migration base"),
        MISSING_MIGRATION("it has a migration applied that this Synara version does not contain"),
    }
}
