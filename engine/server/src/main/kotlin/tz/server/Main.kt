package tz.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tz.shared.AuthResponse
import tz.shared.Credentials
import tz.shared.ErrorResponse
import tz.shared.Errors
import tz.shared.ItemRequest
import tz.shared.MeView
import tz.shared.MoveRequest
import tz.shared.NewCharacter
import java.io.File

fun contentDir(): File = File(
    System.getProperty("tz.content") ?: System.getenv("TZ_CONTENT") ?: "content"
)

fun main() {
    val content = Content.load(contentDir())
    val dbUrl = System.getenv("DATABASE_URL") ?: error("DATABASE_URL is not set")
    val db = Db.fromUrl(dbUrl).also { it.migrate() }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val accounts = Accounts(db, content)
    val world = World(content)
    world.problems.take(20).forEach { System.err.println("world: $it") }
    val game = Game(content, db, accounts, world)
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        // The world lives on its own clock (the old game only moved when a player looked).
        launch {
            while (isActive) {
                try { world.tick(System.currentTimeMillis() / 1000) } catch (e: Exception) { environment.log.error("world tick", e) }
                delay(1000)
            }
        }
        game(content, accounts, game)
    }.start(wait = true)
}

fun Application.game(content: Content, accounts: Accounts? = null, game: Game? = null) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ErrorResponse(e.code, Errors.text(e.code)))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(Errors.BAD_REQUEST, Errors.text(Errors.BAD_REQUEST)))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(Errors.BAD_REQUEST, Errors.text(Errors.BAD_REQUEST)))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal", "Ошибка сервера"))
        }
    }
    routing {
        get("/api/health") { call.respondText("ok") }
        get("/api/content/report") { call.respond(content.report()) }
        get("/api/locations/{id}") {
            val loc = content.locations[call.parameters["id"]]
            if (loc == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Нет такой локации"))
            else call.respond(loc.view())
        }
        if (accounts != null && game != null) accountRoutes(accounts, game)
    }
}

private fun Route.accountRoutes(accounts: Accounts, game: Game) {
    post("/api/auth/register") {
        val body = call.receive<Credentials>()
        val (account, token) = accounts.register(body.login, body.password)
        call.respond(HttpStatusCode.Created, AuthResponse(token, account.login))
    }
    post("/api/auth/login") {
        val body = call.receive<Credentials>()
        val (account, token) = accounts.login(body.login, body.password)
        call.respond(AuthResponse(token, account.login))
    }
    post("/api/auth/logout") {
        bearer(call)?.let { accounts.logout(it) }
        call.respond(HttpStatusCode.NoContent)
    }
    get("/api/me") {
        val account = requireAccount(call, accounts)
        call.respond(MeView(account.login, accounts.character(account)))
    }
    post("/api/characters") {
        val account = requireAccount(call, accounts)
        val body = call.receive<NewCharacter>()
        call.respond(HttpStatusCode.Created, accounts.createCharacter(account, body.name, body.sex))
    }
    get("/api/game") {
        call.respond(game.view(requireAccount(call, accounts)))
    }
    post("/api/game/move") {
        val account = requireAccount(call, accounts)
        call.respond(game.move(account, call.receive<MoveRequest>().target))
    }
    post("/api/game/take") {
        val account = requireAccount(call, accounts)
        call.respond(game.take(account, call.receive<ItemRequest>().item))
    }
    post("/api/game/drop") {
        val account = requireAccount(call, accounts)
        call.respond(game.drop(account, call.receive<ItemRequest>().item))
    }
    post("/api/game/equip") {
        val account = requireAccount(call, accounts)
        call.respond(game.equip(account, call.receive<ItemRequest>().item))
    }
    post("/api/game/unequip") {
        val account = requireAccount(call, accounts)
        call.respond(game.unequip(account, call.receive<ItemRequest>().item))
    }
}

private fun bearer(call: ApplicationCall): String? =
    call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }

private suspend fun requireAccount(call: ApplicationCall, accounts: Accounts): Account =
    bearer(call)?.let { accounts.authenticate(it) } ?: throw ApiException(HttpStatusCode.Unauthorized, Errors.UNAUTHORIZED)
