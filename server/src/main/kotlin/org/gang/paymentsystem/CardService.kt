package org.gang.paymentsystem

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt

@Serializable
data class CardData(
    var userName: String,
    var uuid: String,
    var credit: Long
)

@Serializable
data class CardDTO(
    var userName: String,
    var credit: Long
)

@Serializable
data class BuyDto(
    var uuid: String,
    var credit: Long
)

@Serializable
data class TransactionRequest(
    val uuid: String,
    val userName: String,
    val amount: Long,
    val type: String,          // "DEPOSIT" or "WITHDRAW"
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val locationName: String = ""
)

@Serializable
data class TransactionDTO(
    val id: Int,
    val userName: String,
    val uuid: String,
    val amount: Long,
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val locationName: String,
    val timestamp: String
)

@Serializable
data class AdminSummary(
    val totalCards: Int,
    val totalTransactions: Int,
    val totalDeposits: Long,
    val totalWithdrawals: Long,
    val recentTransactions: List<TransactionDTO>
)

class CardService(database: Database) {
    object Cards : Table() {
        val id = integer("id").autoIncrement()
        val userName = varchar("userName", length = 50)
        val uuid = varchar("uuid", length = 100)
        val credit = long("credit")

        override val primaryKey = PrimaryKey(id)
    }

    object Transactions : Table("transactions") {
        val id = integer("id").autoIncrement()
        val userName = varchar("userName", length = 50)
        val uuid = varchar("uuid", length = 100)
        val amount = long("amount")
        val type = varchar("type", length = 20)
        val latitude = double("latitude")
        val longitude = double("longitude")
        val locationName = varchar("locationName", length = 200)
        val timestamp = varchar("timestamp", length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(Cards, Transactions)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    // ── Card operations ──

    suspend fun create(data: CardData): Int = dbQuery {
        Cards.insert {
            it[userName] = data.userName
            it[uuid] = data.uuid.toHash()
            it[credit] = data.credit
        }[Cards.id]
    }

    suspend fun readAll(): List<CardDTO> = dbQuery {
        Cards.selectAll().map {
            CardDTO(userName = it[Cards.userName], credit = it[Cards.credit])
        }
    }

    suspend fun readByUuid(uuid: String): CardDTO? = dbQuery {
        Cards.selectAll()
            .asSequence()
            .firstOrNull { BCrypt.checkpw(uuid, it[Cards.uuid]) }
            ?.let { CardDTO(userName = it[Cards.userName], credit = it[Cards.credit]) }
    }

    suspend fun update(rawUuid: String, newData: CardDTO): Int = dbQuery {
        val targetRow = Cards.selectAll().firstOrNull {
            BCrypt.checkpw(rawUuid, it[Cards.uuid])
        }
        if (targetRow != null) {
            val targetId = targetRow[Cards.id]
            Cards.update({ Cards.id eq targetId }) {
                it[userName] = newData.userName
                it[credit] = newData.credit
            }
        } else {
            0
        }
    }

    suspend fun delete(uuid: String): Int = dbQuery {
        Cards.deleteWhere { Cards.uuid eq uuid }
    }

    suspend fun countCards(): Int = dbQuery {
        Cards.selectAll().count().toInt()
    }

    // ── Transaction operations ──

    suspend fun logTransaction(request: TransactionRequest): TransactionDTO = dbQuery {
        val now = java.time.LocalDateTime.now().toString()

        Transactions.insert {
            it[userName] = request.userName
            it[uuid] = request.uuid
            it[amount] = request.amount
            it[type] = request.type
            it[latitude] = request.latitude
            it[longitude] = request.longitude
            it[locationName] = request.locationName
            it[timestamp] = now
        }

        TransactionDTO(
            id = Transactions.selectAll().last()[Transactions.id],
            userName = request.userName,
            uuid = request.uuid,
            amount = request.amount,
            type = request.type,
            latitude = request.latitude,
            longitude = request.longitude,
            locationName = request.locationName,
            timestamp = now
        )
    }

    suspend fun getAllTransactions(): List<TransactionDTO> = dbQuery {
        Transactions.selectAll().orderBy(Transactions.id, SortOrder.DESC).map {
            TransactionDTO(
                id = it[Transactions.id],
                userName = it[Transactions.userName],
                uuid = it[Transactions.uuid],
                amount = it[Transactions.amount],
                type = it[Transactions.type],
                latitude = it[Transactions.latitude],
                longitude = it[Transactions.longitude],
                locationName = it[Transactions.locationName],
                timestamp = it[Transactions.timestamp]
            )
        }
    }

    suspend fun getTransactionsByUuid(uuid: String): List<TransactionDTO> = dbQuery {
        Transactions.selectAll()
            .asSequence()
            .filter { it[Transactions.uuid] == uuid }
            .sortedByDescending { it[Transactions.id] }
            .map {
                TransactionDTO(
                    id = it[Transactions.id],
                    userName = it[Transactions.userName],
                    uuid = it[Transactions.uuid],
                    amount = it[Transactions.amount],
                    type = it[Transactions.type],
                    latitude = it[Transactions.latitude],
                    longitude = it[Transactions.longitude],
                    locationName = it[Transactions.locationName],
                    timestamp = it[Transactions.timestamp]
                )
            }
            .toList()
    }

    suspend fun getAdminSummary(): AdminSummary = dbQuery {
        val txs = Transactions.selectAll()
        val totalTxs = txs.count().toInt()
        val totalDep = txs.filter { it[Transactions.type] == "DEPOSIT" }
            .sumOf { it[Transactions.amount] }
        val totalWdr = txs.filter { it[Transactions.type] == "WITHDRAW" }
            .sumOf { it[Transactions.amount] }

        val recent = Transactions.selectAll()
            .orderBy(Transactions.id, SortOrder.DESC)
            .limit(20)
            .map {
                TransactionDTO(
                    id = it[Transactions.id],
                    userName = it[Transactions.userName],
                    uuid = it[Transactions.uuid],
                    amount = it[Transactions.amount],
                    type = it[Transactions.type],
                    latitude = it[Transactions.latitude],
                    longitude = it[Transactions.longitude],
                    locationName = it[Transactions.locationName],
                    timestamp = it[Transactions.timestamp]
                )
            }

        AdminSummary(
            totalCards = Cards.selectAll().count().toInt(),
            totalTransactions = totalTxs,
            totalDeposits = totalDep,
            totalWithdrawals = totalWdr,
            recentTransactions = recent
        )
    }
}
