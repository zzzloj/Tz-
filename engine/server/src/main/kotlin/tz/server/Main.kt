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
import tz.shared.CastRequest
import tz.shared.SkillRequest
import tz.shared.LookRequest
import tz.shared.TechniqueRequest
import tz.shared.Credentials
import tz.shared.ErrorResponse
import tz.shared.Errors
import tz.shared.ItemRequest
import tz.shared.LootRequest
import tz.shared.TalkRequest
import tz.shared.ShopRequest
import tz.shared.BankRequest
import tz.shared.UseRequest
import tz.shared.PrefsRequest
import tz.shared.SayRequest
import tz.shared.ExchangeRequest
import tz.shared.MessageRequest
import tz.shared.ClanRequest
import tz.shared.CastleRequest
import tz.shared.TargetRequest
import tz.shared.MeView
import tz.shared.MoveRequest
import tz.shared.NewCharacter
import tz.shared.AccountRequest
import tz.shared.AdminRequest
import tz.shared.ForumRequest
import tz.shared.PageSummary
import tz.shared.RecoverRequest
import kotlinx.coroutines.runBlocking
import java.io.File

/** How often the live world is written to world_snapshot (and on shutdown). */
const val WORLD_SAVE_SECONDS = 120

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
    val store = WorldStore(db)
    runBlocking {
        val restored = try { store.restore(world, System.currentTimeMillis() / 1000) } catch (e: Exception) { System.err.println("world restore: $e"); false }
        System.err.println(if (restored) "world: restored from the last snapshot" else "world: fresh from content/")
        accounts.promoteAdmins(System.getenv("TZ_ADMINS")?.split(',')?.filter { it.isNotBlank() } ?: emptyList())
    }
    val game = Game(content, db, accounts, world)
    // Railway stops the old container with SIGTERM on each deploy: save players and the world first.
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            try { game.flush() } catch (e: Exception) { System.err.println("flush on shutdown: $e") }
            try { store.save(game) } catch (e: Exception) { System.err.println("world save on shutdown: $e") }
        }
    })
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        // The world lives on its own clock (the old game only moved when a player looked).
        launch {
            var lastFlush = 0L
            var lastSnapshot = System.currentTimeMillis() / 1000
            while (isActive) {
                val now = System.currentTimeMillis() / 1000
                try { game.tick(now) } catch (e: Exception) { environment.log.error("world tick", e) }
                if (now - lastFlush >= 30) {
                    try { game.flush() } catch (e: Exception) { environment.log.error("flush", e) }
                    lastFlush = now
                }
                if (now - lastSnapshot >= WORLD_SAVE_SECONDS) {
                    try { store.save(game) } catch (e: Exception) { environment.log.error("world save", e) }
                    lastSnapshot = now
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
        artRoutes(content.art)
        get("/api/pages") { call.respond(content.pages.map { PageSummary(it.id, it.title) }) }
        get("/api/pages/{id}") {
            call.respond(content.pages.firstOrNull { it.id == call.parameters["id"] } ?: throw ApiException(HttpStatusCode.NotFound, Errors.NOT_FOUND))
        }
        if (accounts != null && game != null) {
            accountRoutes(accounts, game)
            siteRoutes(content, game)
        }
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
        call.respond(MeView(account.login, accounts.character(account), account.role))
    }
    post("/api/auth/recover") {
        val body = call.receive<RecoverRequest>()
        val (account, token) = accounts.recover(body.login, body.code, body.newPassword)
        call.respond(AuthResponse(token, account.login))
    }
    get("/api/account") { call.respond(accounts.accountView(requireAccount(call, accounts))) }
    post("/api/account") {
        val account = requireAccount(call, accounts)
        val b = call.receive<AccountRequest>()
        when (b.op) {
            "password" -> call.respond(accounts.changePassword(account, bearer(call)!!, b.password, b.newPassword))
            "recovery" -> call.respond(accounts.newRecoveryCode(account, b.password))
            "about" -> call.respond(accounts.setAbout(account, b.text))
            "delete" -> { game.deleteAccount(account, b.password); call.respond(HttpStatusCode.NoContent) }
            else -> throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
        }
    }
    get("/api/admin") { call.respond(game.adminView(requireAccount(call, accounts))) }
    post("/api/admin") {
        val account = requireAccount(call, accounts)
        call.respond(game.admin(account, call.receive<AdminRequest>()))
    }
    get("/api/forum") { call.respond(game.forum.sections(optionalAccount(call, accounts))) }
    get("/api/forum/section/{id}") {
        val id = call.parameters["id"]?.toIntOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
        call.respond(game.forum.section(optionalAccount(call, accounts), id, call.request.queryParameters["page"]?.toIntOrNull() ?: 0))
    }
    get("/api/forum/topic/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
        call.respond(game.forum.topic(optionalAccount(call, accounts), id, call.request.queryParameters["page"]?.toIntOrNull() ?: 0))
    }
    post("/api/forum") {
        val account = requireAccount(call, accounts)
        call.respond(game.forum.act(account, call.receive<ForumRequest>()))
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
        val body = call.receive<MoveRequest>()
        call.respond(game.move(account, body.target, body.gallop))
    }
    post("/api/game/take") {
        val account = requireAccount(call, accounts)
        val body = call.receive<ItemRequest>()
        call.respond(game.take(account, body.item, body.arg, body.count))
    }
    post("/api/game/drop") {
        val account = requireAccount(call, accounts)
        val body = call.receive<ItemRequest>()
        call.respond(game.drop(account, body.item, body.count))
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
        val body = call.receive<TargetRequest>()
        call.respond(if (body.player) game.attackPlayer(account, body.target) else game.attack(account, body.target))
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
    get("/api/world") {
        requireAccount(call, accounts)
        call.respond(game.worldView())
    }
    get("/api/map") {
        requireAccount(call, accounts)
        call.respond(game.mapView())
    }
    post("/api/game/skill") {
        val account = requireAccount(call, accounts)
        val body = call.receive<SkillRequest>()
        call.respond(game.skill(account, body.skill, body.target, body.item))
    }
    post("/api/game/look") {
        val account = requireAccount(call, accounts)
        call.respond(game.look(account, call.receive<LookRequest>().target))
    }
    post("/api/game/cast") {
        val account = requireAccount(call, accounts)
        val body = call.receive<CastRequest>()
        call.respond(game.cast(account, body.spell, body.target))
    }
    post("/api/game/technique") {
        val account = requireAccount(call, accounts)
        val body = call.receive<TechniqueRequest>()
        call.respond(game.technique(account, body.id, body.target))
    }
    post("/api/game/prefs") {
        val account = requireAccount(call, accounts)
        call.respond(game.prefs(account, call.receive<PrefsRequest>()))
    }
    post("/api/game/say") {
        val account = requireAccount(call, accounts)
        val body = call.receive<SayRequest>()
        call.respond(game.say(account, body.text, body.channel))
    }
    post("/api/game/exchange") {
        val account = requireAccount(call, accounts)
        val body = call.receive<ExchangeRequest>()
        call.respond(game.exchange(account, body.op, body.with, body.item, body.count))
    }
    get("/api/messages") {
        call.respond(game.messages(requireAccount(call, accounts)))
    }
    post("/api/messages") {
        val account = requireAccount(call, accounts)
        val body = call.receive<MessageRequest>()
        call.respond(game.message(account, body.op, body.to, body.text))
    }
    post("/api/game/castle") {
        val account = requireAccount(call, accounts)
        val b = call.receive<CastleRequest>()
        call.respond(game.castle(account, b.op, b.text))
    }
    get("/api/clan") {
        call.respond(game.clan(requireAccount(call, accounts)))
    }
    post("/api/clan") {
        val account = requireAccount(call, accounts)
        val b = call.receive<ClanRequest>()
        call.respond(game.clanAction(account, b.op, b.name, b.rank, b.clan, b.text))
    }
    post("/api/game/resurrect") {
        val account = requireAccount(call, accounts)
        call.respond(game.resurrect(account))
    }
    // "Your screen changed": the app re-reads GET /api/game. Authenticated like the REST calls.
    webSocket("/api/events") {
        val account = optionalAccount(call, accounts)
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

/** The account if the request carries a valid token; reading the forum needs none. */
private suspend fun optionalAccount(call: ApplicationCall, accounts: Accounts): Account? =
    bearer(call)?.let { try { accounts.authenticate(it) } catch (e: ApiException) { null } }

private suspend fun requireAccount(call: ApplicationCall, accounts: Accounts): Account =
    bearer(call)?.let { accounts.authenticate(it) } ?: throw ApiException(HttpStatusCode.Unauthorized, Errors.UNAUTHORIZED)
