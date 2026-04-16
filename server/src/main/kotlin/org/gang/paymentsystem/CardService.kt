package org.gang

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

class CardService(database: Database) {
    object Cards : Table() {
        val id = integer("id").autoIncrement()
        val userName = varchar("userName", length = 50)
        val uuid = varchar("uuid", length = 100)
        val credit = long("credit") // 데이터 클래스에 맞춰 필드 추가

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(Cards)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun create(data: CardData): Int = dbQuery {
        Cards.insert {
            it[userName] = data.userName
            it[uuid] = data.uuid.toHash()
            it[credit] = data.credit
        }[Cards.id]
    }

    suspend fun readAll(): List<CardDTO> = dbQuery {
        Cards.selectAll().map {
            CardDTO(
                userName = it[Cards.userName],
                credit = it[Cards.credit]
            )
        }
    }

    suspend fun readByUuid(uuid: String): CardDTO? = dbQuery {
        Cards.selectAll()
            .asSequence()
            .firstOrNull {
                BCrypt.checkpw(uuid, it[Cards.uuid])
            }
            ?.let {
                CardDTO(
                    userName = it[Cards.userName],
                    credit = it[Cards.credit]
                )
            }
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
}

