package dev.dertyp.synara.db

import dev.dertyp.synara.utils.AppDispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

suspend fun <T> dbQuery(block: JdbcTransaction.() -> T): T =
    withContext(AppDispatchers.database) { suspendTransaction { block() } }
