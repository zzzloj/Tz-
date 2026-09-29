package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.Errors
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Speech, messages, exchange and clans between two characters. Needs TZ_TEST_DATABASE_URL. */
class SocialTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? by lazy { System.getenv("TZ_TEST_DATABASE_URL")?.let { Db.fromUrl(it).also(Db::migrate) } }
    }

    private class Pair2(val game: Game, val a: Account, val b: Account, val aName: String, val bName: String)

    private fun rnd(n: Int) = (1..n).map { ('а'..'я').random() }.joinToString("")

    private fun withTwo(block: suspend Pair2.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            fun login() = "s" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val (a, _) = accounts.register(login(), "secret-123")
            val (b, _) = accounts.register(login(), "secret-123")
            val aName = "Первый" + rnd(5); val bName = "Второй" + rnd(5)
            accounts.createCharacter(a, aName, "m")
            accounts.createCharacter(b, bName, "f")
            val game = Game(content, database, accounts, World(content, Random(9)), random = Random(9))
            game.place(a, "x1086x501"); game.place(b, "x1086x501")
            Pair2(game, a, b, aName, bName).block()
        }
    }

    private suspend fun give(account: Account, item: String, count: Int) = db!!.tx { c ->
        c.prepareStatement(
            "INSERT INTO character_items (character_id, item_id, count) SELECT id, ?, ? FROM characters WHERE account_id = ? " +
                "ON CONFLICT (character_id, item_id) DO UPDATE SET count = character_items.count + EXCLUDED.count"
        ).use { it.setString(1, item); it.setInt(2, count); it.setLong(3, account.id); it.executeUpdate() }
    }

    @Test
    fun chatFilter() {
        assertEquals("ты [бип]", cleanSpeech("ты сука"))
        assertEquals("худой кот", cleanSpeech("худой кот"))
        assertEquals("ссылка site", cleanSpeech("ссылка <b>site.ru</b>"))
        assertEquals(Rules.SAY_MAX, cleanSpeech("а".repeat(400)).length)
    }

    @Test
    fun speechReachesOthersHere() = withTwo {
        game.view(b)
        game.say(a, "Привет всем", "here")
        assertTrue(game.view(b).journal.any { it == "$aName говорит: Привет всем" })
        assertEquals(Errors.SAID_ALREADY, assertFailsWith<ApiException> { game.say(a, "Привет всем", "here") }.code)
    }

    @Test
    fun messagesNeedContacts() = withTwo {
        game.view(b)
        assertEquals(Errors.NOT_IN_CONTACTS, assertFailsWith<ApiException> { game.message(a, "write", bName, "эй") }.code)
        game.message(b, "add", aName, "")
        game.message(a, "write", bName, "Встретимся у банка")
        assertEquals(1, game.view(b).unread)
        val inbox = game.messages(b)
        assertEquals("Встретимся у банка", inbox.messages.single().text)
        assertEquals(aName, inbox.messages.single().from)
        assertEquals(0, game.view(b).unread)
        assertTrue(inbox.contacts.single().let { it.name == aName && !it.mutual })
    }

    @Test
    fun exchangeSwapsThingsAtOnce() = withTwo {
        give(a, Rules.MONEY, 100)
        give(b, "i.f.apple", 3)
        give(b, "i.q.instrum", 1)
        game.view(b)
        game.exchange(a, "start", bName, null, 1)
        game.exchange(b, "start", aName, null, 1)
        game.exchange(a, "add", null, Rules.MONEY, 30)
        game.exchange(b, "add", null, "i.f.apple", 3)
        assertEquals(Errors.CANNOT_TRADE, assertFailsWith<ApiException> { game.exchange(b, "add", null, "i.q.instrum", 1) }.code)
        var v = game.exchange(a, "agree", null, null, 1)
        assertTrue(v.exchange!!.iAgree && !v.exchange!!.theyAgree)
        // A change drops both agreements.
        game.exchange(b, "add", null, "i.f.apple", 2)
        assertTrue(!game.view(a).exchange!!.iAgree)
        game.exchange(a, "agree", null, null, 1)
        v = game.exchange(b, "agree", null, null, 1)
        assertEquals(null, v.exchange)
        assertEquals(1, v.inventory.first { it.id == "i.f.apple" }.count)
        assertEquals(30, v.inventory.first { it.id == Rules.MONEY }.count)
        assertEquals(70, game.view(a).inventory.first { it.id == Rules.MONEY }.count)
        assertEquals(2, game.view(a).inventory.first { it.id == "i.f.apple" }.count)
    }

    @Test
    fun clanRegisterInviteKick() = withTwo {
        give(a, Rules.MONEY, 50000)
        game.view(b)
        val npc = "n.guildmaster"
        var v = game.talk(a, npc, "begin", null)
        assertTrue(v.dialog!!.text.endsWith("не состоишь ни в одном клане."), v.dialog!!.text)
        val create = v.dialog!!.options.first { it.label == "Я хочу создать клан" }
        v = game.talk(a, npc, create.topic, null)
        v = game.talk(a, npc, v.dialog!!.options.first { it.label == "Я согласен" }.topic, null)
        val input = v.dialog!!.inputTopic!!
        val clan = "Стражи" + rnd(3)
        v = game.talk(a, npc, input, clan)
        assertTrue(v.dialog!!.text.startsWith("Поздравляю, ты теперь глава клана $clan"), v.dialog!!.text)
        assertEquals(clan, v.clan)
        assertEquals(1, v.inventory.first { it.id == Rules.GUILD_STONE }.count)
        assertTrue(v.inventory.none { it.id == Rules.MONEY })
        // Same name again is refused.
        give(b, Rules.MONEY, 50000)
        var w = game.talk(b, npc, "begin", null)
        w = game.talk(b, npc, w.dialog!!.options.first { it.label == "Я хочу создать клан" }.topic, null)
        w = game.talk(b, npc, w.dialog!!.options.first { it.label == "Я согласен" }.topic, null)
        w = game.talk(b, npc, w.dialog!!.inputTopic!!, clan.uppercase())
        assertTrue(w.dialog!!.text.contains("уже есть в реестре"), w.dialog!!.text)

        game.clanAction(a, "invite", bName, null, null, null)
        assertEquals(listOf(clan), game.view(b).clanInvites)
        val joined = game.clanAction(b, "accept", null, null, clan, null)
        assertEquals("neophyte", joined.rank)
        assertTrue(game.view(a).people.any { it.name == bName && it.clan == clan })
        assertEquals(Errors.CLAN_RIGHTS, assertFailsWith<ApiException> { game.clanAction(b, "kick", aName, null, null, null) }.code)
        // A head with members cannot leave; after the kick he dissolves the clan.
        assertTrue(game.clanAction(a, "leave", null, null, null, null).message!!.startsWith("Сначала передайте"))
        game.clanAction(a, "kick", bName, null, null, null)
        assertEquals(null, game.clan(b).name)
        assertEquals("Клан $clan распущен", game.clanAction(a, "leave", null, null, null, null).message)
    }
}
