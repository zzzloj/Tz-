package tz.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import kotlinx.serialization.json.Json

/** An error answer from the server; [code] is one of [Errors]. */
class ApiError(val code: String) : Exception(Errors.text(code))

/** Client for the game server, shared by the Android and iOS apps. */
class GameApi(
    var baseUrl: String,
    client: HttpClient? = null,
) {
    /** Session token after login or registration; null when signed out. */
    var token: String? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = (client ?: HttpClient()).config {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
    }

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun HttpRequestBuilder.auth() {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend inline fun <reified T> check(response: HttpResponse): T {
        if (response.status.isSuccess()) return response.body()
        val code = try {
            json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()).error
        } catch (e: Exception) {
            if (response.status.value == 401) Errors.UNAUTHORIZED else "http_${response.status.value}"
        }
        throw ApiError(code)
    }

    private suspend inline fun <reified T, reified B : Any> postJson(path: String, body: B): T =
        check(http.post(url(path)) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(body)
        })

    suspend fun location(id: String): LocationView = check(http.get(url("/api/locations/$id")))

    suspend fun register(login: String, password: String): AuthResponse =
        postJson<AuthResponse, Credentials>("/api/auth/register", Credentials(login, password)).also { token = it.token }

    suspend fun login(login: String, password: String): AuthResponse =
        postJson<AuthResponse, Credentials>("/api/auth/login", Credentials(login, password)).also { token = it.token }

    suspend fun logout() {
        try {
            http.post(url("/api/auth/logout")) { auth() }
        } finally {
            token = null
        }
    }

    suspend fun me(): MeView = check(http.get(url("/api/me")) { auth() })

    suspend fun createCharacter(name: String, sex: String): CharacterView =
        postJson<CharacterView, NewCharacter>("/api/characters", NewCharacter(name, sex))

    suspend fun game(): GameView = check(http.get(url("/api/game")) { auth() })

    suspend fun move(target: String, gallop: Boolean = false): GameView = postJson<GameView, MoveRequest>("/api/game/move", MoveRequest(target, gallop))

    suspend fun take(item: String, arg: String? = null, count: Int? = null): GameView =
        postJson<GameView, ItemRequest>("/api/game/take", ItemRequest(item, arg, count))

    /** Meditation, or stealing: without [item] peeks into [target]'s backpack. */
    suspend fun skill(skill: String, target: String? = null, item: String? = null): GameView =
        postJson<GameView, SkillRequest>("/api/game/skill", SkillRequest(skill, target, item))

    suspend fun world(): WorldView = check(http.get(url("/api/world")) { auth() })

    suspend fun chronicle(): ChronicleView = check(http.get(url("/api/chronicle")) { auth() })

    suspend fun map(): MapView = check(http.get(url("/api/map")) { auth() })

    /** Describes a character, an NPC, an item, a spell or a skill (GameView.look). */
    suspend fun look(target: String): GameView = postJson<GameView, LookRequest>("/api/game/look", LookRequest(target))

    suspend fun drop(item: String, count: Int? = null): GameView = postJson<GameView, ItemRequest>("/api/game/drop", ItemRequest(item, count = count))

    suspend fun equip(item: String): GameView = postJson<GameView, ItemRequest>("/api/game/equip", ItemRequest(item))

    suspend fun unequip(item: String): GameView = postJson<GameView, ItemRequest>("/api/game/unequip", ItemRequest(item))

    suspend fun attack(npc: String): GameView = postJson<GameView, TargetRequest>("/api/game/attack", TargetRequest(npc))

    /** Attacks another player by name. Outside the arena and castles it is a crime. */
    suspend fun attackPlayer(name: String): GameView = postJson<GameView, TargetRequest>("/api/game/attack", TargetRequest(name, player = true))

    suspend fun loot(corpse: String, item: String): GameView = postJson<GameView, LootRequest>("/api/game/loot", LootRequest(corpse, item))

    suspend fun butcher(corpse: String): GameView = postJson<GameView, LootRequest>("/api/game/butcher", LootRequest(corpse))

    suspend fun talk(npc: String, topic: String = "begin", arg: String? = null): GameView =
        postJson<GameView, TalkRequest>("/api/game/talk", TalkRequest(npc, topic, arg))

    suspend fun shop(npc: String, mode: String, item: String, count: Int): GameView =
        postJson<GameView, ShopRequest>("/api/game/shop", ShopRequest(npc, mode, item, count))

    suspend fun bank(npc: String, op: String, item: String, count: Int): GameView =
        postJson<GameView, BankRequest>("/api/game/bank", BankRequest(npc, op, item, count))

    suspend fun use(item: String, recipe: Int? = null, target: String? = null): GameView =
        postJson<GameView, UseRequest>("/api/game/use", UseRequest(item, recipe, target))

    /** Casts a spell from the book at [target] (an NPC id, a character's name or an item id). */
    suspend fun cast(spell: String, target: String? = null): GameView =
        postJson<GameView, CastRequest>("/api/game/cast", CastRequest(spell, target))

    /** Uses a technique at [target], or takes a stance (no target). */
    suspend fun technique(id: String, target: String? = null): GameView =
        postJson<GameView, TechniqueRequest>("/api/game/technique", TechniqueRequest(id, target))

    /** Combat buttons and belt (null — keep, empty — back to the default). */
    suspend fun prefs(slots: List<String>? = null, belt: List<String>? = null): GameView =
        postJson<GameView, PrefsRequest>("/api/game/prefs", PrefsRequest(slots, belt))

    /** Full address of a picture path from [GameScene.artPath] or [GameScene.itemPath]. */
    fun artUrl(path: String): String = url(path)

    suspend fun say(text: String, channel: String): GameView =
        postJson<GameView, SayRequest>("/api/game/say", SayRequest(text, channel))

    suspend fun exchange(op: String, with: String? = null, item: String? = null, count: Int = 1): GameView =
        postJson<GameView, ExchangeRequest>("/api/game/exchange", ExchangeRequest(op, with, item, count))

    suspend fun messages(): MessagesView = check(http.get(url("/api/messages")) { auth() })

    suspend fun message(op: String, to: String, text: String = ""): MessagesView =
        postJson<MessagesView, MessageRequest>("/api/messages", MessageRequest(op, to, text))

    suspend fun clan(): ClanView = check(http.get(url("/api/clan")) { auth() })

    suspend fun clan(op: String, name: String? = null, rank: String? = null, clan: String? = null, text: String? = null): ClanView =
        postJson<ClanView, ClanRequest>("/api/clan", ClanRequest(op, name, rank, clan, text))

    suspend fun castle(op: String, text: String? = null): GameView =
        postJson<GameView, CastleRequest>("/api/game/castle", CastleRequest(op, text))

    // ---- account, forum, pages, moderation ----

    /** A new password by the recovery code; signs in like [login]. */
    suspend fun recover(login: String, code: String, newPassword: String): AuthResponse =
        postJson<AuthResponse, RecoverRequest>("/api/auth/recover", RecoverRequest(login, code, newPassword)).also { token = it.token }

    suspend fun account(): AccountView = check(http.get(url("/api/account")) { auth() })

    /** op: password, recovery, about (see AccountRequest). */
    suspend fun account(op: String, password: String = "", newPassword: String = "", text: String = ""): AccountView =
        postJson<AccountView, AccountRequest>("/api/account", AccountRequest(op, password, newPassword, text))

    /** Deletes the account and character for good. */
    suspend fun deleteAccount(password: String) {
        val r = http.post(url("/api/account")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AccountRequest("delete", password))
        }
        if (!r.status.isSuccess()) check<ErrorResponse>(r)
        token = null
    }

    suspend fun forum(): ForumView = check(http.get(url("/api/forum")) { auth() })

    suspend fun forumSection(id: Int, page: Int = 0): ForumView = check(http.get(url("/api/forum/section/$id?page=$page")) { auth() })

    /** [page] -1 — the last page. */
    suspend fun forumTopic(id: Long, page: Int = 0): ForumView = check(http.get(url("/api/forum/topic/$id?page=$page")) { auth() })

    suspend fun forum(request: ForumRequest): ForumView = postJson<ForumView, ForumRequest>("/api/forum", request)

    suspend fun forumSearch(query: String): ForumView =
        check(http.get(url("/api/forum/search")) { auth(); url { parameters.append("q", query) } })

    suspend fun pages(): List<PageSummary> = check(http.get(url("/api/pages")))

    suspend fun page(id: String): PageView = check(http.get(url("/api/pages/$id")))

    suspend fun admin(): AdminView = check(http.get(url("/api/admin")) { auth() })

    suspend fun admin(request: AdminRequest): AdminView = postJson<AdminView, AdminRequest>("/api/admin", request)

    suspend fun resurrect(): GameView = check(http.post(url("/api/game/resurrect")) { auth() })

    /**
     * Listens to "your screen changed" signals until the connection closes
     * (then returns or throws); [onChange] is called for each one.
     */
    suspend fun events(onChange: suspend () -> Unit) {
        val wsUrl = url("/api/events").replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        http.webSocket(wsUrl, request = { auth() }) {
            for (frame in incoming) if (frame is Frame.Text) onChange()
        }
    }
}
