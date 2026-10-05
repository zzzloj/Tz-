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
        private val db: Db? get() = TestDb.db
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
        // The teacher's card: free for a newcomer, one training point.
        val offer = v.dialog!!.teach.single { it.key == "int" }
        assertTrue(offer.free && offer.price == 0 && offer.points == 1 && offer.level == 2 && offer.max == 10 && offer.note == null, offer.toString())
        v = game.choose(account, npc, v, "Согласен")
        assertTrue(v.dialog!!.text.contains("Интеллект: +1"), v.dialog!!.text)
        assertEquals(3, v.character.int)
        assertEquals(3, v.character.skillPoints)
        assertEquals(25, v.character.manaMax)   // 10 + 5 · 3
        // The other three starting points: still free.
        for (n in 4..6) {
            v = game.talk(account, npc, "begin", null)
            v = game.choose(account, npc, v, "Ты можешь меня чему-нибудь научить?")
            v = game.choose(account, npc, v, "Я хочу повысить интеллект")
            assertTrue(v.dialog!!.text.startsWith("Так как ты новичок"), v.dialog!!.text)
            v = game.choose(account, npc, v, "Согласен")
            assertEquals(n, v.character.int)
        }
        assertEquals(0, v.character.skillPoints)
        // No points left.
        v = game.talk(account, npc, "begin", null)
        v = game.choose(account, npc, v, "Ты можешь меня чему-нибудь научить?")
        v = game.choose(account, npc, v, "Я хочу повысить интеллект")
        v = game.choose(account, npc, v, "Согласен")
        assertEquals("Недостаточно очков обучения: они приходят с новыми уровнями", v.dialog!!.text)
        assertEquals(6, v.character.int)
        // No longer a newcomer: today's price in the old line (15 · 7² for the 7th step), and why not now.
        v = game.talk(account, npc, "begin", null)
        v = game.choose(account, npc, v, "Ты можешь меня чему-нибудь научить?")
        v = game.choose(account, npc, v, "Я хочу повысить интеллект")
        assertEquals("Я помогу развить твой интеллект за 735 монет", v.dialog!!.text)
        val paid = v.dialog!!.teach.single()
        assertTrue(!paid.free && paid.price == 735 && paid.note == "нет очков обучения", paid.toString())
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

    private suspend fun give(account: Account, item: String, count: Int = 1) = db!!.tx { c ->
        c.prepareStatement(
            "INSERT INTO character_items (character_id, item_id, count) SELECT id, ?, ? FROM characters WHERE account_id = ? " +
                "ON CONFLICT (character_id, item_id) DO UPDATE SET count = character_items.count + EXCLUDED.count"
        ).use { it.setString(1, item); it.setInt(2, count); it.setLong(3, account.id); it.executeUpdate() }
    }

    @Test
    fun archerTradesTenBrokenSticksForAnElvenBow() = withGame { game, account ->
        val npc = "n.Archer"
        game.place(account, "x1161x456")
        var v = game.talk(account, npc, "begin", null)
        assertTrue(v.dialog!!.options.none { it.topic == "sdat" }, "nothing to hand in before the quest")
        v = game.choose(account, npc, v, "Квест")
        assertTrue(v.dialog!!.text.startsWith("Убивая орков"), v.dialog!!.text)
        // Not enough sticks: nothing happens.
        give(account, "i.q.ambroken", 9)
        v = game.talk(account, npc, "begin", null)
        v = game.choose(account, npc, v, "Принёс сломанные палки")
        assertTrue(v.dialog!!.text.startsWith("Мне нужно 10"), v.dialog!!.text)
        give(account, "i.q.ambroken", 1)
        v = game.talk(account, npc, "begin", null)
        v = game.choose(account, npc, v, "Принёс сломанные палки")
        assertTrue(v.dialog!!.text.startsWith("Все десять"), v.dialog!!.text)
        val inv = v.inventory.associate { it.id to it.count }
        assertEquals(null, inv["i.q.ambroken"])
        assertEquals(1, inv["i.w.r.b.elven"])
        // Once only: the order is closed and the quest is not offered again.
        v = game.talk(account, npc, "begin", null)
        assertTrue(v.dialog!!.options.none { it.topic == "qv" || it.topic == "sdat" }, v.dialog!!.options.toString())
    }

    @Test
    fun deliveryQuestTakesTheItemAndRewards() = withGame { game, account ->
        val npc = "n.Arant"
        game.place(account, game.world.allNpcs().first { it.key == npc }.location)
        var v = game.talk(account, npc, "begin", null)
        assertTrue(v.dialog!!.options.none { it.topic == "qvok" }, "no tools, no hand-in option")
        give(account, "i.q.instrum")
        v = game.talk(account, npc, "begin", null)
        v = game.choose(account, npc, v, "Кузнец просил передать тебе эти инструменты")
        assertTrue(v.dialog!!.text.startsWith("Спасибо, Собеседник"), v.dialog!!.text)
        val inv = v.inventory.associate { it.id to it.count }
        assertEquals(null, inv["i.q.instrum"])
        assertEquals(12, inv["i.arrow"])
        assertEquals(1, inv["i.w.r.b.short"])
        // A quest of level 2 pays 11 monsters of level 2 (22 each, +8 % for a level above the hero).
        assertEquals(261L, v.character.expTotal)
        assertEquals(3, v.character.level)
    }

    @Test
    fun recruitingTimerIsPersonal() {
        // Owner 04.10: Ditrih's recruiting timer is each player's own (was shared by the whole world).
        val database = db ?: return
        withGame { game, first ->
            val accounts = Accounts(database, content)
            val (second, _) = accounts.register("d" + (1..10).map { ('a'..'z').random() }.joinToString(""), "secret-123")
            accounts.createCharacter(second, "Второй" + (1..5).map { ('а'..'я').random() }.joinToString(""), "f")
            val npc = "n.Ditrih"
            val loc = game.world.allNpcs().first { it.key == npc }.location
            game.place(first, loc); game.place(second, loc)
            var v = game.talk(first, npc, "begin", null)
            assertTrue(v.dialog!!.options.any { it.label == "Меня зовут Собеседник" + v.character.name.removePrefix("Собеседник") + ", а вы кто?" })
            v = game.choose(first, npc, v, "Я хочу поступить к вам на службу")
            assertTrue(v.dialog!!.text.startsWith("Да, нам требуются люди"), v.dialog!!.text)
            assertTrue(v.inventory.any { it.id == "i.q.ditrih" })
            var w = game.talk(second, npc, "begin", null)
            w = game.choose(second, npc, w, "Я хочу поступить к вам на службу")
            assertTrue(w.dialog!!.text.startsWith("Да, нам требуются люди"), w.dialog!!.text)
            assertTrue(w.inventory.any { it.id == "i.q.ditrih" })
            // The first one is already in.
            v = game.talk(first, npc, "begin", null)
            v = game.choose(first, npc, v, "Я хочу поступить к вам на службу")
            assertTrue(v.dialog!!.text.startsWith("Ты и так числишься"), v.dialog!!.text)
        }
    }
}
