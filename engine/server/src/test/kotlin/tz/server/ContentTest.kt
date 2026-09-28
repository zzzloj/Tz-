package tz.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import tz.shared.LocationView
import tz.shared.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentTest {
    private val content by lazy { Content.load(contentDir()) }

    @Test
    fun loadsTheWholeWorld() {
        assertTrue(content.locations.size > 1300, "locations: ${content.locations.size}")
        assertTrue(content.items.size > 700, "items: ${content.items.size}")
        assertTrue(content.npcs.size > 300, "npcs: ${content.npcs.size}")
        assertTrue(content.dialogs.size > 100, "dialogs: ${content.dialogs.size}")
        println("content problems: ${content.problems.size}")
        content.problems.take(30).forEach { println("  $it") }
    }

    @Test
    fun startLocationHasExitsThatExist() {
        val start = content.locations.getValue(Protocol.START_LOCATION)
        assertTrue(start.exits.isNotEmpty())
        start.exits.forEach { assertTrue(it.target in content.locations, it.target) }
    }

    @Test
    fun namesAreCyrillicWithoutLatinLookalikes() {
        // content/ was normalized (tools/content/normalize_lookalikes.php).
        val mixed = Regex("[А-Яа-яЁё]+[AaBCcEeHKkMOoPpTXxy]|[AaBCcEeHKkMOoPpTXxy][А-Яа-яЁё]")
        val bad = content.locations.values.filter { mixed.containsMatchIn(it.name) }.map { it.id }
        assertEquals(emptyList(), bad)
    }

    @Test
    fun apiServesLocations() = testApplication {
        application { game(content) }
        val response = client.get("/api/locations/${Protocol.START_LOCATION}")
        assertEquals(HttpStatusCode.OK, response.status)
        val view = Json { ignoreUnknownKeys = true }.decodeFromString(LocationView.serializer(), response.bodyAsText())
        assertEquals(Protocol.START_LOCATION, view.id)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/locations/nowhere").status)
    }
}
