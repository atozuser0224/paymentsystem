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

class PasswordService(private val cardService: CardService) {

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
        cardService.setConfig("business_locked_until", until)
    }

    suspend fun unlockBusiness() {
        cardService.deleteConfig("business_locked_until")
    }

    suspend fun getBusinessStatus(): BusinessStatusResponse {
        val until = cardService.getConfig("business_locked_until")
        if (until != null) {
            try {
                val today = java.time.LocalDate.now()
                val time = java.time.LocalTime.parse(until) // "22:00" or "22:00:00"
                var untilDateTime = java.time.LocalDateTime.of(today, time)
                // overnight: if the time has already passed today, assume tomorrow
                if (untilDateTime.isBefore(java.time.LocalDateTime.now())) {
                    untilDateTime = untilDateTime.plusDays(1)
                }
                return BusinessStatusResponse(locked = true, until = until)
            } catch (_: Exception) {}
            cardService.deleteConfig("business_locked_until")
        }
        return BusinessStatusResponse(locked = false, until = null)
    }
}
