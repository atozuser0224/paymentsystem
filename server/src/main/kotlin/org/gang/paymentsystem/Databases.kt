package org.gang.paymentsystem

import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.html.*
import org.jetbrains.exposed.sql.*

@OptIn(ExperimentalKtorApi::class)
fun Application.configureDatabases() {
    val database = Database.connect(
        url = "jdbc:h2:file:./data/payment;DB_CLOSE_DELAY=-1",
        user = "root",
        driver = "org.h2.Driver",
        password = "",
    )
    val cardService = CardService(database)
    val historyService = HistoryService(cardService)

    routing {
        swaggerUI(path = "swagger") {
            info = OpenApiInfo(title = "Card API", version = "1.0.0")
            source = OpenApiDocSource.Routing(
                contentType = ContentType.Application.Json
            )
        }

        // ── Admin Dashboard (HTML) ──
        get("/") {
            val summary = historyService.getSummary()
            call.respondHtml {
                head {
                    title("RFID 결제 시스템 - 관리자 대시보드")
                    style {
                        unsafe {
                            raw("""
                            body { font-family: sans-serif; margin: 20px; background: #f5f5f5; }
                            h1 { color: #333; }
                            table { border-collapse: collapse; width: 100%; background: white; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                            th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #eee; }
                            th { background: #4CAF50; color: white; }
                            tr:hover { background: #f9f9f9; }
                            .deposit { color: #4CAF50; font-weight: bold; }
                            .withdraw { color: #f44336; font-weight: bold; }
                            .summary { display: flex; gap: 20px; margin-bottom: 20px; }
                            .card { background: white; padding: 16px 24px; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); flex: 1; }
                            .card h3 { margin: 0 0 8px 0; color: #666; font-size: 14px; }
                            .card .value { font-size: 28px; font-weight: bold; color: #333; }
                            @media (prefers-color-scheme: dark) {
                                body { background: #1a1a1a; color: #ddd; }
                                table { background: #2a2a2a; }
                                th { background: #388E3C; }
                                tr:hover { background: #333; }
                                .card { background: #2a2a2a; }
                                .card h3 { color: #aaa; }
                                .card .value { color: #eee; }
                            }
                            """.trimIndent())
                        }
                    }
                    script {
                        unsafe {
                            raw("""
                            setInterval(function() { location.reload(); }, 5000);
                            """.trimIndent())
                        }
                    }
                }
                body {
                    h1 { +"RFID 결제 시스템 - 관리자 대시보드" }

                    div("summary") {
                        div("card") {
                            h3 { +"등록된 카드" }
                            div("value") { +"${summary.totalCards}" }
                        }
                        div("card") {
                            h3 { +"총 거래 건수" }
                            div("value") { +"${summary.totalTransactions}" }
                        }
                        div("card") {
                            h3 { +"총 입금액" }
                            div("value") { +"₩${summary.totalDeposits}" }
                        }
                        div("card") {
                            h3 { +"총 출금액" }
                            div("value") { +"₩${summary.totalWithdrawals}" }
                        }
                    }

                    h2 { +"최근 거래 내역" }
                    table {
                        tr {
                            th { +"시간" }
                            th { +"사용자" }
                            th { +"카드 UID" }
                            th { +"금액" }
                            th { +"유형" }
                            th { +"위치" }
                        }
                        summary.recentTransactions.forEach { tx ->
                            tr {
                                td { +tx.timestamp }
                                td { +tx.userName }
                                td { +(tx.uuid.take(16) + "...") }
                                td { +"₩${tx.amount}" }
                                td {
                                    val cls = if (tx.type == "DEPOSIT") "deposit" else "withdraw"
                                    val label = if (tx.type == "DEPOSIT") "입금" else "출금"
                                    span(cls) { +label }
                                }
                                td {
                                    val loc = if (tx.locationName.isNotEmpty()) tx.locationName
                                    else if (tx.latitude != 0.0) "%.4f, %.4f".format(tx.latitude, tx.longitude)
                                    else "-"
                                    +loc
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Card Registration ──
        post("/register") {
            val data = call.receive<CardData>()

            if (!isValidNfcFormat(data.uuid)) {
                call.respond(HttpStatusCode.BadRequest, "need valid uid code please try another code ")
            }
            val card = cardService.readByUuid(data.uuid)
            card?.let {
                call.respond(HttpStatusCode.OK, card)
            } ?: run {
                cardService.create(
                    CardData(data.userName, data.uuid, data.credit)
                )
                call.respond(HttpStatusCode.OK, "uuid is not found. created new credit card")
            }
        }.describe {
            summary = "카드 등록 또는 조회"
            description = "UUID를 기반으로 기존 카드를 조회하거나 없으면 신규 생성합니다."
            responses {
                HttpStatusCode.OK { description = "성공" }
                HttpStatusCode.BadRequest { description = "uid 아님" }
            }
        }

        // ── Payment (Buy) ──
        post("/buy") {
            val data = call.receive<BuyDto>()

            if (!isValidNfcFormat(data.uuid)) {
                call.respond(HttpStatusCode.BadRequest, "need valid uid code please try another code ")
                return@post
            }
            val card = cardService.readByUuid(data.uuid)
            card?.let {
                if (it.credit < data.credit) {
                    call.respond(HttpStatusCode.PayloadTooLarge, "too expensive")
                } else {
                    val update = it.apply { credit -= data.credit }
                    cardService.update(data.uuid, update)

                    // Log transaction
                    historyService.logTransaction(
                        TransactionRequest(
                            uuid = data.uuid,
                            userName = it.userName,
                            amount = data.credit,
                            type = "WITHDRAW"
                        )
                    )

                    call.respond(HttpStatusCode.OK, update)
                }
            } ?: run {
                call.respond(HttpStatusCode.BadRequest, "uuid is not found")
            }
        }.describe {
            summary = "결제"
            description = "UUID를 기반으로 비용 비교를 통해 결제한다"
        }

        // ── Deposit (Add) ──
        post("/add") {
            val data = call.receive<BuyDto>()

            if (!isValidNfcFormat(data.uuid)) {
                call.respond(HttpStatusCode.BadRequest, "need valid uid code please try another code ")
                return@post
            }
            val card = cardService.readByUuid(data.uuid)
            card?.let {
                val update = it.apply { credit += data.credit }
                cardService.update(data.uuid, update)

                // Log transaction
                historyService.logTransaction(
                    TransactionRequest(
                        uuid = data.uuid,
                        userName = it.userName,
                        amount = data.credit,
                        type = "DEPOSIT"
                    )
                )

                call.respond(HttpStatusCode.OK, update)
            } ?: run {
                call.respond(HttpStatusCode.BadRequest, "uuid is not found")
            }
        }.describe {
            summary = "입금"
            description = "UUID를 기반으로 입금한다"
        }

        // ── Full Transaction (with location) ──
        post("/transaction") {
            val req = call.receive<TransactionRequest>()

            if (!isValidNfcFormat(req.uuid)) {
                call.respond(HttpStatusCode.BadRequest, "need valid uid code")
                return@post
            }

            val card = cardService.readByUuid(req.uuid)
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, "uuid is not found")
                    return@post
                }

            when (req.type) {
                "WITHDRAW" -> {
                    if (card.credit < req.amount) {
                        call.respond(HttpStatusCode.PayloadTooLarge, "잔액 부족")
                        return@post
                    }
                    val update = card.apply { credit -= req.amount }
                    cardService.update(req.uuid, update)
                }
                "DEPOSIT" -> {
                    val update = card.apply { credit += req.amount }
                    cardService.update(req.uuid, update)
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest, "type must be DEPOSIT or WITHDRAW")
                    return@post
                }
            }

            val tx = historyService.logTransaction(req)
            call.respond(HttpStatusCode.OK, tx)
        }.describe {
            summary = "거래 처리 (위치 포함)"
            description = "입금 또는 출금을 처리하고 위치 정보와 함께 기록합니다."
        }

        // ── Transaction History (Admin) ──
        get("/transactions") {
            val txs = historyService.getAllTransactions()
            call.respond(HttpStatusCode.OK, txs)
        }.describe {
            summary = "전체 거래 내역 조회"
            description = "모든 카드의 거래 내역을 최신순으로 조회합니다."
        }

        get("/transactions/{uuid}") {
            val uuid = call.parameters["uuid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "uuid required")
            val txs = historyService.getTransactionsByCard(uuid)
            call.respond(HttpStatusCode.OK, txs)
        }.describe {
            summary = "카드별 거래 내역 조회"
            description = "특정 카드의 거래 내역을 조회합니다."
        }

        get("/admin/summary") {
            val summary = historyService.getSummary()
            call.respond(HttpStatusCode.OK, summary)
        }.describe {
            summary = "관리자 대시보드 요약"
            description = "전체 통계와 최근 거래 내역을 반환합니다."
        }

        // ── Delete Card ──
        delete("/card/{uuid}") {
            val uuid = call.parameters["uuid"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            cardService.delete(uuid)
            call.respond(HttpStatusCode.NoContent)
        }.describe {
            summary = "카드 삭제"
            description = "해당 UUID의 카드 정보와 모든 거래 기록을 삭제합니다."
        }
    }
}
