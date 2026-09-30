package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.DialogView
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pets, horses, mercenaries, taming, summoning, necromancy. Needs TZ_TEST_DATABASE_URL. */
class PetsTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? by lazy { System.getenv("TZ_TEST_DATABASE_URL")?.let { Db.fromUrl(it).also(Db::migrate) } }
    }

    private class One(val game: Game, val a: Account, val name: String, val clock: LongArray)

    private fun withOne(seed: Int, skills: String, block: suspend One.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            val login = "p" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val name = "Хозяин" + (1..6).map { ('а'..'я').random() }.joinToString("")
            val (a, _) = accounts.register(login, "secret-123")
            accounts.createCharacter(a, name, "m")
            database.tx { c ->
                c.prepareStatement("UPDATE characters SET str = 4, dex = 4, intel = 5, hp = ?, mana = ?, skills = ?::jsonb WHERE account_id = ?").use {
                    it.setInt(1, Rules.hpMax(4)); it.setInt(2, Rules.manaMax(5)); it.setString(3, skills); it.setLong(4, a.id); it.executeUpdate()
                }
            }
            val clock = longArrayOf(1_800_000_000L)
            val game = Game(content, database, accounts, World(content, Random(seed), clock[0]), { clock[0] }, Random(seed))
            One(game, a, name, clock).block()
        }
    }

    private fun One.me() = game.players.values.first { it.accountId == a.id }

    /** Walks a dialog from «begin» through the options until [topic] is offered, then picks it. */
    private suspend fun One.reach(npc: String, topic: String): DialogView {
        var d = game.talk(a, npc, "begin", null).dialog!!
        val seen = HashSet<String>()
        repeat(6) {
            d.options.firstOrNull { it.topic == topic }?.let { return game.talk(a, npc, it.topic, it.arg).dialog!! }
            val next = d.options.firstOrNull { it.topic !in seen && it.topic != "end" } ?: error("no way to $topic: ${d.options}")
            seen += next.topic
            d = game.talk(a, npc, next.topic, next.arg).dialog!!
        }
        error("no way to $topic")
    }

    @Test
    fun boughtPetsFollowAndHorsesCarry() = withOne(seed = 1, skills = "{}") {
        val shop = game.world.allNpcs().first { it.key == "n.Milta" }.location
        game.place(a, shop)
        game.view(a)
        game.changeItem(me(), Rules.MONEY, 500)
        var d = reach("n.Milta", "dog")
        assertTrue(d.text.contains("собака"), d.text)
        val dog = game.world.npcsIn(shop).single { it.key == "n.a.dog.${me().id}" }
        assertTrue(game.view(a).location.npcs.single { it.id == dog.key }.mine)
        // A second one here is refused.
        d = reach("n.Milta", "pig")
        assertTrue(d.text.contains("уже есть купленное здесь животное"), d.text)

        // The dog follows into the next location.
        val there = shop.let { l -> content.locations.getValue(l).exits.first { it.target != l }.target }
        game.move(a, there)
        clock[0] += 1
        game.tick(clock[0])
        assertEquals(there, dog.location)
        // «Стой здесь»: it stays.
        var pd = game.talk(a, dog.key, "begin", null).dialog!!
        assertTrue(pd.options.any { it.topic == "free" })
        game.talk(a, dog.key, "stay", null)
        game.move(a, shop)
        clock[0] += 1
        game.tick(clock[0])
        assertEquals(there, dog.location)

        // A horse: buy it (the dog is not here any more), ride, get off — it is yours for an hour.
        game.place(a, there)
        game.talk(a, dog.key, "begin", null)
        game.talk(a, dog.key, "free", null)
        game.place(a, shop)
        reach("n.Milta", "losh")
        val horse = game.world.npcsIn(shop).single { it.key == "n.a.losh.${me().id}" }
        game.talk(a, horse.key, "begin", null)
        pd = game.talk(a, horse.key, "sedlo", null).dialog!!
        assertTrue(pd.text.contains("всадник"), pd.text)
        var v = game.view(a)
        assertTrue(v.character.mounted)
        assertTrue(v.location.npcs.none { it.id == horse.key })
        clock[0] += 10
        v = game.skill(a, "dismount", null, null)
        assertTrue(!v.character.mounted)
        val back = v.location.npcs.single { it.id.startsWith("n.a.losh.") && it.mine }
        assertNotNull(back)
        assertTrue(game.world.npcsIn(shop).single { it.key == back.id }.owner!!.until > clock[0])
    }

    @Test
    fun tamingSummoningAndTheDead() = withOne(seed = 2, skills = """{"animaltaming": 5, "animallore": 5, "magic": 5, "necro": 5}""") {
        // Taming: an easy animal sooner or later follows you.
        val animal = game.world.allNpcs().first { n ->
            n.key.startsWith("n.a.") && n.owner == null &&
                ((content.npcs[n.proto.template]?.get("char") as? kotlinx.serialization.json.JsonObject)?.int("tame_difficulty") ?: 0) == 1
        }
        game.place(a, animal.location)
        var tamed = false
        repeat(20) {
            if (tamed) return@repeat
            clock[0] += 6
            game.skill(a, "animaltaming", animal.key, null)
            tamed = animal.owner?.ownerId == me().id
        }
        assertTrue(tamed)
        assertEquals(1, me().statistics[Stat.TAMED])

        // Summoning a wolf.
        me().known += "m.s.wolf"
        var wolf: World.Npc? = null
        repeat(15) {
            if (wolf != null) return@repeat
            clock[0] += 700
            me().mana = me().manaMax
            game.cast(a, "m.s.wolf", null)
            wolf = game.world.npcsIn(me().location).firstOrNull { it.key.startsWith("n.s.wolf.") && it.owner?.ownerId == me().id }
        }
        assertNotNull(wolf)

        // Necromancy: a dead animal rises as a zombie.
        val victim = game.world.allNpcs().first { it.key.startsWith("n.a.") && it.owner == null && it.location != me().location }
        game.place(a, victim.location)
        game.world.kill(victim, clock[0])
        val corpse = game.view(a).location.corpses.first { it.canRaise }
        var zombie: World.Npc? = null
        repeat(25) {
            if (zombie != null) return@repeat
            clock[0] += 11
            me().mana = me().manaMax
            game.skill(a, "necro", corpse.id, null)
            zombie = game.world.npcsIn(me().location).firstOrNull { it.key.startsWith("n.z.") && it.owner?.ownerId == me().id }
        }
        assertTrue(assertNotNull(zombie).name.endsWith("-зомби"))
    }

    @Test
    fun mercenaries() = withOne(seed = 3, skills = "{}") {
        val barracks = game.world.allNpcs().first { it.key == "n.Markus" }.location
        game.place(a, barracks)
        game.view(a)
        game.changeItem(me(), Rules.MONEY, 500)
        var d = reach("n.Markus", "get1")
        assertTrue(d.text.contains("теперь в твоем распоряжении"), d.text)
        val merc = game.world.npcsIn(barracks).single { it.key == "n.kastle1.${me().id}" }
        assertTrue(merc.name.endsWith("[наемник]"))
        assertEquals(410, game.view(a).inventory.single { it.id == Rules.MONEY }.count)
        d = reach("n.Markus", "get2")
        assertTrue(d.text.contains("уже есть наемник воин"), d.text)
    }
}
