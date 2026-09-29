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
}
