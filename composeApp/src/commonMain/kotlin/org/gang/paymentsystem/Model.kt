package org.gang.paymentsystem

import kotlinx.serialization.Serializable

@Serializable
data class CardData(
    val userName: String,
    val uuid: String,
    val credit: Long
)

@Serializable
data class CardDTO(
    val userName: String,
    var credit: Long
)

@Serializable
data class BuyDto(
    val uuid: String,
    val credit: Long
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
    val id: Int = 0,
    val userName: String = "",
    val uuid: String = "",
    val amount: Long = 0,
    val type: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val locationName: String = "",
    val timestamp: String = ""
)

@Serializable
data class AdminSummary(
    val totalCards: Int = 0,
    val totalTransactions: Int = 0,
    val totalDeposits: Long = 0,
    val totalWithdrawals: Long = 0,
    val recentTransactions: List<TransactionDTO> = emptyList()
)

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
data class BusinessStatusResponse(val locked: Boolean, val until: String? = null)

data class LocationData(
    val latitude: Double,
    val longitude: Double
)
