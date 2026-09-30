package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.DialogView
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The leadership flag, marriage, Tomrak, bouquets, the world page and the map. Needs TZ_TEST_DATABASE_URL. */
class SocietyTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? get() = TestDb.db
    }

    private class Folk(val game: Game, val acc: List<Account>, val names: List<String>, val clock: LongArray)

    private fun rnd(n: Int) = (1..n).map { ('а'..'я').random() }.joinToString("")

    /** Four characters: a man, a woman and two witnesses. */
    private fun withFolk(seed: Int, block: suspend Folk.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            fun login() = "w" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val names = listOf("Жених" + rnd(5), "Невеста" + rnd(5), "Свидетель" + rnd(5), "Гость" + rnd(5))
            val acc = names.mapIndexed { i, n ->
                val (a, _) = accounts.register(login(), "secret-123")
                accounts.createCharacter(a, n, if (i == 1) "f" else "m")
                database.tx { c ->
                    c.prepareStatement("UPDATE characters SET str = 3, dex = 3, hp = ? WHERE account_id = ?").use {
                        it.setInt(1, Rules.hpMax(3)); it.setLong(2, a.id); it.executeUpdate()
                    }
                }
                a
            }
            val clock = longArrayOf(1_800_000_000L)
            val game = Game(content, database, accounts, World(content, Random(seed), clock[0]), { clock[0] }, Random(seed))
            Folk(game, acc, names, clock).block()
        }
    }

    private fun Folk.me(i: Int) = game.players.values.first { it.accountId == acc[i].id }

    private suspend fun Folk.reach(i: Int, npc: String, topic: String): DialogView {
        val a = acc[i]
        val queue = ArrayDeque(listOf(emptyList<Pair<String, String?>>()))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            var d = game.talk(a, npc, "begin", null).dialog!!
            for ((t, arg) in path) d = game.talk(a, npc, t, arg).dialog!!
            d.options.firstOrNull { it.topic == topic }?.let { return game.talk(a, npc, it.topic, it.arg).dialog!! }
            if (path.size < 4) for (o in d.options) if (o.topic != "end" && seen.add(o.topic)) queue.addLast(path + (o.topic to o.arg))
        }
        error("no way to $topic")
    }

    @Test
    fun theFlag() = withFolk(seed = 1) {
        val (a, b) = acc
        game.tick(clock[0])
        for (x in acc.take(2)) game.place(x, Society.FLAG_START)
        game.view(b)
        var v = game.view(a)
        assertTrue(v.location.items.any { it.id == Society.FLAG && it.takeable })
        val hp = v.character.hpMax
        v = game.take(a, Society.FLAG)
        assertTrue(v.character.flag)
        assertEquals(hp + 10, v.character.hpMax)
        assertEquals(names[0], game.worldView().flagHolder)
        // The holder is fair game: striking him is no crime.
        clock[0] += 10
        val w = game.attackPlayer(b, names[0])
        assertNull(w.character.crime)
        // Dropped, it lies here again.
        clock[0] += 10
        v = game.drop(a, Society.FLAG)
        assertTrue(!v.character.flag)
        assertTrue(v.location.items.any { it.id == Society.FLAG })
        assertNull(game.worldView().flagHolder)
    }

    @Test
    fun marriage() = withFolk(seed = 2) {
        val lajma = game.world.allNpcs().first { it.key == "n.Lajma" }.location
        for (x in acc) { game.place(x, lajma); game.view(x) }
        for (i in 0..1) game.changeItem(me(i), Society.RING, 1)
        // He proposes.
        var d = reach(0, "n.Lajma", "svnow")
        val her = d.options.first { it.label == names[1] }
        d = game.talk(acc[0], "n.Lajma", her.topic, her.arg).dialog!!
        assertTrue(d.text.contains("ждите согласия"), d.text)
        // She accepts before witnesses.
        d = reach(1, "n.Lajma", "svnow")
        assertTrue(d.text.contains(names[0]), d.text)
        val yes = d.options.first { it.topic == "svok" }
        d = game.talk(acc[1], "n.Lajma", yes.topic, yes.arg).dialog!!
        assertTrue(d.text.contains("объявляю вас мужем и женой"), d.text)
        assertEquals(names[1], game.view(acc[0]).character.spouse)
        assertEquals(names[0], game.view(acc[1]).character.spouse)
        val look = assertNotNull(game.look(acc[2], names[0]).look)
        assertTrue(look.text.contains("Женат на ${names[1]}"), look.text)
        // Striking one's wife is no crime (f_attackf.dat:58).
        clock[0] += 10
        assertNull(game.attackPlayer(acc[0], names[1]).character.crime)
        // Divorce: 500 coins.
        game.changeItem(me(0), Rules.MONEY, 500)
        d = reach(0, "n.Lajma", "svnow")
        assertTrue(d.text.contains("Вы развелись"), d.text)
        assertNull(game.view(acc[0]).character.spouse)
        assertNull(game.view(acc[1]).character.spouse)
    }

    @Test
    fun tomrakBouquetsAndTheWorld() = withFolk(seed = 3) {
        val a = acc[0]
        val tomrak = game.world.allNpcs().first { it.key == "n.telemag" }.location
        game.place(a, tomrak)
        game.view(a)
        game.changeItem(me(0), Rules.MONEY, 150)
        val armor = game.view(a).character.armor
        val d = reach(0, "n.telemag", "kast1")
        assertEquals("Иди с миром", d.text)
        assertEquals(armor + 5, game.view(a).character.armor)

        // Two lilies and a chrysanthemum make a bouquet; a third kind joins it.
        game.changeItem(me(0), "i.bc.i", 2)
        game.changeItem(me(0), "i.bc.h", 1)
        game.changeItem(me(0), "i.bc.p", 3)
        clock[0] += 10
        var v = game.use(a, "i.bc.i", null, "i.bc.h")
        val bouquet = v.inventory.single { it.id.startsWith("i.bc_") }.id
        assertTrue(bouquet.endsWith("_i2h1"), bouquet)
        clock[0] += 10
        v = game.use(a, "i.bc.p", null, bouquet)
        assertTrue(v.inventory.any { it.id.endsWith("_i2h1p3") }, v.inventory.toString())

        val w = game.worldView()
        assertEquals(5, w.castles.size)
        assertTrue(w.online.any { it.name == names[0] })
        assertTrue(game.mapView().points.size > 1000)
    }
}
