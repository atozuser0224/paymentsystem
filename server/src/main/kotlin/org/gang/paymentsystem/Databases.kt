package org.gang

import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import org.jetbrains.exposed.sql.*

@OptIn(ExperimentalKtorApi::class)
fun Application.configureDatabases() {
    val database = Database.connect(
        url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        user = "root",
        driver = "org.h2.Driver",
        password = "",
    )
    val cardService = CardService(database)
    routing {
        swaggerUI(path = "swagger") {
            info = OpenApiInfo(title = "Card API", version = "1.0.0")
            // Routing 정보를 소스로 사용하여 자동으로 openapi.json 생성
            source = OpenApiDocSource.Routing(
                contentType = ContentType.Application.Json
            )
        }
        post("/register") {
            val data = call.receive<CardData>()

            if (!isValidNfcFormat(data.uuid)){
                call.respond(HttpStatusCode.BadRequest, "need valid uid code please try another code ")
            }
            val card = cardService.readByUuid(data.uuid)
            card?.let {
                call.respond(HttpStatusCode.OK,  card)
            }?:run {
                cardService.create(
                    CardData(
                        data.userName,data.uuid,data.credit
                    )
                )
                call.respond(HttpStatusCode.OK, "uuid is not found. created new credit card")
            }
        }.describe {
            summary = "카드 등록 또는 조회"
            description = "UUID를 기반으로 기존 카드를 조회하거나 없으면 신규 생성합니다."
            responses {
                HttpStatusCode.OK {
                    description = "성공"
                    schema = jsonSchema<CardDTO>()
                }
                HttpStatusCode.BadRequest{
                    description = "uid 아님"
                }
            }
        }

        post("/buy") {
            val data = call.receive<BuyDto>()

            if (!isValidNfcFormat(data.uuid)){
                call.respond(HttpStatusCode.BadRequest, "need valid uid code please try another code ")
            }
            val card = cardService.readByUuid(data.uuid)
            card?.let {

                if (it.credit < data.credit){
                    call.respond(HttpStatusCode.PayloadTooLarge,  "too expansive")
                }else{
                    val update = it.apply {
                        credit-=data.credit
                    }
                    cardService.update(data.uuid,update)
                    call.respond(HttpStatusCode.OK,  update)
                }
            }?:run {
                call.respond(HttpStatusCode.BadRequest, "uuid is not found")
            }
        }.describe {
            summary = "결제"
            description = "UUID를 기반으로 비용 비교를 통해 결제한다"
            responses {
                HttpStatusCode.OK {
                    description = "성공"
                    schema = jsonSchema<CardDTO>()
                }
                HttpStatusCode.BadRequest{
                    description = "uid 못찾음"
                }
                HttpStatusCode.PayloadTooLarge{
                    description = "너무 비쌈"
                }
            }
        }
        post("/add") {
            val data = call.receive<BuyDto>()

            if (!isValidNfcFormat(data.uuid)){
                call.respond(HttpStatusCode.BadRequest, "need valid uid code please try another code ")
            }
            val card = cardService.readByUuid(data.uuid)
            card?.let {
                val update = it.apply {
                    credit+=data.credit
                }
                cardService.update(data.uuid,update)
                call.respond(HttpStatusCode.OK,  update)
            }?:run {
                call.respond(HttpStatusCode.BadRequest, "uuid is not found")
            }
        }.describe {
            summary = "입금"
            description = "UUID를 기반으로 입금한다"
            responses {
                HttpStatusCode.OK {
                    description = "성공"
                    schema = jsonSchema<CardDTO>()
                }
                HttpStatusCode.BadRequest{
                    description = "uid 못찾음"
                }

            }
        }
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
