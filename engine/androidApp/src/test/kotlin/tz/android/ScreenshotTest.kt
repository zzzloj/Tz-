package tz.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tz.shared.GameApi
import tz.shared.Session
import tz.shared.TokenStore
import java.io.File

/**
 * Pictures of the game screen for review (CI shows them as annotations):
 * build/screens/<name>.png. Not a comparison test — it fails only if drawing fails.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-mdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val character = """{"id":1,"name":"Scuko","sex":"m","location":"x","hp":17,"hpMax":20,
        "mana":20,"manaMax":20,"str":1,"dex":1,"int":1,"skillPoints":0,"rank":"Новичок","title":"боец"}"""

    private fun shoot(name: String, view: String) {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath == "/api/me") """{"login":"scuko","character":$character}""" else view
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val tokens = object : TokenStore {
            override fun load() = "t1"
            override fun save(token: String?) {}
        }
        val session = Session(GameApi("http://test", HttpClient(engine)), tokens)
        compose.setContent { TzTheme(dark = true) { androidx.compose.material3.Surface(color = Tz.colors.background) { App(session, live = false) } } }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Scuko")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun locationWithExits() = shoot(
        "location",
        """{"character":$character,"location":{"id":"x","name":"Двор","zone":1,"description":"Мощёная камнем дорога между добротными домами.",
            "exits":[{"label":"дом на севере","target":"a","occupied":true},{"label":"склад на юге","target":"b"},{"label":"на восток","target":"c"},
                     {"label":"выйти на улицу","target":"d"},{"label":"на запад","target":"e"}],
            "npcs":[{"id":"n1","name":"Жульен","canTalk":true}]},
            "journal":["Вы на охраняемой территории"],"journalKinds":["sys"],"journalHere":1,"belt":["","","",""],"slots":["","",""]}""",
    )
}
