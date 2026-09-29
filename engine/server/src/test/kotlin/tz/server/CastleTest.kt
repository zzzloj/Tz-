package tz.server

import kotlinx.coroutines.runBlocking
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Taking, guarding and locking a castle without fighting. Needs TZ_TEST_DATABASE_URL. */
class CastleTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? by lazy { System.getenv("TZ_TEST_DATABASE_URL")?.let { Db.fromUrl(it).also(Db::migrate) } }
    }

    private fun rnd(n: Int) = (1..n).map { ('а'..'я').random() }.joinToString("")

    @Test
    fun takeGuardLockAndOpen() {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            // Castle 1 starts free in every test run.
            database.tx { c -> c.createStatement().use { it.execute("UPDATE castles SET clan_id = NULL, locked_until = 0, open_until = 0 WHERE id = 1; DELETE FROM castle_guards WHERE castle_id = 1") } }
            val accounts = Accounts(database, content)
            fun login() = "k" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val (a, _) = accounts.register(login(), "secret-123")
            val (b, _) = accounts.register(login(), "secret-123")
            val aName = "Рыцарь" + rnd(5); val bName = "Чужак" + rnd(5)
            accounts.createCharacter(a, aName, "m"); accounts.createCharacter(b, bName, "m")
            val clan = "Замковые" + rnd(3)
            database.tx { c ->
                val id = c.prepareStatement("INSERT INTO clans (name) VALUES (?) RETURNING id").use { st -> st.setString(1, clan); st.executeQuery().use { it.next(); it.getLong(1) } }
                c.prepareStatement("INSERT INTO clan_members (character_id, clan_id, rank) SELECT id, ?, 'head' FROM characters WHERE account_id = ?").use { st -> st.setLong(1, id); st.setLong(2, a.id); st.executeUpdate() }
                c.prepareStatement("INSERT INTO character_items (character_id, item_id, count) SELECT id, 'i.money', 1000 FROM characters WHERE account_id = ?").use { st -> st.setLong(1, a.id); st.executeUpdate() }
            }
            val clock = longArrayOf(1_800_000_000L)
            val game = Game(content, database, accounts, World(content, Random(3), clock[0]), { clock[0] }, Random(3))

            // Nobody's castle: a clan member walking into the gate takes it.
            game.place(a, "c.1.in")
            var v = game.view(a)
            assertEquals(null, assertNotNull(v.castle).owner)
            v = game.move(a, "c.1.gate")
            assertEquals("c.1.gate", v.character.location)
            assertEquals(clan, v.castle!!.owner)
            assertTrue(v.journal.any { it.startsWith("Вы захватили замок") })

            // A stranger knocks; walking in while an owner is inside is an attack, not a capture.
            game.place(b, "c.1.in")
            game.castle(b, "knock", null)
            assertTrue(game.view(a).journal.any { it.contains("стучит в ворота") })
            var w = game.move(b, "c.1.gate")
            assertEquals("c.1.gate", w.character.location)
            assertEquals(clan, w.castle!!.owner)
            assertTrue(game.view(a).journal.any { it.startsWith("На ваш замок") })
            game.place(b, "c.1.in")

            // A guard for the castle; with the owner away it attacks the stranger who walks in.
            val p = game.players.values.first { it.name == aName }
            assertTrue(game.hireCastleGuard(p, "n.o.castle1").contains("отправлен"))
            val guard = game.world.npcsIn("c.1.gate").first { it.key.startsWith("n.o.castle1") }
            game.place(a, "c.1.in")
            assertEquals("c.1.gate", game.move(b, "c.1.gate").character.location)
            clock[0] += 1
            game.tick(clock[0])
            // (A newbie may already have fallen to the first blow, leaving no enemy.)
            assertTrue(guard.enemies.isNotEmpty() || game.view(b).journal.any { it.startsWith(guard.name) }, "the guard should go for the stranger")
            assertEquals(clan, game.view(b).castle!!.owner)
            game.place(b, "x1086x501")

            // Lock the gate through the guard; after capture it cannot be locked for two hours.
            game.place(a, "c.1.gate")
            var d = game.talk(a, guard.key, "begin", null).dialog!!
            d = game.talk(a, guard.key, "close", null).dialog!!
            d = game.talk(a, guard.key, "close", "yes").dialog!!
            assertTrue(d.text.contains("не можем закрыть"), d.text)
            clock[0] += 2 * 3600 + 1
            game.talk(a, guard.key, "begin", null)
            game.talk(a, guard.key, "close", null)
            d = game.talk(a, guard.key, "close", "yes").dialog!!
            assertEquals("Ворота заперты на 10 часов, сир.", d.text)

            // Locked: even the owner must open it from outside first.
            game.place(a, "c.1.in")
            assertEquals("c.1.in", game.move(a, "c.1.gate").character.location)
            assertTrue(game.view(a).castle!!.canOpen)
            game.castle(a, "open", null)
            assertEquals("c.1.gate", game.move(a, "c.1.gate").character.location)

            // Contract: extend by a day for 50 coins.
            game.talk(a, guard.key, "begin", null)
            game.talk(a, guard.key, "status", null)
            val extended = game.talk(a, guard.key, "cont", "1")
            assertTrue(extended.dialog!!.text.startsWith("Контракт продлён"))
            assertEquals(950, extended.inventory.first { it.id == Rules.MONEY }.count)
        }
    }
}
