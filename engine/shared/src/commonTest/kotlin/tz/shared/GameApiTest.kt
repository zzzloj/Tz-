package tz.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameApiTest {
    private val json = """{"id":"_begin","name":"Переулок","zone":0,
        "exits":[{"label":"на север","target":"x1141x50"}],"npcs":[],"extra":1}"""

    @Test
    fun explorerLoadsStartLocation() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/locations/_begin", request.url.encodedPath)
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val explorer = Explorer(GameApi("http://test", HttpClient(engine)))
        explorer.start()
        assertNull(explorer.error)
        assertEquals("Переулок", explorer.location?.name)
        assertEquals("x1141x50", explorer.location?.exits?.single()?.target)
    }
}
