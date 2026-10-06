package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.GameView
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Trade, bank, eating and crafting through [Game]. Needs TZ_TEST_DATABASE_URL. */
class EconomyTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? get() = TestDb.db
    }

    private class Setup(val game: Game, val account: Account, val clock: LongArray)

    private fun withGame(block: suspend Setup.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            val (account, _) = accounts.register("e" + (1..10).map { ('a'..'z').random() }.joinToString(""), "secret-123")
            accounts.createCharacter(account, "Торговец" + (1..5).map { ('а'..'я').random() }.joinToString(""), "m")
            val clock = longArrayOf(1_800_000_000L)
            Setup(Game(content, database, accounts, World(content, Random(5), clock[0]), { clock[0] }, Random(5)), account, clock).block()
        }
    }

    private suspend fun Setup.give(item: String, count: Int) = db!!.tx { c ->
        c.prepareStatement(
            "INSERT INTO character_items (character_id, item_id, count) SELECT id, ?, ? FROM characters WHERE account_id = ? " +
                "ON CONFLICT (character_id, item_id) DO UPDATE SET count = character_items.count + EXCLUDED.count"
        ).use { it.setString(1, item); it.setInt(2, count); it.setLong(3, account.id); it.executeUpdate() }
    }

    private suspend fun Setup.sql(query: String) = db!!.tx { c ->
        c.prepareStatement(query).use { it.setLong(1, account.id); it.executeUpdate() }
    }

    private fun GameView.count(id: String) = inventory.firstOrNull { it.id == id }?.count ?: 0

    private suspend fun Setup.open(npc: String, label: String): GameView {
        game.place(account, game.world.allNpcs().first { it.key == npc }.location)
        val v = game.talk(account, npc, "begin", null)
        val o = v.dialog!!.options.first { it.label.startsWith(label) }
        return game.talk(account, npc, o.topic, o.arg)
    }

    @Test
    fun buyAndSellAtTheWeaponShop() = withGame {
        give(Rules.MONEY, 1000)
        var v = open("n.Plant", "Покажи свои товары")
        val shop = assertNotNull(v.shop)
        assertEquals("buy", shop.mode)
        val item = shop.items.first()
        assertEquals(tradePrice(game.basePrice(item.id), 1, 1.2), item.price)
        v = game.shop(account, "n.Plant", "buy", item.id, 1)
        assertEquals(1000 - item.price, v.count(Rules.MONEY))
        assertEquals(1, v.count(item.id))
        assertTrue(v.shop!!.message!!.startsWith("Вы купили 1"))

        v = open("n.Plant", "Я хочу продать оружие")
        val offer = v.shop!!.items.first { it.id == item.id }
        v = game.shop(account, "n.Plant", "sell", item.id, 1)
        assertEquals(1000 - item.price + offer.price, v.count(Rules.MONEY))
        assertEquals(0, v.count(item.id))
        assertEquals(tradePrice(game.basePrice(item.id), 1, 0.6), offer.price)
    }

    @Test
    fun bankKeepsThingsAndCapsCoins() = withGame {
        give(Rules.MONEY, 100)
        var v = open("n.bankir", "Я хочу положить в банк")
        assertNotNull(v.bank)
        v = game.bank(account, "n.bankir", "put", Rules.MONEY, 60)
        assertEquals(40, v.count(Rules.MONEY))
        assertEquals(60, v.bank!!.items.single { it.id == Rules.MONEY }.count)
        v = game.bank(account, "n.bankir", "take", Rules.MONEY, 10)
        assertEquals(50, v.count(Rules.MONEY))
        give(Rules.MONEY, 80000)
        v = game.bank(account, "n.bankir", "put", Rules.MONEY, 80000)
        assertTrue(v.bank!!.message!!.contains("не более 70000"), v.bank!!.message)
        assertEquals(50, v.bank!!.items.single { it.id == Rules.MONEY }.count)
    }

    @Test
    fun eatMineAndForge() = withGame {
        sql("UPDATE characters SET hp = 5, skills = '{\"mine\": 10, \"smith\": 10}'::jsonb WHERE account_id = ?")
        give("i.f.apple", 1)
        var v = game.use(account, "i.f.apple", null, null)
        // An apple heals 5 % of the maximum (25 for a new character): +1.
        assertEquals(6, v.character.hp)
        assertEquals(0, v.count("i.f.apple"))

        // Mining at a vein: each try pauses 4 s; skill 10 (5 on the old scale) gives about half the time.
        give("i.kirka", 1)
        game.place(account, "x1523x472")
        repeat(12) {
            clock[0] += 5
            v = game.use(account, "i.kirka", null, null)
        }
        assertTrue(v.count("i.ruda") in 1..3, "ore from a vein of 3: ${v.count("i.ruda")} ${v.journal.takeLast(4)}")

        // A vein of a rare gem in a castle vault room (owner 06.10): 1 % after a good blow, only its own gem.
        game.place(account, "c.1.x1087x543")
        var found = false
        repeat(3000) {
            if (found) return@repeat
            clock[0] += 400
            v = game.use(account, "i.kirka", null, null)
            found = v.count("i.i.kr") > 0
        }
        assertTrue(found, "no беломорит from its vein")
        assertEquals(0, v.count("i.i.jd"))

        // A golem wakes in the old mines (1 %): fire, ice or stone, elite of level 30, its heart in the carcass.
        game.place(account, "x1482x185")
        var golem: World.Npc? = null
        repeat(3000) {
            if (golem != null) return@repeat
            clock[0] += 400
            game.use(account, "i.kirka", null, null)
            golem = game.world.npcsIn("x1482x185").firstOrNull { it.key.startsWith("n.c.gol.") }
        }
        val g = assertNotNull(golem, "no golem woke")
        assertTrue(g.name in setOf("Огненный голем", "Ледяной голем", "Каменный голем"), g.name)
        assertEquals(30, g.proto.stats.level)
        assertTrue(g.proto.butcher.keys.single().startsWith("i.q."), g.proto.butcher.toString())

        // The smith's hammer at Raks's anvil: a menu first, then a knife (difficulty 0).
        give("i.set.molot", 1)
        give("i.ruda", 10)
        game.place(account, "x1130x534")
        clock[0] += 10
        v = game.use(account, "i.set.molot", null, null)
        val menu = assertNotNull(v.craft)
        val knife = menu.options.first { it.name == "короткий нож" }
        assertTrue(knife.chance >= 99, "chance ${knife.chance}")
        repeat(3) {
            clock[0] += 20
            v = game.use(account, "i.set.molot", knife.key, null)
        }
        assertTrue(v.inventory.any { it.id.startsWith("i.w.k.kor_Торговец") }, v.inventory.map { it.id }.toString())
        assertTrue(v.inventory.first { it.id.startsWith("i.w.k.kor_") }.equippable)
    }
}
