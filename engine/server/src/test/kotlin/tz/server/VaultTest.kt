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

/** The clan vault and leaving the game by the button. Needs TZ_TEST_DATABASE_URL. */
class VaultTest {
    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? get() = TestDb.db
    }

    private fun rnd(n: Int) = (1..n).map { ('а'..'я').random() }.joinToString("")
    private fun GameView.count(id: String) = inventory.firstOrNull { it.id == id }?.count ?: 0

    @Test
    fun clanVaultAtBankersAndInTheCastle() {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            fun login() = "h" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val (head, _) = accounts.register(login(), "secret-123")
            val (novice, _) = accounts.register(login(), "secret-123")
            val (stranger, _) = accounts.register(login(), "secret-123")
            val headName = "Сеньор" + rnd(5); val noviceName = "Неофит" + rnd(5)
            accounts.createCharacter(head, headName, "m"); accounts.createCharacter(novice, noviceName, "m")
            accounts.createCharacter(stranger, "Чужой" + rnd(5), "m")
            val clan = "Кладовщики" + rnd(3)
            database.tx { c ->
                val id = c.prepareStatement("INSERT INTO clans (name) VALUES (?) RETURNING id").use { st -> st.setString(1, clan); st.executeQuery().use { it.next(); it.getLong(1) } }
                for ((a, rank) in listOf(head to "head", novice to "neophyte"))
                    c.prepareStatement("INSERT INTO clan_members (character_id, clan_id, rank) SELECT id, ?, ? FROM characters WHERE account_id = ?").use { st ->
                        st.setLong(1, id); st.setString(2, rank); st.setLong(3, a.id); st.executeUpdate()
                    }
                for (a in listOf(head, novice)) c.prepareStatement(
                    "INSERT INTO character_items (character_id, item_id, count) SELECT id, 'i.money', 500 FROM characters WHERE account_id = ?"
                ).use { st -> st.setLong(1, a.id); st.executeUpdate() }
                c.prepareStatement("UPDATE castles SET clan_id = ?, locked_until = 0, open_until = 0 WHERE id = 4").use { st -> st.setLong(1, id); st.executeUpdate() }
            }
            val clock = longArrayOf(1_800_000_000L)
            val game = Game(content, database, accounts, World(content, Random(6), clock[0]), { clock[0] }, Random(6))

            suspend fun atBanker(a: Account) {
                game.place(a, Rules.BANK_LOCATION)
                val v = game.talk(a, "n.bankir", "begin", null)
                val o = v.dialog!!.options.first { it.label.startsWith("Я хочу положить в банк") }
                assertTrue(game.talk(a, "n.bankir", o.topic, o.arg).bank!!.clanVault)
            }

            // Not at a banker or in the own castle's vault: it does not open.
            game.place(head, "x1086x501")
            assertEquals(Errors.NO_VAULT_HERE, assertFailsWith<ApiException> { game.vault(head, "open", "", "", 1, "neophyte", 0) }.code)

            // The head leaves coins for everyone and coins for the head only.
            atBanker(head)
            var v = game.vault(head, "put", "n.bankir", Rules.MONEY, 100, "neophyte", 0)
            assertEquals(400, v.count(Rules.MONEY))
            v = game.vault(head, "put", "n.bankir", Rules.MONEY, 50, "head", 0)
            val vault = assertNotNull(v.vault)
            assertEquals(2, vault.items.size)
            assertTrue(vault.log.first().contains("положил 50"), vault.log.toString())

            // In the castle's vault room the novice sees both and may take only the one left for everyone.
            game.place(novice, "c.4.hran")
            assertTrue(game.view(novice).castle!!.vault)
            var w = game.vault(novice, "open", "", "", 1, "neophyte", 0)
            val forAll = w.vault!!.items.single { it.access == "neophyte" }
            val forHead = w.vault!!.items.single { it.access == "head" }
            assertTrue(forAll.canTake && !forHead.canTake)
            assertTrue(w.vault!!.log.isEmpty(), "a novice does not see the log")
            assertEquals(Errors.VAULT_RIGHTS, assertFailsWith<ApiException> { game.vault(novice, "take", "", "", 50, "neophyte", forHead.slot) }.code)
            w = game.vault(novice, "take", "", "", 30, "neophyte", forAll.slot)
            assertEquals(530, w.count(Rules.MONEY))
            assertEquals(70, w.vault!!.items.single { it.access == "neophyte" }.count)

            // What one leaves for the head only, he may still take back himself.
            w = game.vault(novice, "put", "", Rules.MONEY, 10, "head", 0)
            val mine = w.vault!!.items.single { it.owner == noviceName }
            assertTrue(mine.canTake)
            assertEquals(530, game.vault(novice, "take", "", "", 10, "neophyte", mine.slot).count(Rules.MONEY))

            // Quest things stay out; a stranger has no vault at all.
            assertEquals(Errors.CANNOT_TRADE, assertFailsWith<ApiException> { game.vault(novice, "put", "", "i.q.ditrih", 1, "neophyte", 0) }.code)
            game.place(stranger, "c.4.hran")
            assertEquals(Errors.NOT_IN_CLAN, assertFailsWith<ApiException> { game.vault(stranger, "open", "", "", 1, "neophyte", 0) }.code)

            // Losing the castle loses nothing: the bankers still open it.
            game.castles().getValue(4).apply { clanId = null; clanName = null }
            game.place(novice, "c.4.hran")
            assertEquals(Errors.NO_VAULT_HERE, assertFailsWith<ApiException> { game.vault(novice, "open", "", "", 1, "neophyte", 0) }.code)
            atBanker(novice)
            assertEquals(2, game.vault(novice, "open", "n.bankir", "", 1, "neophyte", 0).vault!!.items.size)
        }
    }

    @Test
    fun theExitButtonTakesTheCharacterOutAtOnce() {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            fun login() = "o" + (1..10).map { ('a'..'z').random() }.joinToString("")
            val (a, _) = accounts.register(login(), "secret-123")
            val (b, _) = accounts.register(login(), "secret-123")
            val bName = "Уходящий" + rnd(5)
            accounts.createCharacter(a, "Остающийся" + rnd(5), "m"); accounts.createCharacter(b, bName, "m")
            val clock = longArrayOf(1_800_000_000L)
            val game = Game(content, database, accounts, World(content, Random(7), clock[0]), { clock[0] }, Random(7))
            game.place(a, "x1141x506"); game.place(b, "x1141x506")
            game.view(b)
            assertTrue(game.view(a).people.any { it.name == bName })
            // Not in a fight: a blow between the two a moment ago, or a monster set on him here.
            val pa = game.players.values.first { it.accountId == a.id }; val pb = game.players.values.first { it.accountId == b.id }
            pa.pvpAt = clock[0]; pa.pvpWith = pb.id; pb.pvpAt = clock[0]; pb.pvpWith = pa.id
            assertEquals(Errors.IN_COMBAT, assertFailsWith<ApiException> { game.leave(b) }.code)
            game.leave(b, force = true)   // signing out anyway leaves him standing
            assertTrue(game.view(a).people.any { it.name == bName })
            clock[0] += Travel.COMBAT_SECONDS
            val npc = game.world.allNpcs().first { it.hp > 0 }
            game.place(b, npc.location)
            npc.enemies += pb.id
            assertEquals(Errors.IN_COMBAT, assertFailsWith<ApiException> { game.leave(b) }.code)
            npc.enemies -= pb.id
            game.place(b, "x1141x506")
            game.view(b)
            game.leave(b)
            assertTrue(game.view(a).people.none { it.name == bName }, "gone at once, not after ten minutes")
            // Coming back puts the character where it stood.
            clock[0] += 5
            assertEquals("x1141x506", game.view(b).character.location)
            assertTrue(game.view(a).people.any { it.name == bName })
        }
    }
}
