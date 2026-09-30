package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walking, locked passages, the boat, chases, poison, regeneration, coming back after a break. Needs TZ_TEST_DATABASE_URL. */
class TravelTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? by lazy { System.getenv("TZ_TEST_DATABASE_URL")?.let { Db.fromUrl(it).also(Db::migrate) } }
    }

    private class Two(val game: Game, val a: Account, val b: Account, val aName: String, val bName: String, val clock: LongArray)

    private fun rnd(n: Int) = (1..n).map { ('а'..'я').random() }.joinToString("")

    private fun withTwo(seed: Int, block: suspend Two.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            fun login() = "t" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val names = listOf("Путник" + rnd(6), "Спутник" + rnd(6))
            val acc = names.map { n ->
                val (a, _) = accounts.register(login(), "secret-123")
                accounts.createCharacter(a, n, "m")
                database.tx { c ->
                    c.prepareStatement("UPDATE characters SET str = 4, dex = 4, hp = ? WHERE account_id = ?").use {
                        it.setInt(1, Rules.hpMax(4)); it.setLong(2, a.id); it.executeUpdate()
                    }
                }
                a
            }
            val clock = longArrayOf(1_800_000_000L)
            val game = Game(content, database, accounts, World(content, Random(seed), clock[0]), { clock[0] }, Random(seed))
            for (a in acc) game.place(a, "x772x113")
            Two(game, acc[0], acc[1], names[0], names[1], clock).block()
        }
    }

    private fun Two.me(account: Account) = game.players.values.first { it.accountId == account.id }

    @Test
    fun walkingLockedPassagesAndTheBoat() = withTwo(seed = 1) {
        game.view(b)
        // Leaving is seen by those who stay; entering a guarded street is announced.
        var v = game.move(a, "x776x141")
        assertEquals("x776x141", v.character.location)
        assertTrue(v.journal.last() == "Вы на охраняемой территории", v.journal.toString())
        assertTrue(game.view(b).journal.any { it == "$aName ушёл к аванпосту" }, game.view(b).journal.toString())
        // Somebody (b) is behind the exit back: «звуки».
        assertTrue(v.location.exits.first { it.target == "x772x113" }.occupied)

        // The castle gate of Ditrih stays shut, as in the old game.
        game.place(a, "x927x253")
        v = game.move(a, "x902x254")
        assertEquals("x927x253", v.character.location)
        assertEquals("Стражник: Стой!", v.journal.last())

        // The boat asks where to, sails, and comes along.
        game.world.placePermanent("x1103x618", Travel.BOAT, 1)
        game.place(a, "x1103x618")
        v = game.take(a, Travel.BOAT)
        assertEquals(3, assertNotNull(v.choice).options.size)
        v = game.take(a, Travel.BOAT, "3")
        assertEquals("x672x336", v.character.location)
        assertTrue(v.location.items.any { it.id == Travel.BOAT && it.takeable })
        assertTrue(game.world.itemsAt("x1103x618", clock[0]).none { it.id == Travel.BOAT })
        // Back to the pier: the boat leaks again.
        v = game.take(a, Travel.BOAT, "1")
        assertEquals("x1103x618", v.character.location)
        assertTrue(v.location.items.any { it.id == Travel.BROKEN_BOAT })
    }

    @Test
    fun chasePoisonAndRegeneration() = withTwo(seed = 2) {
        // A passive animal that stays at home, next to a location of the same zone.
        val (animal, next) = game.world.allNpcs().filter { it.key.startsWith("n.a.") && it.proto.wander == null && it.key != Travel.FIREBIRD }
            .firstNotNullOf { n ->
                val here = content.locations[n.location] ?: return@firstNotNullOf null
                here.exits.map { it.target }.firstOrNull { t -> t != n.location && content.locations[t]?.zone == here.zone }?.let { n to it }
            }
        game.place(a, animal.location)
        var chased = false
        repeat(6) {
            if (chased) return@repeat
            game.place(a, animal.location)
            clock[0] += 30
            me(a).hp = me(a).hpMax
            game.attack(a, animal.key)
            if (animal.hp < 1) return@repeat
            game.move(a, next)
            clock[0] += 1
            game.tick(clock[0])
            chased = animal.location == next
        }
        assertTrue(chased, "the animal should follow its attacker")

        // Poison: the bottle poisons, health drips away but never below 1; the antidote helps.
        val quiet = content.locations.values.first { l -> l.zone == 0 && game.world.npcsIn(l.id).isEmpty() && !l.id.startsWith("c.") && l.id != Rules.ARENA }.id
        game.place(a, quiet); game.place(b, quiet)
        game.view(b)
        game.changeItem(me(a), "i.b.jad.c", 1)
        clock[0] += 60
        var v = game.use(a, "i.b.jad.c", null, bName)
        assertEquals("бандит", v.character.crime)
        assertTrue(game.view(b).character.poisoned)
        me(b).hp = 20
        me(b).regenFrom = clock[0]
        clock[0] += 60
        game.tick(clock[0])
        assertEquals(14, me(b).hp)
        game.changeItem(me(b), "i.b.antidot", 1)
        v = game.use(b, "i.b.antidot", null, null)
        assertTrue(!v.character.poisoned)

        // Regeneration skill 5: +round(31/10) a tick instead of +1.
        me(b).other["regeneration"] = 5
        me(b).hp = 1
        me(b).regenFrom = clock[0]
        clock[0] += 31
        game.tick(clock[0])
        assertEquals(4, me(b).hp)
    }

    @Test
    fun backAfterABreak() = withTwo(seed = 3) {
        game.view(a)
        game.place(a, Rules.ARENA)
        me(a).crime = "бандит"
        me(a).crimeUntil = clock[0] + 600
        clock[0] += 3600
        val v = game.view(a)
        assertEquals("x1229x582", v.character.location)
        assertEquals("бандит", v.character.crime)
        assertTrue(v.character.crimeMinutes in 9..10)
        assertNull(v.choice)
    }
}
