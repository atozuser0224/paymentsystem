package org.gang.paymentsystem

import kotlinx.serialization.Serializable

@Serializable
data class VerifyPinRequest(val uuid: String, val pin: String)

@Serializable
data class VerifyMasterRequest(val password: String)

@Serializable
data class SetMasterRequest(val newPassword: String)

@Serializable
data class SetPinRequest(val uuid: String, val newPin: String)

@Serializable
data class LockBusinessRequest(val until: String)

@Serializable
data class VerifyResponse(val ok: Boolean)

@Serializable
data class BusinessStatusResponse(val locked: Boolean, val until: String?)

class PasswordService(
    private val cardService: CardService,
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone()
) {

    suspend fun verifyPin(uuid: String, pin: String): Boolean =
        cardService.verifyCardPin(uuid, pin)

    suspend fun verifyMasterPassword(password: String): Boolean {
        val hash = cardService.getConfig("master_password")
        if (hash == null) {
            // 최초 설정: 입력값을 마스터 비밀번호로 저장
            setMasterPassword(password)
            return true
        }
        return org.mindrot.jbcrypt.BCrypt.checkpw(password, hash)
    }

    suspend fun setMasterPassword(newPassword: String) {
        val hash = org.mindrot.jbcrypt.BCrypt.hashpw(newPassword, org.mindrot.jbcrypt.BCrypt.gensalt())
        cardService.setConfig("master_password", hash)
    }

    suspend fun setCardPin(uuid: String, newPin: String) {
        cardService.setCardPin(uuid, newPin)
    }

    suspend fun lockBusiness(until: String) {
        val now = java.time.LocalDateTime.now(clock)
        val lockedUntil = parseLockUntil(until, now)
        cardService.setConfig("business_locked_until", lockedUntil.toString())
    }

    suspend fun unlockBusiness() {
        cardService.deleteConfig("business_locked_until")
    }

    suspend fun getBusinessStatus(): BusinessStatusResponse {
        val until = cardService.getConfig("business_locked_until")
        if (until != null && until.isNotBlank()) {
            try {
                val untilDateTime = java.time.LocalDateTime.parse(until.trim())
                if (untilDateTime.isAfter(java.time.LocalDateTime.now(clock))) {
                    return BusinessStatusResponse(
                        locked = true,
                        until = untilDateTime.toLocalTime().toString()
                    )
                }
            } catch (_: Exception) {
                // Legacy time-only values cannot reliably survive a date change.
            }
            cardService.deleteConfig("business_locked_until")
        }
        return BusinessStatusResponse(locked = false, until = null)
    }

    private fun parseLockUntil(
        value: String,
        now: java.time.LocalDateTime
    ): java.time.LocalDateTime {
        val trimmed = value.trim()
        return try {
            java.time.LocalDateTime.parse(trimmed)
        } catch (_: Exception) {
            val time = java.time.LocalTime.parse(trimmed)
            var result = java.time.LocalDateTime.of(now.toLocalDate(), time)
            if (!result.isAfter(now)) {
                result = result.plusDays(1)
            }
            result
        }
    }
}
