package tz.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.io.File

fun contentDir(): File = File(
    System.getProperty("tz.content") ?: System.getenv("TZ_CONTENT") ?: "content"
)

fun main() {
    val content = Content.load(contentDir())
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { game(content) }.start(wait = true)
}

fun Application.game(content: Content) {
    install(ContentNegotiation) { json(Json { prettyPrint = false }) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal error"))
        }
    }
    routing {
        get("/api/health") { call.respondText("ok") }
        get("/api/content/report") { call.respond(content.report()) }
        get("/api/locations/{id}") {
            val loc = content.locations[call.parameters["id"]]
            if (loc == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such location"))
            else call.respond(loc.view())
        }
    }
}
