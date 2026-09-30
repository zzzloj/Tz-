package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spells, scrolls, runes, techniques and stances. Needs TZ_TEST_DATABASE_URL. */
class MagicTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? by lazy { System.getenv("TZ_TEST_DATABASE_URL")?.let { Db.fromUrl(it).also(Db::migrate) } }
    }

    private class Two(val game: Game, val a: Account, val b: Account, val aName: String, val bName: String, val clock: LongArray, val db: Db)

    private fun rnd(n: Int) = (1..n).map { ('а'..'я').random() }.joinToString("")

    private fun withTwo(seed: Int, block: suspend Two.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            fun login() = "m" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val names = listOf("Маг" + rnd(6), "Воин" + rnd(6))
            val acc = names.map { n ->
                val (a, _) = accounts.register(login(), "secret-123")
                accounts.createCharacter(a, n, "m")
                database.tx { c ->
                    c.prepareStatement("UPDATE characters SET str = 3, dex = 4, intel = 5, hp = ?, mana = ?, skills = '{\"magic\": 5, \"coldweapon\": 5}'::jsonb WHERE account_id = ?").use {
                        it.setInt(1, Rules.hpMax(3)); it.setInt(2, Rules.manaMax(5)); it.setLong(3, a.id); it.executeUpdate()
                    }
                }
                a
            }
            val clock = longArrayOf(1_800_000_000L)
            val game = Game(content, database, accounts, World(content, Random(seed), clock[0]), { clock[0] }, Random(seed))
            for (a in acc) game.place(a, "x1141x506")
            Two(game, acc[0], acc[1], names[0], names[1], clock, database).block()
        }
    }

    private fun Two.me(account: Account) = game.players.values.first { it.accountId == account.id }

    private suspend fun Two.learn(account: Account, vararg ids: String) {
        game.view(account)
        me(account).known += ids
    }

    private suspend fun Two.give(account: Account, item: String, n: Int = 1) {
        game.view(account)
        game.changeItem(me(account), item, n)
    }

    /** Tries [action] (a cast that may fizzle) until [done], moving the clock past rest and period each time. */
    private suspend fun Two.retry(step: Long, done: (GameView) -> Boolean, action: suspend () -> GameView): GameView {
        repeat(15) {
            clock[0] += step
            me(a).mana = me(a).manaMax
            val v = action()
            if (done(v)) return v
        }
        error("did not happen")
    }

    @Test
    fun spellsScrollsAndRunes() = withTwo(seed = 3) {
        assertEquals(Errors.UNKNOWN_ABILITY, assertFailsWith<ApiException> { game.cast(a, "m.w.arrow", bName) }.code)
        learn(a, "m.w.arrow", "m.heal", "m.mark", "m.recall")
        var v = game.view(a)
        val arrow = v.abilities.single { it.id == "m.w.arrow" }
        assertEquals("creature", arrow.target)
        assertEquals(5, arrow.manaCost)

        // A battle spell at a monster: mana goes, the words sound, the period starts.
        val rat = game.world.allNpcs().first { it.key.startsWith("n.a.") && it.proto.wander == null && it.hp > 10 }
        game.place(a, rat.location)
        clock[0] += 60
        val manaBefore = game.view(a).character.mana
        v = game.cast(a, "m.w.arrow", rat.key)
        assertEquals(manaBefore - 5, v.character.mana)
        assertTrue(v.journal.any { it.endsWith(": In Ost") }, v.journal.toString())
        assertTrue(v.abilities.single { it.id == "m.w.arrow" }.readyIn > 0)
        clock[0] += 30
        v = game.cast(a, "m.w.arrow", rat.key)
        assertTrue(v.journal.last().startsWith("Период «Магическая стрела» не истек"), v.journal.last())
        v = retry(61, { view -> view.journal.any { it.contains("по ${rat.name} ") && it.contains("магией") } }) { game.cast(a, "m.w.arrow", rat.key) }
        assertTrue(me(a).id in rat.enemies, "the struck animal turns on the caster")

        // Healing yourself.
        me(a).hp = 3
        v = retry(181, { view -> view.character.hp > 3 }) { game.cast(a, "m.heal", aName) }
        assertTrue(v.journal.any { it.startsWith("Жизнь +") })

        // A scroll: no need to know the spell, and it burns.
        give(a, "i.m.heal", 2)
        val scroll = game.view(a).inventory.single { it.id == "i.m.heal" }
        assertTrue(scroll.usable)
        assertEquals("creature_self", scroll.target)
        me(a).hp = 3
        v = retry(15, { view -> view.character.hp > 3 }) { game.use(a, "i.m.heal", null, aName) }
        assertTrue((v.inventory.firstOrNull { it.id == "i.m.heal" }?.count ?: 0) < 2)

        // Mark an empty rune here, walk away, come back by it.
        val home = "x1141x506"
        game.place(a, home)
        give(a, Spells.EMPTY_RUNE)
        retry(70, { view -> view.inventory.any { it.id == "i.rr.$home" } }) { game.cast(a, "m.mark", Spells.EMPTY_RUNE) }
        game.place(a, Rules.ARENA_EXIT)
        v = retry(130, { view -> view.character.location == home }) { game.cast(a, "m.recall", "i.rr.$home") }
        assertTrue(v.journal.any { it.startsWith("Вы исчезаете в клубах серого дыма") })

        // Without the magic skill nothing works, and no mana is spent.
        me(a).other["magic"] = 0
        clock[0] += 200
        val mana = game.view(a).character.mana
        v = game.cast(a, "m.heal", aName)
        assertEquals("Слишком слабый навык магии", v.journal.last())
        assertEquals(mana, v.character.mana)
    }

    @Test
    fun techniquesAndStances() = withTwo(seed = 5) {
        learn(a, "p.m", "p.n", "p.d.o")
        learn(b, "p.d.re", "p.d.o")
        game.equip(a, Rules.STARTING_KNIFE)
        me(a).int = 1   // intelligence spoils the aim of techniques
        game.view(b)

        // A guard stance: «реакция» goes with the first physical blow at its owner.
        var w = game.technique(b, "p.d.re", null)
        assertNotNull(w.stance)
        clock[0] += 10
        var v = game.attackPlayer(a, bName)
        assertEquals("бандит", v.character.crime)
        assertNull(game.view(b).stance)

        // A technique: its name in the journal, then its period.
        clock[0] += 10
        me(b).hp = me(b).hpMax
        v = game.technique(a, "p.m", bName)
        assertTrue(v.journal.any { it.startsWith("Вы (меткий удар) по $bName") }, v.journal.toString())
        clock[0] += 10
        v = game.technique(a, "p.m", bName)
        assertTrue(v.journal.last().startsWith("Период «меткий удар» не истек"), v.journal.last())

        // Stunning blow: sooner or later the target is stunned for 15 s.
        var stunned = false
        repeat(20) {
            if (stunned) return@repeat
            clock[0] += 901
            game.view(b)   // still online
            me(b).hp = me(b).hpMax
            me(a).hp = me(a).hpMax
            v = game.technique(a, "p.n", bName)
            if (v.journal.any { it == "$bName оглушен!" }) stunned = true
        }
        assertTrue(stunned, v.journal.toString())
        assertTrue(game.view(b).restSeconds >= 14)

        // «Глухая оборона»: the owner rests the whole time and cannot pick things up.
        clock[0] += 60
        w = game.technique(b, "p.d.o", null)
        assertTrue(w.restSeconds >= 59)
        assertTrue(w.stance!!.startsWith("глухая оборона"))

        // Unknown techniques are refused.
        assertEquals(Errors.UNKNOWN_ABILITY, assertFailsWith<ApiException> { game.technique(a, "p.me", bName) }.code)
    }
}
