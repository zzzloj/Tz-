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
import kotlin.test.assertTrue

/** Fights through [Game] with a fake clock. Needs TZ_TEST_DATABASE_URL like AccountsApiTest. */
class CombatTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? get() = TestDb.db
    }

    private val start = 1_800_000_000L

    private class Setup(val game: Game, val world: World, val accounts: Accounts, val account: Account, val clock: LongArray)

    private fun withGame(seed: Int, str: Int = 1, dex: Int = 1, block: suspend Setup.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            val login = "c" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val (account, _) = accounts.register(login, "secret-123")
            accounts.createCharacter(account, "Боец" + (1..6).map { ('а'..'я').random() }.joinToString(""), "m")
            database.tx { c ->
                c.prepareStatement("UPDATE characters SET str = ?, dex = ?, hp = ? WHERE account_id = ?").use {
                    it.setInt(1, str); it.setInt(2, dex); it.setInt(3, Rules.hpMax(str)); it.setLong(4, account.id); it.executeUpdate()
                }
            }
            val clock = longArrayOf(start)
            val world = World(content, Random(seed), start)
            val game = Game(content, database, accounts, world, { clock[0] }, Random(seed))
            Setup(game, world, accounts, account, clock).block()
        }
    }

    /** Places the character next to a fresh NPC chosen by [pick] and returns the NPC. */
    private suspend fun Setup.meet(pick: (List<World.Npc>) -> World.Npc): World.Npc {
        val npc = pick(world.allNpcs())
        game.place(account, npc.location)
        game.equip(account, "i.w.k.begin")
        return npc
    }

    @Test
    fun killAnAnimalLootAndButcher() = withGame(seed = 11, str = 5, dex = 5) {
        // A wild animal (passive until struck) that can be butchered.
        val npc = meet { all ->
            all.filter { it.key.startsWith("n.a.") && it.proto.butcher.isNotEmpty() && it.proto.wander == null }
                .minBy { it.proto.hpMax * 10 + it.stats.dmgMax }
        }
        val npcKey = npc.key
        var view: GameView = game.view(account)
        var rounds = 0
        while (view.location.npcs.any { it.id == npcKey } && !view.character.ghost && rounds < 500) {
            clock[0] += 10
            view = game.attack(account, npcKey)
            rounds++
        }
        assertTrue(!view.character.ghost, "a strong character should beat ${npc.name}: ${view.journal.takeLast(5)}")
        assertTrue(view.location.npcs.none { it.id == npcKey }, "not dead after $rounds rounds")
        assertTrue(view.journal.any { "погибает" in it }, view.journal.toString())
        val corpse = view.location.corpses.single { it.name == "труп: ${npc.name}" }
        assertTrue(corpse.canButcher)
        val before = view.inventory.sumOf { it.count }
        view = game.butcher(account, corpse.id)
        assertTrue(view.inventory.sumOf { it.count } > before, "butchering gave nothing")
        assertTrue(view.location.corpses.none { it.id == corpse.id && it.canButcher })
        // Rest: a second blow right away is refused.
        val next = world.allNpcs().firstOrNull { Rules.attackable(it.key) && it.location == view.character.location }
        if (next != null) {
            clock[0] += 10
            game.attack(account, next.key)
            assertEquals(Errors.RESTING, assertFailsWith<ApiException> { game.attack(account, next.key) }.code)
        }
    }

    @Test
    fun strikingTheInnocentCallsTheGuard() = withGame(seed = 12) {
        val npc = meet { all -> all.first { it.key == "n.beginner" } }
        assertEquals(Errors.NO_TARGET, assertFailsWith<ApiException> { game.attack(account, "n.c.nobody") }.code)
        clock[0] += 10
        val v = game.attack(account, npc.key)
        assertEquals("бандит", v.character.crime)
        assertEquals(30L, v.character.crimeMinutes)
        // The start street is guarded (zone 1): a city guard comes for the criminal.
        clock[0] += 1
        game.tick(clock[0])
        val guard = game.world.npcsIn(npc.location).firstOrNull { it.key.startsWith("n.g.") }
        assertNotNull(guard, "a guard appears")
        assertTrue(guard.name.endsWith("[стража]"))
        // After 30 minutes online the crime is over and the guard (10 minutes) is gone.
        repeat(6) { clock[0] += 300; game.view(account) }
        clock[0] += 1
        game.tick(clock[0])
        assertEquals(null, game.view(account).character.crime)
        assertTrue(game.world.npcsIn(npc.location).none { it.key.startsWith("n.g.") })
    }

    @Test
    fun deathGhostCorpseAndResurrection() = withGame(seed = 13) {
        // The hardest-hitting monster standing still: it attacks on sight.
        val monster = meet { all ->
            all.filter { it.key.startsWith("n.c.") && it.proto.wander == null && it.stats.hit >= 50 }
                .maxBy { it.stats.dmgMin * 1000 + it.proto.hpMax }
        }
        val here = monster.location
        var view = game.view(account)
        var seconds = 0
        while (!view.character.ghost && seconds < 600) {
            clock[0] += 1
            game.tick(clock[0])
            view = game.view(account)
            seconds++
        }
        assertTrue(view.character.ghost, "${monster.name} never killed a 20 HP newbie: ${view.journal.takeLast(5)}")
        assertEquals(0, view.character.hp)
        assertTrue(view.inventory.isEmpty(), "everything goes into the corpse")
        val corpse = view.location.corpses.single { it.name.startsWith("труп: ") && it.items.any { i -> i.id == "i.w.k.begin" } }
        assertEquals(here, view.character.location)
        // Ghosts cannot take or fight, and monsters leave them alone.
        assertEquals(Errors.GHOST, assertFailsWith<ApiException> { game.loot(account, corpse.id, "i.w.k.begin") }.code)
        assertEquals(Errors.GHOST, assertFailsWith<ApiException> { game.attack(account, monster.key) }.code)
        val hpOfGhost = view.character.hp
        repeat(30) { clock[0] += 1; game.tick(clock[0]) }
        assertEquals(hpOfGhost, game.view(account).character.hp)
        // Not here: there is no resurrection stone or healer.
        if (!view.canResurrect)
            assertEquals(Errors.NO_RESURRECTION_HERE, assertFailsWith<ApiException> { game.resurrect(account) }.code)

        // The healer's yard north of the start (n.h.Djozef) brings the ghost back with 0 HP, as in the old game.
        val healer = world.allNpcs().first { it.key.startsWith("n.h.") }
        game.place(account, healer.location)
        view = game.view(account)
        assertTrue(view.canResurrect)
        view = game.resurrect(account)
        assertTrue(!view.character.ghost)
        assertEquals(0, view.character.hp)
        // Health comes back by itself: +1 after 31 s without blows.
        clock[0] += 31
        game.tick(clock[0])
        assertEquals(1, game.view(account).character.hp)
        assertEquals(Errors.NOT_GHOST, assertFailsWith<ApiException> { game.resurrect(account) }.code)

        // Back to the corpse for the knife.
        game.place(account, here)
        view = game.loot(account, corpse.id, "i.w.k.begin")
        assertNotNull(view.inventory.firstOrNull { it.id == "i.w.k.begin" })
    }

    @Test
    fun anNpcFightsEveryoneWhoStruckIt() = withGame(seed = 14) {
        val cow = meet { all -> all.first { it.key == "n.a.cow.krest" } }
        val (second, _) = accounts.register("c" + (1..10).map { ('a'..'z').random() }.joinToString(""), "secret-123")
        accounts.createCharacter(second, "Второй" + (1..6).map { ('а'..'я').random() }.joinToString(""), "f")
        game.place(second, cow.location)

        assertTrue(game.attack(account, cow.key).location.npcs.single { it.id == cow.key }.fightingYou)
        assertTrue(game.attack(second, cow.key).location.npcs.single { it.id == cow.key }.fightingYou)
        assertTrue(game.view(account).location.npcs.single { it.id == cow.key }.fightingYou, "the first attacker is not forgotten")

        // Its own blows go round both attackers.
        repeat(40) {
            clock[0] += 1
            game.tick(clock[0])
            game.view(account); game.view(second)   // both stay active
        }
        val first = game.view(account).journal
        val other = game.view(second).journal
        assertTrue(first.any { it.startsWith("${cow.name} по вам") }, first.toString())
        assertTrue(other.any { it.startsWith("${cow.name} по вам") }, other.toString())
    }
}
