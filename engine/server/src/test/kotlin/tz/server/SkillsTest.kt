package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.Errors
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Stealing, meditation, looking at things, quest items, the firebird feather, sharpening. Needs TZ_TEST_DATABASE_URL. */
class SkillsTest {
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
            fun login() = "s" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val names = listOf("Вор" + rnd(6), "Ротозей" + rnd(6))
            val acc = names.map { n ->
                val (a, _) = accounts.register(login(), "secret-123")
                accounts.createCharacter(a, n, "m")
                database.tx { c ->
                    c.prepareStatement("UPDATE characters SET str = 3, dex = 5, hp = ?, skills = '{\"steal\": 5, \"steallook\": 5, \"meditation\": 5}'::jsonb WHERE account_id = ?").use {
                        it.setInt(1, Rules.hpMax(3)); it.setLong(2, a.id); it.executeUpdate()
                    }
                }
                a
            }
            val clock = longArrayOf(1_800_000_000L)
            val game = Game(content, database, accounts, World(content, Random(seed), clock[0]), { clock[0] }, Random(seed))
            // A quiet ordinary place: no NPCs to interfere.
            val quiet = content.locations.values.first { l -> l.zone == 0 && game.world.npcsIn(l.id).isEmpty() && !l.id.startsWith("c.") && l.id != Rules.ARENA }.id
            for (a in acc) game.place(a, quiet)
            Two(game, acc[0], acc[1], names[0], names[1], clock).block()
        }
    }

    private fun Two.me(account: Account) = game.players.values.first { it.accountId == account.id }

    @Test
    fun stealingAndMeditation() = withTwo(seed = 1) {
        game.view(b)
        game.changeItem(me(b), Rules.MONEY, 100)
        var stolen = false
        repeat(40) {
            if (stolen) return@repeat
            clock[0] += 11
            game.view(b)
            var v = game.skill(a, "steal", bName, null)
            val peek = v.peek ?: return@repeat
            assertTrue(peek.items.any { it.id == Rules.MONEY && it.count == 100 }, peek.toString())
            clock[0] += 6
            v = game.skill(a, "steal", bName, Rules.MONEY)
            stolen = v.inventory.any { it.id == Rules.MONEY && it.count == 100 }
        }
        assertTrue(stolen)
        assertTrue(game.view(b).inventory.none { it.id == Rules.MONEY })
        assertEquals(1, me(a).stats[Stat.THEFTS])
        // Stealing without a fresh peek is always noticed: «вор».
        clock[0] += 120
        game.view(b)
        val v = game.skill(a, "steal", bName, Rules.STARTING_KNIFE)
        assertEquals("вор", v.character.crime)

        // Meditation: some mana back, sooner or later.
        me(a).mana = 0
        var gained = false
        repeat(20) {
            if (gained) return@repeat
            clock[0] += 6
            gained = game.skill(a, "meditation", null, null).character.mana > 0
        }
        assertTrue(gained)
    }

    @Test
    fun lookingAtThings() = withTwo(seed = 2) {
        game.view(b)
        var v = game.look(a, bName)
        val l = assertNotNull(v.look)
        assertTrue(l.text.contains("Искусный вор") && l.text.contains("Убил монстров: 0"), l.text)
        v = game.look(a, Rules.STARTING_KNIFE)
        assertTrue(v.look!!.text.contains("Урон: 0-2"), v.look!!.text)
        v = game.look(a, "i.w.k.begin-3-")
        assertTrue(v.look!!.text.contains("Заточен: +3"), v.look!!.text)
        v = game.look(a, "m.w.arrow")
        assertTrue(v.look!!.text.contains("Шанс"), v.look!!.text)
        v = game.look(a, "skill.steal")
        assertTrue(v.look!!.text.length > 20, v.look!!.text)
        assertEquals("Искусный", v.character.rank)
        assertEquals("вор", v.character.title)
    }

    @Test
    fun questItemsAndTheFeather() = withTwo(seed = 3) {
        game.view(a)
        game.changeItem(me(a), "i.q.kirka", 1)
        assertEquals(Errors.CANNOT_DROP, assertFailsWith<ApiException> { game.drop(a, "i.q.kirka") }.code)
        // One feather at most.
        game.changeItem(me(a), Game.FEATHER, 1)
        game.world.drop(me(a).location, Game.FEATHER, 1, clock[0])
        assertEquals(Errors.TOO_MANY, assertFailsWith<ApiException> { game.take(a, Game.FEATHER) }.code)
        // Death: the feather burns, the things stay.
        game.kill(a)
        val v = game.view(a)
        assertTrue(v.character.ghost)
        assertTrue(v.inventory.any { it.id == Rules.STARTING_KNIFE })
        assertTrue(v.inventory.none { it.id == Game.FEATHER })
        assertTrue(v.location.corpses.none { it.name.endsWith(aName) })
        assertEquals(1, me(a).stats[Stat.DEATHS])
    }

    @Test
    fun sharpeningAndSets() {
        val content = Content.load(contentDir())
        fun stats(vararg eq: String) = Formulas.player(Skills.of(3, 3, 1), eq.toList(), { content.items[it] })
        val plain = stats("i.w.k.begin")
        val sharp = stats("i.w.k.begin-3-")
        assertEquals(plain.dmgMin + 3, sharp.dmgMin)
        assertEquals(plain.dmgMax + 3, sharp.dmgMax)
        assertEquals(plain.dmgMax + 6, stats("i.w.k.begin-9-").dmgMax)
    }
}
