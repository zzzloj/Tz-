package tz.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import tz.shared.AuthResponse
import tz.shared.CharacterView
import tz.shared.ErrorResponse
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.MeView
import tz.shared.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Needs PostgreSQL: TZ_TEST_DATABASE_URL (CI provides one). Without it the
 * tests are skipped, so `gradle test` still works on a machine without a database.
 */
class AccountsApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? get() = TestDb.db
    }

    private fun unique(prefix: String) = prefix + (1..8).map { ('a'..'z').random() }.joinToString("")

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        testApplication {
            val accounts = Accounts(database, content)
            application { game(content, accounts, Game(content, database, accounts, World(content))) }
            block()
        }
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        setBody(body)
    }

    private suspend fun HttpClient.getAuth(path: String, token: String?): HttpResponse = get(path) {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun error(r: HttpResponse) = json.decodeFromString(ErrorResponse.serializer(), r.bodyAsText()).error

    @Test
    fun registerCreateCharacterAndWalk() = withApp {
        val login = unique("p")
        val reg = client.postJson("/api/auth/register", """{"login":"$login","password":"secret-123"}""")
        assertEquals(HttpStatusCode.Created, reg.status, reg.bodyAsText())
        val token = json.decodeFromString(AuthResponse.serializer(), reg.bodyAsText()).token

        val me = json.decodeFromString(MeView.serializer(), client.getAuth("/api/me", token).bodyAsText())
        assertEquals(login, me.login)
        assertNull(me.character)
        assertEquals(Errors.NO_CHARACTER, error(client.getAuth("/api/game", token)))

        val name = unique("Hero").replaceFirstChar { it.uppercase() }
        val created = client.postJson("/api/characters", """{"name":"$name","sex":"f"}""", token)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val character = json.decodeFromString(CharacterView.serializer(), created.bodyAsText())
        assertEquals(Protocol.START_LOCATION, character.location)
        assertEquals(20, character.hpMax)
        assertEquals(2, character.skillPoints)

        val game = json.decodeFromString(GameView.serializer(), client.getAuth("/api/game", token).bodyAsText())
        val exit = game.location.exits.first()
        val moved = client.postJson("/api/game/move", """{"target":"${exit.target}"}""", token)
        assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
        assertEquals(exit.target, json.decodeFromString(GameView.serializer(), moved.bodyAsText()).location.id)

        // A location that is not an exit from here is refused.
        val cheat = client.postJson("/api/game/move", """{"target":"arena"}""", token)
        assertEquals(HttpStatusCode.BadRequest, cheat.status)
        assertEquals(Errors.NOT_AN_EXIT, error(cheat))

        // Position is saved.
        val again = json.decodeFromString(GameView.serializer(), client.getAuth("/api/game", token).bodyAsText())
        assertEquals(exit.target, again.character.location)

        // Only one character per account and world.
        assertEquals(Errors.CHARACTER_EXISTS, error(client.postJson("/api/characters", """{"name":"Another","sex":"m"}""", token)))
    }

    @Test
    fun loginLogoutAndBadCredentials() = withApp {
        val login = unique("q")
        client.postJson("/api/auth/register", """{"login":"$login","password":"secret-123"}""")
        assertEquals(Errors.LOGIN_TAKEN, error(client.postJson("/api/auth/register", """{"login":"${login.uppercase()}","password":"secret-456"}""")))
        assertEquals(Errors.BAD_CREDENTIALS, error(client.postJson("/api/auth/login", """{"login":"$login","password":"wrong-pass"}""")))
        assertEquals(Errors.BAD_CREDENTIALS, error(client.postJson("/api/auth/login", """{"login":"nobody_${login}","password":"secret-123"}""")))

        val ok = client.postJson("/api/auth/login", """{"login":"$login","password":"secret-123"}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        val token = json.decodeFromString(AuthResponse.serializer(), ok.bodyAsText()).token
        assertEquals(HttpStatusCode.OK, client.getAuth("/api/me", token).status)

        client.postJson("/api/auth/logout", "{}", token)
        assertEquals(HttpStatusCode.Unauthorized, client.getAuth("/api/me", token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAuth("/api/me", "made-up-token").status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAuth("/api/me", null).status)
    }

    @Test
    fun validation() = withApp {
        assertEquals(Errors.INVALID_LOGIN, error(client.postJson("/api/auth/register", """{"login":"admin","password":"secret-123"}""")))
        assertEquals(Errors.INVALID_LOGIN, error(client.postJson("/api/auth/register", """{"login":"a b","password":"secret-123"}""")))
        assertEquals(Errors.WEAK_PASSWORD, error(client.postJson("/api/auth/register", """{"login":"${unique("w")}","password":"short"}""")))
        assertEquals(Errors.BAD_REQUEST, error(client.postJson("/api/auth/register", """{"nonsense":true}""")))

        val reg = client.postJson("/api/auth/register", """{"login":"${unique("n")}","password":"secret-123"}""")
        val token = json.decodeFromString(AuthResponse.serializer(), reg.bodyAsText()).token
        // Latin look-alikes inside a Cyrillic name are refused.
        assertEquals(Errors.INVALID_NAME, error(client.postJson("/api/characters", """{"name":"Aнтoниo","sex":"m"}""", token)))
        assertEquals(Errors.INVALID_NAME, error(client.postJson("/api/characters", """{"name":"x","sex":"m"}""", token)))
        assertEquals(Errors.BAD_REQUEST, error(client.postJson("/api/characters", """{"name":"Антонио","sex":"x"}""", token)))
    }

    @Test
    fun inventoryTakeDropEquip() = withApp {
        val reg = client.postJson("/api/auth/register", """{"login":"${unique("i")}","password":"secret-123"}""")
        val token = json.decodeFromString(AuthResponse.serializer(), reg.bodyAsText()).token
        client.postJson("/api/characters", """{"name":"${unique("Inv")}","sex":"m"}""", token)

        suspend fun game(r: HttpResponse): GameView {
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            return json.decodeFromString(GameView.serializer(), r.bodyAsText())
        }

        // A new character carries the starting knife, not yet equipped.
        var g = game(client.getAuth("/api/game", token))
        assertEquals(listOf("i.w.k.begin" to false), g.inventory.map { it.id to it.equipped })
        assertEquals(listOf("Привратник Уин", "Эдвард"), g.location.npcs.map { it.name })

        g = game(client.postJson("/api/game/equip", """{"item":"i.w.k.begin"}""", token))
        assertEquals(true, g.inventory.single().equipped)
        g = game(client.postJson("/api/game/unequip", """{"item":"i.w.k.begin"}""", token))
        assertEquals(false, g.inventory.single().equipped)

        g = game(client.postJson("/api/game/drop", """{"item":"i.w.k.begin"}""", token))
        assertEquals(emptyList(), g.inventory)
        assertEquals(true, g.location.items.any { it.id == "i.w.k.begin" && it.takeable })

        g = game(client.postJson("/api/game/take", """{"item":"i.w.k.begin"}""", token))
        assertEquals(listOf("i.w.k.begin"), g.inventory.map { it.id })
        assertEquals(false, g.location.items.any { it.id == "i.w.k.begin" })

        assertEquals(Errors.NO_SUCH_ITEM, error(client.postJson("/api/game/take", """{"item":"i.w.k.begin"}""", token)))
        assertEquals(Errors.NOT_IN_INVENTORY, error(client.postJson("/api/game/drop", """{"item":"i.money"}""", token)))
        assertEquals(Errors.CANNOT_EQUIP, error(client.postJson("/api/game/equip", """{"item":"i.money"}""", token)))
        assertEquals(Errors.CANNOT_TAKE, error(client.postJson("/api/game/take", """{"item":"i.s.tree"}""", token)))
    }

    @Test
    fun namesOfOneAlphabetOnly() {
        assertEquals(true, tz.shared.Rules.validName("Антонио"))
        assertEquals(true, tz.shared.Rules.validName("Anna-Maria"))
        assertEquals(false, tz.shared.Rules.validName("Aнтoниo"))
        assertEquals(false, tz.shared.Rules.validName("Ян"))
        assertEquals(false, tz.shared.Rules.validName("Иван  Грозный"))
    }
}
