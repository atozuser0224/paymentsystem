package org.gang.paymentsystem

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class PaymentApi(var baseUrl: String = "http://10.0.2.2:8080") {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    suspend fun registerOrFetchCard(userName: String, uuid: String, initialCredit: Long): Any {
        val response = client.post("$baseUrl/register") {
            contentType(ContentType.Application.Json)
            setBody(CardData(userName, uuid, initialCredit))
        }
        return if (response.status == HttpStatusCode.OK) {
            try {
                response.body<CardDTO>()
            } catch (e: Exception) {
                response.body<String>()
            }
        } else {
            throw Exception("Error: ${response.status}, ${response.body<String>()}")
        }
    }

    suspend fun buy(uuid: String, amount: Long): CardDTO {
        val response = client.post("$baseUrl/buy") {
            contentType(ContentType.Application.Json)
            setBody(BuyDto(uuid, amount))
        }
        return when (response.status) {
            HttpStatusCode.OK -> response.body<CardDTO>()
            HttpStatusCode.PayloadTooLarge -> throw Exception("잔액이 부족합니다.")
            else -> throw Exception("결제 실패: ${response.body<String>()}")
        }
    }

    suspend fun addCredit(uuid: String, amount: Long): CardDTO {
        val response = client.post("$baseUrl/add") {
            contentType(ContentType.Application.Json)
            setBody(BuyDto(uuid, amount))
        }
        if (response.status == HttpStatusCode.OK) {
            return response.body<CardDTO>()
        } else {
            throw Exception("입금 실패: ${response.body<String>()}")
        }
    }

    suspend fun sendTransaction(request: TransactionRequest): TransactionDTO {
        val response = client.post("$baseUrl/transaction") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return when (response.status) {
            HttpStatusCode.OK -> response.body<TransactionDTO>()
            HttpStatusCode.PayloadTooLarge -> throw Exception("잔액이 부족합니다.")
            else -> throw Exception("거래 실패: ${response.body<String>()}")
        }
    }

    suspend fun getTransactions(uuid: String? = null): List<TransactionDTO> {
        val url = if (uuid != null) "$baseUrl/transactions/$uuid" else "$baseUrl/transactions"
        val response = client.get(url)
        return if (response.status == HttpStatusCode.OK) {
            response.body<List<TransactionDTO>>()
        } else {
            emptyList()
        }
    }

    suspend fun getAdminSummary(): AdminSummary {
        val response = client.get("$baseUrl/admin/summary")
        return if (response.status == HttpStatusCode.OK) {
            response.body<AdminSummary>()
        } else {
            AdminSummary()
        }
    }

    suspend fun deleteCard(uuid: String): Boolean {
        val response = client.delete("$baseUrl/card/$uuid")
        return response.status == HttpStatusCode.NoContent
    }

    suspend fun verifyPin(uuid: String, pin: String): Boolean {
        val response = client.post("$baseUrl/verify-pin") {
            contentType(ContentType.Application.Json)
            setBody(VerifyPinRequest(uuid, pin))
        }
        return if (response.status == HttpStatusCode.OK) {
            response.body<VerifyResponse>().ok
        } else false
    }

    suspend fun verifyMasterPassword(password: String): Boolean {
        val response = client.post("$baseUrl/verify-master") {
            contentType(ContentType.Application.Json)
            setBody(VerifyMasterRequest(password))
        }
        return if (response.status == HttpStatusCode.OK) {
            response.body<VerifyResponse>().ok
        } else false
    }

    suspend fun setMasterPassword(newPassword: String): Boolean {
        val response = client.post("$baseUrl/set-master") {
            contentType(ContentType.Application.Json)
            setBody(SetMasterRequest(newPassword))
        }
        return response.status == HttpStatusCode.OK
    }

    suspend fun setCardPin(uuid: String, newPin: String): Boolean {
        val response = client.post("$baseUrl/set-pin") {
            contentType(ContentType.Application.Json)
            setBody(SetPinRequest(uuid, newPin))
        }
        return response.status == HttpStatusCode.OK
    }

    suspend fun lockBusiness(until: String): Boolean {
        val response = client.post("$baseUrl/lock-business") {
            contentType(ContentType.Application.Json)
            setBody(LockBusinessRequest(until))
        }
        return response.status == HttpStatusCode.OK
    }

    suspend fun getBusinessStatus(): BusinessStatusResponse {
        val response = client.get("$baseUrl/business-status")
        return if (response.status == HttpStatusCode.OK) {
            response.body<BusinessStatusResponse>()
        } else BusinessStatusResponse(false, null)
    }

    suspend fun unlockBusiness(): Boolean {
        val response = client.post("$baseUrl/unlock-business") {
            contentType(ContentType.Application.Json)
        }
        return response.status == HttpStatusCode.OK
    }

    suspend fun getConfig(key: String): String? {
        try {
            val response = client.get("$baseUrl/config/$key")
            if (response.status == HttpStatusCode.OK) {
                @Suppress("UNCHECKED_CAST")
                val map = response.body<Map<String, String>>()
                return map["value"]
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun setConfig(key: String, value: String): Boolean {
        val response = client.post("$baseUrl/config") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("key" to key, "value" to value))
        }
        return response.status == HttpStatusCode.OK
    }

    fun close() {
        client.close()
    }
}
