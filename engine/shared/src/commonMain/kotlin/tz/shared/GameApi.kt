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

    suspend fun move(target: String): GameView = postJson<GameView, MoveRequest>("/api/game/move", MoveRequest(target))

    suspend fun take(item: String): GameView = postJson<GameView, ItemRequest>("/api/game/take", ItemRequest(item))

    suspend fun drop(item: String): GameView = postJson<GameView, ItemRequest>("/api/game/drop", ItemRequest(item))

    suspend fun equip(item: String): GameView = postJson<GameView, ItemRequest>("/api/game/equip", ItemRequest(item))

    suspend fun unequip(item: String): GameView = postJson<GameView, ItemRequest>("/api/game/unequip", ItemRequest(item))

    suspend fun attack(npc: String): GameView = postJson<GameView, TargetRequest>("/api/game/attack", TargetRequest(npc))

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
