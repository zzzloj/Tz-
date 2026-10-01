package tz.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import tz.shared.GameApi
import tz.shared.Session
import tz.shared.TokenStore

/**
 * The game screen must show the new state after every action. Regression:
 * the screen got the same Session object each time, Compose skipped it, and
 * the player kept seeing the old location ("Туда отсюда не пройти").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val character = """{"id":1,"name":"Анна","sex":"f","location":"_begin","hp":20,"hpMax":20,
        "mana":20,"manaMax":20,"str":1,"dex":1,"int":1,"skillPoints":2}"""

    private fun view(loc: String, name: String, equipped: Boolean) = """{"character":$character,
        "location":{"id":"$loc","name":"$name","zone":0,"exits":[{"label":"на север","target":"north"}],"npcs":[]},
        "journal":["Вы пришли в $name"],"journalKinds":["sys"],"belt":["","","",""],"slots":["","",""],
        "inventory":[{"id":"i.w.k.begin","name":"нож","count":1,"equipped":$equipped,"equippable":true}]}"""

    @Test
    fun movingAndEquippingRedrawTheScreen() {
        var loc = "_begin" to "Переулок"
        var equipped = false
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/me" -> """{"login":"anna","character":$character}"""
                "/api/game/move" -> { loc = "north" to "Двор лекаря"; view(loc.first, loc.second, equipped) }
                "/api/game/equip" -> { equipped = true; view(loc.first, loc.second, equipped) }
                else -> view(loc.first, loc.second, equipped)
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val tokens = object : TokenStore {
            override fun load() = "t1"
            override fun save(token: String?) {}
        }
        val session = Session(GameApi("http://test", HttpClient(engine)), tokens)
        compose.setContent { TzTheme { App(session, live = false) } }

        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists("Переулок") }
        compose.onNodeWithText("на север").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists("Двор лекаря") }

        // Exits stay on every tab; the backpack is its own tab.
        compose.onNodeWithText("Сумка").performClick()
        compose.onNodeWithText("на север").assertExists()
        compose.onNodeWithText("надеть").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists("нож (надето)") }
        compose.onNodeWithText("снять").assertExists()
    }

    @Test
    fun enemiesStrikingYouComeFirstAndUnawareOnesFold() {
        val fight = """{"character":$character,"location":{"id":"f","name":"Восточный лес","zone":0,"exits":[{"label":"на север","target":"n"}],
            "npcs":[{"id":"w1","name":"волк","hp":14,"hpMax":26,"fightingYou":true,"attackable":true,"attacking":"вас","nextBlow":0,"hostile":true},
                    {"id":"w2","name":"тёмный волк","hp":30,"hpMax":30,"fightingYou":true,"attackable":true,"attacking":"вас","nextBlow":0,"hostile":true},
                    {"id":"w3","name":"белый волк","hp":34,"hpMax":34,"attackable":true,"hostile":true}]},
            "slots":["","",""],"belt":["","","",""]}"""
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath == "/api/me") """{"login":"anna","character":$character}""" else fight
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val tokens = object : TokenStore {
            override fun load() = "t1"
            override fun save(token: String?) {}
        }
        val session = Session(GameApi("http://test", HttpClient(engine)), tokens)
        compose.setContent { TzTheme { App(session, live = false) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists("бьют вас · 2") }
        compose.onNodeWithText("волк  14/26").assertExists()
        compose.onNodeWithText("не заметили вас · 1").assertExists()
        assert(!compose.onAllNodesWithTextExists("белый волк  34/34")) { "an unaware monster is folded away in a fight" }
        compose.onNodeWithText("не заметили вас · 1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists("белый волк  34/34") }
        compose.onNodeWithText("на север").assertExists()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextExists(text: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
