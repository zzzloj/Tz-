package tz.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameApiTest {
    private class MemoryTokens(var value: String? = null) : TokenStore {
        override fun load() = value
        override fun save(token: String?) { value = token }
    }

    private val character = """{"id":1,"name":"Анна","sex":"f","location":"_begin","hp":20,"hpMax":20,
        "mana":20,"manaMax":20,"str":1,"dex":1,"int":1,"skillPoints":2}"""
    private val location = """{"id":"_begin","name":"Переулок","zone":0,
        "exits":[{"label":"на север","target":"x1141x506"}],"npcs":[]}"""

    private fun server(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { request -> handler(request) })

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun signInLeadsToGameAndSavesToken() = runTest {
        var hasCharacter = false
        val client = server { request ->
            when (request.url.encodedPath) {
                "/api/auth/login" -> json("""{"token":"t1","login":"anna"}""")
                "/api/me" -> {
                    assertEquals("Bearer t1", request.headers[HttpHeaders.Authorization])
                    json(if (hasCharacter) """{"login":"anna","character":$character}""" else """{"login":"anna"}""")
                }
                "/api/characters" -> { hasCharacter = true; json(character, HttpStatusCode.Created) }
                "/api/game" -> json("""{"character":$character,"location":$location}""")
                "/api/game/move" -> json("""{"character":$character,"location":${location.replace("_begin", "x1141x506")}}""")
                else -> error("unexpected ${request.url}")
            }
        }
        val tokens = MemoryTokens()
        val session = Session(GameApi("http://test", client), tokens)
        session.resume()
        assertEquals(Screen.SIGN_IN, session.screen)

        session.signIn("Anna", "secret-123")
        assertNull(session.error)
        assertEquals("t1", tokens.value)
        assertEquals(Screen.CREATE_CHARACTER, session.screen)

        session.createCharacter("Aнна", female = true)   // Latin "A": refused before sending
        assertEquals(Errors.text(Errors.INVALID_NAME), session.error)

        session.createCharacter("Анна", female = true)
        assertEquals(Screen.PLAYING, session.screen)
        assertEquals("Переулок", session.game?.location?.name)

        session.go(session.game!!.location.exits.single())
        assertEquals("x1141x506", session.game?.location?.id)
    }

    @Test
    fun staleScreenIsReloadedAfterRefusal() = runTest {
        var at = "_begin"
        val client = server { request ->
            when (request.url.encodedPath) {
                "/api/auth/login" -> json("""{"token":"t1","login":"anna"}""")
                "/api/me" -> json("""{"login":"anna","character":$character}""")
                "/api/game" -> json("""{"character":$character,"location":${location.replace("_begin", at)}}""")
                "/api/game/move" -> json("""{"error":"not_an_exit","message":""}""", HttpStatusCode.BadRequest)
                else -> error("unexpected ${request.url}")
            }
        }
        val session = Session(GameApi("http://test", client), MemoryTokens())
        session.signIn("anna", "secret-123")
        assertEquals("_begin", session.game?.location?.id)
        at = "x1141x506"   // the character moved elsewhere (another device)
        session.go(session.game!!.location.exits.single())
        assertEquals(Errors.text(Errors.NOT_AN_EXIT), session.error)
        assertEquals("x1141x506", session.game?.location?.id)
    }

    @Test
    fun expiredTokenReturnsToSignIn() = runTest {
        val client = server { json("""{"error":"unauthorized","message":""}""", HttpStatusCode.Unauthorized) }
        val tokens = MemoryTokens("old")
        val session = Session(GameApi("http://test", client), tokens)
        session.resume()
        assertEquals(Screen.SIGN_IN, session.screen)
        assertNull(tokens.value)
        assertEquals(Errors.text(Errors.UNAUTHORIZED), session.error)
    }

    @Test
    fun serverErrorsBecomeRussianText() = runTest {
        val client = server { json("""{"error":"login_taken","message":"x"}""", HttpStatusCode.Conflict) }
        val session = Session(GameApi("http://test", client), MemoryTokens())
        session.register("anna", "secret-123")
        assertEquals("Такой логин уже занят", session.error)
        assertEquals(Screen.LOADING, session.screen)
    }

    @Test
    fun attackSendsTargetAndDeathResyncs() = runTest {
        var ghost = false
        val npcs = """[{"id":"n.c.rat","name":"Крыса","hp":5,"hpMax":5,"attackable":true}]"""
        fun view() = """{"character":${character.replace("\"skillPoints\":2", "\"skillPoints\":2,\"ghost\":$ghost")},
            "location":${location.replace("\"npcs\":[]", "\"npcs\":$npcs")},"journal":["Вы по Крыса ножом 2"]}"""
        val client = server { request ->
            when (request.url.encodedPath) {
                "/api/auth/login" -> json("""{"token":"t1","login":"anna"}""")
                "/api/me" -> json("""{"login":"anna","character":$character}""")
                "/api/game" -> json(view())
                "/api/game/attack" -> {
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    assertEquals("""{"target":"n.c.rat"}""", body)
                    ghost = true   // killed by the counter-blow on another device meanwhile
                    json("""{"error":"ghost","message":""}""", HttpStatusCode.Conflict)
                }
                else -> error("unexpected ${request.url}")
            }
        }
        val session = Session(GameApi("http://test", client), MemoryTokens())
        session.signIn("anna", "secret-123")
        val rat = session.game!!.location.npcs.single()
        assertEquals(true, rat.attackable)
        session.attack(rat)
        assertEquals(Errors.text(Errors.GHOST), session.error)
        assertEquals(true, session.game?.character?.ghost)
        assertEquals(listOf("Вы по Крыса ножом 2"), session.game?.journal)
    }
}
