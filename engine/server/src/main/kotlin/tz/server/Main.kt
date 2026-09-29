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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
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
import tz.shared.LootRequest
import tz.shared.TalkRequest
import tz.shared.ShopRequest
import tz.shared.BankRequest
import tz.shared.UseRequest
import tz.shared.TargetRequest
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
    content.logic.problems.take(20).forEach { System.err.println("logic: $it") }
    val game = Game(content, db, accounts, world)
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        // The world lives on its own clock (the old game only moved when a player looked).
        launch {
            var lastFlush = 0L
            while (isActive) {
                val now = System.currentTimeMillis() / 1000
                try { game.tick(now) } catch (e: Exception) { environment.log.error("world tick", e) }
                if (now - lastFlush >= 30) {
                    try { game.flush() } catch (e: Exception) { environment.log.error("flush", e) }
                    lastFlush = now
                }
                delay(1000)
            }
        }
        game(content, accounts, game)
    }.start(wait = true)
}

fun Application.game(content: Content, accounts: Accounts? = null, game: Game? = null) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(WebSockets) { pingPeriodMillis = 20_000; timeoutMillis = 40_000 }
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
        // Commit the running server was built from (Railway sets it), so checks can wait for a new deploy.
        get("/api/version") { call.respondText(System.getenv("RAILWAY_GIT_COMMIT_SHA") ?: "dev") }
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
    post("/api/game/attack") {
        val account = requireAccount(call, accounts)
        call.respond(game.attack(account, call.receive<TargetRequest>().target))
    }
    post("/api/game/loot") {
        val account = requireAccount(call, accounts)
        val body = call.receive<LootRequest>()
        call.respond(game.loot(account, body.corpse, body.item))
    }
    post("/api/game/butcher") {
        val account = requireAccount(call, accounts)
        call.respond(game.butcher(account, call.receive<LootRequest>().corpse))
    }
    post("/api/game/talk") {
        val account = requireAccount(call, accounts)
        val body = call.receive<TalkRequest>()
        call.respond(game.talk(account, body.npc, body.topic, body.arg))
    }
    post("/api/game/shop") {
        val account = requireAccount(call, accounts)
        val body = call.receive<ShopRequest>()
        call.respond(game.shop(account, body.npc, body.mode, body.item, body.count))
    }
    post("/api/game/bank") {
        val account = requireAccount(call, accounts)
        val body = call.receive<BankRequest>()
        call.respond(game.bank(account, body.npc, body.op, body.item, body.count))
    }
    post("/api/game/use") {
        val account = requireAccount(call, accounts)
        val body = call.receive<UseRequest>()
        call.respond(game.use(account, body.item, body.recipe, body.target))
    }
    post("/api/game/resurrect") {
        val account = requireAccount(call, accounts)
        call.respond(game.resurrect(account))
    }
    // "Your screen changed": the app re-reads GET /api/game. Authenticated like the REST calls.
    webSocket("/api/events") {
        val account = bearer(call)?.let { accounts.authenticate(it) }
        if (account == null) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, Errors.UNAUTHORIZED)); return@webSocket }
        val events = try { game.events(account) } catch (e: ApiException) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.code)); return@webSocket
        }
        val sender = launch { events.collect { send(Frame.Text("changed")) } }
        try { for (frame in incoming) { /* pings are handled by Ktor; nothing else is expected */ } } finally { sender.cancel() }
    }
}

private fun bearer(call: ApplicationCall): String? =
    call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }

private suspend fun requireAccount(call: ApplicationCall, accounts: Accounts): Account =
    bearer(call)?.let { accounts.authenticate(it) } ?: throw ApiException(HttpStatusCode.Unauthorized, Errors.UNAUTHORIZED)
