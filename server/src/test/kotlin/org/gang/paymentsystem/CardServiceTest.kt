package org.gang.paymentsystem

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardServiceTest {

    @Test
    fun firstPinIsPersistedAndCannotBeReplacedByVerification() = runBlocking {
        val databasePath = Files.createTempDirectory("payment-system-test")
            .resolve("payment")
            .toAbsolutePath()
            .toString()
            .replace('\\', '/')
        val databaseUrl = "jdbc:h2:file:$databasePath"
        val uuid = "AA BB CC DD"

        val firstService = CardService(connect(databaseUrl))
        firstService.create(CardData("tester", uuid, 0))

        assertTrue(firstService.verifyCardPin(uuid, "1234"))
        assertFalse(firstService.verifyCardPin(uuid, "9999"))

        val restartedService = CardService(connect(databaseUrl))
        assertTrue(restartedService.verifyCardPin(uuid, "1234"))
        assertFalse(restartedService.verifyCardPin(uuid, "9999"))
    }

    private fun connect(url: String): Database = Database.connect(
        url = url,
        user = "root",
        driver = "org.h2.Driver",
        password = ""
    )
}
