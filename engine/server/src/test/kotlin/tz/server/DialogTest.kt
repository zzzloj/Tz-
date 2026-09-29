package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.DialogView
import tz.shared.Errors
import tz.shared.GameView
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Content checks run always; conversations need TZ_TEST_DATABASE_URL. */
class DialogTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? by lazy { System.getenv("TZ_TEST_DATABASE_URL")?.let { Db.fromUrl(it).also(Db::migrate) } }
    }

    @Test
    fun logicFilesAreValid() {
        val logic = content.logic
        assertTrue(logic.problems.isEmpty(), logic.problems.joinToString("\n"))
        val untranslated = logic.untranslated()
        println("logic: ${logic.logic.size} dialogs, ${logic.timers.size} timers, ${untranslated.size} topics not moved yet")
        untranslated.take(300).forEach { println("  untranslated $it") }
    }

    @Test
    fun plainTextFromWml() {
        assertEquals("a\nb\nc", Dialogs.plain("<p>a<br/>b<br />c</p>"))
        assertEquals("Привет, <imja>!", Dialogs.plain("Привет, <b><imja></b>!"))
    }

    private fun withGame(block: suspend (Game, Account) -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            val (account, _) = accounts.register("d" + (1..10).map { ('a'..'z').random() }.joinToString(""), "secret-123")
            accounts.createCharacter(account, "Собеседник" + (1..5).map { ('а'..'я').random() }.joinToString(""), "m")
            block(Game(content, database, accounts, World(content, Random(1)), random = Random(1)), account)
        }
    }

    private fun GameView.say(label: String): Pair<String, String?> {
        val d = dialog ?: error("no dialog open")
        val o = d.options.firstOrNull { it.label.startsWith(label) } ?: error("no option «$label» in ${d.options.map { it.label }}")
        return o.topic to o.arg
    }

    private suspend fun Game.choose(account: Account, npc: String, view: GameView, label: String): GameView {
        val (topic, arg) = view.say(label)
        return talk(account, npc, topic, arg)
    }

    @Test
    fun gatekeeperGreetsNewbies() = withGame { game, account ->
        val v = game.talk(account, "n.beginner", "begin", null)
        val d: DialogView = v.dialog!!
        assertEquals("Привратник Уин", d.npcName)
        assertTrue(d.text.startsWith("Приветствую тебя, Собеседник"), d.text)
        assertTrue(d.options.any { it.topic == "news" })
        // Only topics offered on screen can be opened.
        assertEquals(Errors.TOPIC_CLOSED, assertFailsWith<ApiException> { game.talk(account, "n.beginner", "npc", null) }.code)
        val news = game.choose(account, "n.beginner", v, "Есть новости?")
        assertTrue(news.dialog!!.text.startsWith("На Волчьем острове"))
        val npc = game.choose(account, "n.beginner", news, "Что насчет жителей?")
        assertTrue(npc.dialog!!.text.contains("Лайма"))
        // Nobody to talk to: not here.
        assertEquals(Errors.NO_TARGET, assertFailsWith<ApiException> { game.talk(account, "n.Antonio", "begin", null) }.code)
    }

    @Test
    fun teacherRaisesAnAttributeForFreeForNewbies() = withGame { game, account ->
        val npc = "n.Antonio"
        val loc = game.world.allNpcs().first { it.key == npc }.location
        game.place(account, loc)
        var v = game.talk(account, npc, "begin", null)
        v = game.choose(account, npc, v, "Ты можешь меня чему-нибудь научить?")
        v = game.choose(account, npc, v, "Я хочу повысить интеллект")
        assertTrue(v.dialog!!.text.startsWith("Так как ты новичок"), v.dialog!!.text)
        v = game.choose(account, npc, v, "Согласен")
        assertTrue(v.dialog!!.text.contains("Интеллект: +1"), v.dialog!!.text)
        assertEquals(2, v.character.int)
        assertEquals(1, v.character.skillPoints)
        assertEquals(30, v.character.manaMax)
        // Second point: still free (the sum of skills and points stays 5).
        v = game.talk(account, npc, "begin", null)
        v = game.choose(account, npc, v, "Ты можешь меня чему-нибудь научить?")
        v = game.choose(account, npc, v, "Я хочу повысить интеллект")
        v = game.choose(account, npc, v, "Согласен")
        assertEquals(3, v.character.int)
        assertEquals(0, v.character.skillPoints)
        // No points left.
        v = game.talk(account, npc, "begin", null)
        v = game.choose(account, npc, v, "Ты можешь меня чему-нибудь научить?")
        v = game.choose(account, npc, v, "Я хочу повысить интеллект")
        v = game.choose(account, npc, v, "Согласен")
        assertEquals("Недостаточно очков опыта", v.dialog!!.text)
        assertEquals(3, v.character.int)
    }

    @Test
    fun healerResurrectsThroughDialog() = withGame { game, account ->
        val npc = "n.h.Djozef"
        game.place(account, game.world.allNpcs().first { it.key == npc }.location)
        var v = game.talk(account, npc, "begin", null)
        assertTrue(v.dialog!!.options.none { it.topic == "resp" }, "the living are not offered resurrection")
        game.kill(account)
        v = game.talk(account, npc, "begin", null)
        v = game.choose(account, npc, v, "Доктор, воскресите меня!")
        assertTrue(!v.character.ghost)
        assertEquals(0, v.character.hp)
        assertTrue(v.dialog!!.text.startsWith("Конечно, нет проблем"))
    }
}
