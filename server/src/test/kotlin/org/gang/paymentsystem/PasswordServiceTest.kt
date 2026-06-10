package org.gang.paymentsystem

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordServiceTest {

    @Test
    fun businessLockExpiresAtStoredDateTime() = runBlocking {
        val cardService = CardService(
            Database.connect(
                url = "jdbc:h2:mem:business-lock-test;DB_CLOSE_DELAY=-1",
                user = "root",
                driver = "org.h2.Driver",
                password = ""
            )
        )
        val zone = ZoneId.of("Asia/Seoul")
        val beforeExpiry = PasswordService(
            cardService,
            Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), zone)
        )

        beforeExpiry.lockBusiness("22:00")
        assertTrue(beforeExpiry.getBusinessStatus().locked)

        val afterExpiry = PasswordService(
            cardService,
            Clock.fixed(Instant.parse("2026-06-10T13:01:00Z"), zone)
        )
        assertFalse(afterExpiry.getBusinessStatus().locked)
        assertFalse(afterExpiry.getBusinessStatus().locked)
    }

    @Test
    fun businessLockExpiresExactlyAtSelectedMinute() = runBlocking {
        val cardService = CardService(
            Database.connect(
                url = "jdbc:h2:mem:business-lock-minute-test;DB_CLOSE_DELAY=-1",
                user = "root",
                driver = "org.h2.Driver",
                password = ""
            )
        )
        val zone = ZoneId.of("Asia/Seoul")
        val at1137 = PasswordService(
            cardService,
            Clock.fixed(Instant.parse("2026-06-10T02:37:00Z"), zone)
        )

        at1137.lockBusiness("11:38")
        assertTrue(at1137.getBusinessStatus().locked)

        val at1138 = PasswordService(
            cardService,
            Clock.fixed(Instant.parse("2026-06-10T02:38:00Z"), zone)
        )
        assertFalse(at1138.getBusinessStatus().locked)
    }
}
