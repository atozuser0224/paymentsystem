package org.gang.paymentsystem

class HistoryService(private val cardService: CardService) {

    suspend fun logTransaction(request: TransactionRequest): TransactionDTO {
        return cardService.logTransaction(request)
    }

    suspend fun getAllTransactions(): List<TransactionDTO> {
        return cardService.getAllTransactions()
    }

    suspend fun getTransactionsByCard(uuid: String): List<TransactionDTO> {
        return cardService.getTransactionsByUuid(uuid)
    }

    suspend fun getSummary(): AdminSummary {
        return cardService.getAdminSummary()
    }
}
