package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.Errors
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Players fighting players: crimes, murder, looting, bounties, the arena. Needs TZ_TEST_DATABASE_URL. */
class PvpTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? by lazy { System.getenv("TZ_TEST_DATABASE_URL")?.let { Db.fromUrl(it).also(Db::migrate) } }
    }

    private fun rnd(n: Int) = (1..n).map { ('а'..'я').random() }.joinToString("")

    private class Three(val game: Game, val a: Account, val b: Account, val c: Account, val names: List<String>, val clock: LongArray)

    private fun withThree(block: suspend Three.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            fun login() = "v" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val names = listOf("Злодей" + rnd(5), "Жертва" + rnd(5), "Мститель" + rnd(5))
            val acc = names.mapIndexed { i, n ->
                val (a, _) = accounts.register(login(), "secret-123")
                accounts.createCharacter(a, n, "m")
                // The villain and the avenger are strong, the victim is a newbie.
                val str = if (i == 1) 1 else 5
                database.tx { c ->
                    c.prepareStatement("UPDATE characters SET str = ?, dex = 5, hp = ? WHERE account_id = ?").use {
                        it.setInt(1, str); it.setInt(2, Rules.hpMax(str)); it.setLong(3, a.id); it.executeUpdate()
                    }
                }
                a
            }
            val clock = longArrayOf(1_800_000_000L)
            val game = Game(content, database, accounts, World(content, Random(4), clock[0]), { clock[0] }, Random(4))
            for (a in acc) game.place(a, "x1141x506")
            Three(game, acc[0], acc[1], acc[2], names, clock).block()
        }
    }

    private suspend fun Three.fightUntilDead(attacker: tz.server.Account, victim: String) {
        repeat(200) {
            clock[0] += 10
            val v = game.attackPlayer(attacker, victim)
            if (v.people.first { it.name == victim }.ghost) return
        }
        error("$victim did not die")
    }

    @Test
    fun murderLootingAndBounty() = withThree {
        val (villain, victim, avenger) = names
        game.view(b); game.view(c)
        assertEquals(Errors.NO_SUCH_PLAYER, assertFailsWith<ApiException> { game.attackPlayer(a, "Никто") }.code)
        // First swing at an innocent: bandit.
        clock[0] += 10
        var v = game.attackPlayer(a, victim)
        assertEquals("бандит", v.character.crime)
        assertEquals("бандит", game.view(b).people.first { it.name == villain }.crime)
        fightUntilDead(a, victim)
        v = game.view(a)
        assertEquals("убийца", v.character.crime)
        // The victim's corpse holds the knife; for the avenger taking it is looting.
        val corpse = v.location.corpses.first { it.name == "труп: $victim" }
        assertTrue(corpse.looting)
        val w = game.loot(c, corpse.id, Rules.STARTING_KNIFE)
        assertEquals("мародер", w.character.crime)

        // The victim, killed by one who already was a criminal, may put a price on his head.
        game.kill(c) // the avenger's own crime aside: a fresh character state for him below
        game.place(b, "x1092x474")
        var d = game.talk(b, "n.officer", "begin", null).dialog!!
        val place = d.options.first { it.label.startsWith("Я хочу назначить награду") }
        db!!.tx { conn -> conn.prepareStatement("INSERT INTO character_items (character_id, item_id, count) SELECT id, 'i.money', 300 FROM characters WHERE account_id = ?").use { it.setLong(1, b.id); it.executeUpdate() } }
        d = game.talk(b, "n.officer", place.topic, null).dialog!!
        d = game.talk(b, "n.officer", d.inputTopic!!, "250").dialog!!
        assertTrue(d.text.contains("увеличена на 250"), d.text)
        d = game.talk(b, "n.officer", "begin", null).dialog!!
        d = game.talk(b, "n.officer", d.options.first { it.label.startsWith("Я хочу посмотреть список") }.topic, null).dialog!!
        assertTrue(d.text.contains("$villain — 250 монет"), d.text)
    }

    @Test
    fun noFightingInTheBankAndArenaKeepsThings() = withThree {
        val (_, victim, _) = names
        game.place(a, Rules.BANK_LOCATION); game.place(b, Rules.BANK_LOCATION)
        assertEquals(Errors.NO_FIGHT_HERE, assertFailsWith<ApiException> { game.attackPlayer(a, victim) }.code)
        // On the arena nobody becomes a criminal and the fallen keep their things.
        game.place(a, Rules.ARENA); game.place(b, Rules.ARENA)
        game.view(b)
        fightUntilDead(a, victim)
        val v = game.view(a)
        assertEquals(null, v.character.crime)
        assertTrue(v.location.corpses.isEmpty())
        assertTrue(game.view(b).inventory.any { it.id == Rules.STARTING_KNIFE })
        // The ghost leaves by the exit stone; the winner, now alone, too.
        assertEquals(Rules.ARENA_EXIT, game.take(b, "i.s.arena").character.location)
        assertEquals(Rules.ARENA_EXIT, game.take(a, "i.s.arena").character.location)
    }
}
