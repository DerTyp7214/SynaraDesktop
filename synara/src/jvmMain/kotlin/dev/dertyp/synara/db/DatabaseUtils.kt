package dev.dertyp.synara.db

import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.synara.utils.AppDispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.java.KoinJavaComponent.getKoin

fun <T> tempConnection(block: JdbcTransaction.() -> T): T {
    val dataSource = getKoin().get<HikariDataSource>()
    val db = Database.connect(dataSource)
    return transaction(db) {
        block()
    }
}

suspend fun <T> dbQuery(block: JdbcTransaction.() -> T): T =
    withContext(AppDispatchers.database) { suspendTransaction { block() } }
