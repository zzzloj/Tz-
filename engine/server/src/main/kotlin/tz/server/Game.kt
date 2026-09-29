package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tz.shared.CharacterView
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.InventoryItemView
import tz.shared.NpcView
import tz.shared.Protocol
import tz.shared.Rules
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Everything a character does in the world: moving, items, fighting, dying.
 * Characters and inventories are stored in PostgreSQL; while a character is
 * active its state (HP, pause, target, journal) is kept here and written
 * back after each change. NPCs and things on the ground live in [World].
 *
 * One lock ([lock]) serialises all actions and the world tick, like the old
 * engine's game.lock — simple and fast enough for one world.
 */
class Game(
    private val content: Content,
    private val db: Db,
    private val accounts: Accounts,
    val world: World,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    random: Random = Random.Default,
) {
    private val dice = Dice.of(random)
    private val rnd = random
    private val lock = Mutex()

    class Player(
        val id: Long,
        val accountId: Long,
        val name: String,
        val sex: String,
        var location: String,
        var hp: Int,
        var mana: Int,
        var ghost: Boolean,
        val str: Int,
        val dex: Int,
        val int: Int,
        var exp: Int,
        var points: Int,
    ) {
        var equipped: List<String> = emptyList()
        var stats: Stats = Formulas.player(skills(), emptyList(), { null })
        var busyUntil = 0L
        var regenFrom = 0L
        var lastSeen = 0L
        val journal = ArrayDeque<String>()

        fun skills() = Skills.of(str, dex, int, exp, points)
        val hpMax get() = (Rules.hpMax(str) + stats.hpBonus).coerceAtLeast(1)
        val manaMax get() = (Rules.manaMax(int) + stats.manaBonus).coerceAtLeast(0)

        fun log(line: String) {
            journal.addLast(line)
            while (journal.size > JOURNAL_SIZE) journal.removeFirst()
        }
    }

    private val players = ConcurrentHashMap<Long, Player>()
    private val byAccount = ConcurrentHashMap<Long, Long>()
    private val events = ConcurrentHashMap<Long, MutableSharedFlow<Unit>>()

    // ---- public actions -----------------------------------------------------------

    suspend fun view(account: Account): GameView = lock.withLock { viewLocked(player(account)) }

    suspend fun move(account: Account, target: String): GameView = lock.withLock {
        val p = player(account)
        val here = content.locations[p.location]
        if (here == null || here.exits.none { it.target == target } || target !in content.locations)
            throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_AN_EXIT)
        for (npc in world.npcsIn(p.location)) npc.enemies.remove(p.id)
        p.location = target
        save(p)
        notifyLocation(target, except = p.id)
        viewLocked(p)
    }

    suspend fun take(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        if (itemId.startsWith("i.s.")) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TAKE)
        val now = clock()
        val item = world.take(p.location, itemId, now) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
        try {
            db.tx { c -> addItem(c, p.id, item.id, item.count) }
        } catch (e: Exception) {
            world.restore(p.location, item)
            throw e
        }
        viewLocked(p)
    }

    suspend fun drop(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        val count = db.tx { c ->
            c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ? RETURNING count").use { st ->
                st.setLong(1, p.id)
                st.setString(2, itemId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
            }
        } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
        world.drop(p.location, itemId, count, clock())
        refreshStats(p)
        viewLocked(p)
    }

    /** Puts an item on; whatever occupied the same slot (or conflicts, shield vs bow) comes off. */
    suspend fun equip(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        val slot = Rules.equipSlot(itemId) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_EQUIP)
        db.tx { c ->
            val owned = inventoryRows(c, p.id)
            if (owned.none { it.first == itemId }) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
            val off = owned.filter { (id, _, equipped) ->
                equipped && id != itemId && (Rules.equipSlot(id) == slot || Rules.conflicts(id, itemId))
            }.map { it.first }
            for (id in off) setEquipped(c, p.id, id, false)
            setEquipped(c, p.id, itemId, true)
        }
        refreshStats(p)
        viewLocked(p)
    }

    suspend fun unequip(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        db.tx { c ->
            if (!setEquipped(c, p.id, itemId, false)) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
        }
        refreshStats(p)
        viewLocked(p)
    }

    /** Strikes an NPC here (f_attackf.dat): pause, blow, its free counter-blow, death. */
    suspend fun attack(account: Account, target: String): GameView = lock.withLock {
        val p = alive(player(account))
        val now = clock()
        val npc = world.npc(p.location, target) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_TARGET)
        if (!Rules.attackable(npc.key)) throw ApiException(HttpStatusCode.BadRequest, Errors.PEACEFUL)
        if (now < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
        p.busyUntil = now + p.stats.delay
        if (p.stats.ammo.isNotEmpty() && !useAmmo(p)) {
            viewLocked(p)
            throw ApiException(HttpStatusCode.BadRequest, Errors.NO_AMMO)
        }
        playerHits(p, npc, now, answer = true)
        save(p)
        viewLocked(p)
    }

    suspend fun loot(account: Account, corpseId: String, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        val count = world.lootCorpse(p.location, corpseId, itemId, clock())
            ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_CORPSE)
        db.tx { c -> addItem(c, p.id, itemId, count) }
        viewLocked(p)
    }

    /** Cuts meat and hides off a corpse; needs a knife (i.w.k.*) in the backpack (plugin/i.w.k.dat). */
    suspend fun butcher(account: Account, corpseId: String): GameView = lock.withLock {
        val p = alive(player(account))
        val hasKnife = db.tx { c -> inventoryRows(c, p.id) }.any { it.first.startsWith("i.w.k.") }
        if (!hasKnife) throw ApiException(HttpStatusCode.BadRequest, Errors.NEED_KNIFE)
        val loot = world.butcher(p.location, corpseId, clock())
            ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_CORPSE)
        db.tx { c -> for ((id, n) in loot) addItem(c, p.id, id, n) }
        if (loot.isNotEmpty()) p.log("Вы разделали: " + loot.entries.joinToString { (id, n) -> content.itemName(id) + if (n > 1) " ×$n" else "" })
        viewLocked(p)
    }

    /**
     * A ghost at a resurrection stone (i.s.res*) or a healer (n.h.*) comes
     * back to life with [Rules.RESURRECT_HP_PERCENT] of max HP — 0 as in the
     * old f_ressurect.dat; health comes back with regeneration.
     * f_ressurect.dat left 0 HP — any blow killed again).
     */
    suspend fun resurrect(account: Account): GameView = lock.withLock {
        val p = player(account)
        if (!p.ghost) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_GHOST)
        if (!canResurrect(p)) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_RESURRECTION_HERE)
        p.ghost = false
        p.hp = p.hpMax * Rules.RESURRECT_HP_PERCENT / 100
        p.regenFrom = clock()
        p.log("Вы воскресли.")
        save(p)
        viewLocked(p)
    }

    /** Puts a character somewhere without walking (tests; later teleports and quests). */
    internal suspend fun place(account: Account, location: String) = lock.withLock {
        val p = player(account)
        p.location = location
        save(p)
    }

    /** Signals "your screen changed" for the WebSocket of this account's character. */
    suspend fun events(account: Account): SharedFlow<Unit> = lock.withLock { eventsOf(player(account).id) }

    // ---- world clock ------------------------------------------------------------------

    /**
     * One second of the world (g.php doai()): timers and wandering, then in
     * every location with active players: regeneration, monsters choosing
     * and attacking targets, fleeing NPCs.
     */
    suspend fun tick(now: Long) = lock.withLock {
        world.tick(now)
        val active = players.values.filter { now - it.lastSeen < ACTIVE_SECONDS }
        for (p in active) if (!p.ghost) regen(p, now)
        for ((loc, here) in active.groupBy { it.location }) {
            val living = here.filter { !it.ghost }
            for (npc in world.npcsIn(loc)) {
                regenNpc(npc, now)
                // Enemies who left or died are forgotten (no chasing yet, f_goto.dat).
                npc.enemies.retainAll(living.filter { !it.ghost }.map { it.id }.toSet())
                if (npc.enemies.isEmpty() && npc.aggressive && living.isNotEmpty()) {
                    npc.enemies += living[rnd.nextInt(living.size)].id
                }
                // Blows go round all its enemies in turn.
                val victimId = npc.enemies.firstOrNull() ?: continue
                val victim = living.firstOrNull { it.id == victimId } ?: continue
                if (now < npc.busyUntil || npc.hp < 1) continue
                if (npc.hp < npc.proto.hpMax / 4 && dice.roll(0, 100) < 50 && world.flee(npc)) {
                    npc.enemies.clear()
                    for (q in here) { q.log("${npc.name} убегает."); notify(q.id) }
                    continue
                }
                npc.busyUntil = now + npc.stats.delay
                npc.enemies.remove(victimId); npc.enemies.add(victimId)
                npcHits(npc, victim, now, answer = true)
                save(victim)
            }
        }
    }

    // ---- fighting -------------------------------------------------------------------

    private fun describe(h: Formulas.Hit, verb: String): String = when (h.outcome) {
        Formulas.Outcome.MISS, Formulas.Outcome.FIZZLED -> "мимо"
        Formulas.Outcome.DODGED -> "мимо (уклон)"
        Formulas.Outcome.HIT -> buildString {
            if (h.crit) append("критически ")
            append(verb).append(' ').append(h.damage)
            if (h.shield > 0) append(" (щит ${h.shield})")
            if (h.resisted > 0) append(" (сопр. магии ${h.resisted})")
        }
    }

    private suspend fun playerHits(p: Player, npc: World.Npc, now: Long, answer: Boolean) {
        val h = Formulas.attack(p.stats, npc.stats, dice)
        if (h.outcome == Formulas.Outcome.FIZZLED) return
        val text = describe(h, p.stats.verb)
        p.log(if (answer) "Вы по ${npc.name} $text" else "  вы отвечаете: $text")
        tellOthers(p.location, p.id, if (answer) "${p.name} по ${npc.name} $text" else "${p.name} отвечает: $text")
        if (h.outcome == Formulas.Outcome.HIT) {
            npc.hp -= h.damage
            npc.regenFrom = now
        }
        // Everyone who strikes an NPC becomes its enemy; it answers them all.
        npc.enemies += p.id
        if (npc.hp < 1) {
            killNpc(p, npc, now)
            return
        }
        // Free counter-blow of a target that is not resting (f_attackf.dat:171).
        if (answer && now >= npc.busyUntil) npcHits(npc, p, now, answer = false)
    }

    private suspend fun npcHits(npc: World.Npc, p: Player, now: Long, answer: Boolean) {
        val h = Formulas.attack(npc.stats, p.stats, dice)
        if (h.outcome == Formulas.Outcome.FIZZLED) return
        val text = describe(h, npc.stats.verb)
        p.log(if (answer) "${npc.name} по вам $text" else "  ${npc.name} отвечает: $text")
        tellOthers(p.location, p.id, "${npc.name} по ${p.name} $text")
        notify(p.id)
        if (h.outcome == Formulas.Outcome.HIT) {
            p.hp -= h.damage
            p.regenFrom = now
        }
        // Only a blow that lands kills: just resurrected at 0 HP, a miss leaves the character alive.
        if (h.outcome == Formulas.Outcome.HIT && p.hp < 1) {
            killPlayer(p, "${npc.name}", now)
            return
        }
        // The player answers too, if not resting (the old engine did this for players as well).
        if (answer && now >= p.busyUntil && !p.ghost) playerHits(p, npc, now, answer = false)
    }

    private suspend fun killNpc(p: Player, npc: World.Npc, now: Long) {
        world.kill(npc, now)
        p.log("${npc.name} погибает.")
        tellOthers(p.location, p.id, "${npc.name} погибает.")
        val gained = npc.stats.expValue
        if (gained > 0) {
            p.exp += gained
            if (p.exp > Formulas.expThreshold(p.skills())) {
                p.exp = 0
                p.points += 1
                p.log("Вы получили очко опыта! Потратить его можно у учителей.")
                refreshStats(p)
            }
        }
    }

    /** Death (f_kill.dat): everything carried falls into a corpse, the character becomes a ghost. */
    private suspend fun killPlayer(p: Player, killer: String, now: Long) {
        p.hp = 0
        p.ghost = true
        val items = db.tx { c ->
            val rows = inventoryRows(c, p.id)
            c.prepareStatement("DELETE FROM character_items WHERE character_id = ?").use { it.setLong(1, p.id); it.executeUpdate() }
            rows.associate { it.first to it.second }
        }
        world.addCorpse(p.location, "труп: ${p.name}", items, now, p.id)
        p.equipped = emptyList()
        p.stats = Formulas.player(p.skills(), emptyList(), { content.items[it] })
        p.log("Вас убил $killer. Вы призрак; ваши вещи остались в трупе на 10 минут.")
        tellOthers(p.location, p.id, "${p.name} погибает.")
        for (npc in world.npcsIn(p.location)) npc.enemies.remove(p.id)
        save(p)
        notify(p.id)
    }

    private suspend fun useAmmo(p: Player): Boolean = db.tx { c ->
        val left = c.prepareStatement(
            "UPDATE character_items SET count = count - 1 WHERE character_id = ? AND item_id = ? AND count > 0 RETURNING count"
        ).use { st ->
            st.setLong(1, p.id)
            st.setString(2, p.stats.ammo)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        } ?: return@tx false
        if (left == 0) c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ?").use { st ->
            st.setLong(1, p.id); st.setString(2, p.stats.ammo); st.executeUpdate()
        }
        true
    }

    private fun regen(p: Player, now: Long) {
        if (p.hp >= p.hpMax && p.mana >= p.manaMax) { p.regenFrom = now; return }
        val since = now - p.regenFrom
        val add = Formulas.regen(since)
        if (add <= 0) return
        p.hp = (p.hp + add).coerceAtMost(p.hpMax)
        p.mana = (p.mana + add).coerceAtMost(p.manaMax)
        p.regenFrom = now
        dirty += p.id
    }

    private fun regenNpc(npc: World.Npc, now: Long) {
        if (npc.hp >= npc.proto.hpMax) { npc.regenFrom = now; return }
        val add = Formulas.regen(now - npc.regenFrom)
        if (add <= 0) return
        npc.hp = (npc.hp + add).coerceAtMost(npc.proto.hpMax)
        npc.regenFrom = now
    }

    private val dirty = HashSet<Long>()

    /** Writes regeneration to the database now and then, not every second. */
    suspend fun flush() = lock.withLock {
        val ids = dirty.toList()
        dirty.clear()
        for (id in ids) players[id]?.let { save(it) }
    }

    private suspend fun canResurrect(p: Player): Boolean =
        p.ghost && (world.hasFixture(p.location, "i.s.res") || world.npcsIn(p.location).any { it.key.startsWith("n.h.") })

    // ---- players --------------------------------------------------------------------

    private fun alive(p: Player): Player {
        if (p.ghost) throw ApiException(HttpStatusCode.Conflict, Errors.GHOST)
        return p
    }

    private suspend fun player(account: Account): Player {
        val now = clock()
        val cached = byAccount[account.id]?.let { players[it] }
        val p = cached ?: load(account) ?: throw ApiException(HttpStatusCode.Conflict, Errors.NO_CHARACTER)
        p.lastSeen = now
        return p
    }

    private suspend fun load(account: Account): Player? {
        val p = db.tx { c ->
            c.prepareStatement(
                "SELECT id, name, sex, location, hp, mana, ghost, str, dex, intel, exp, skill_points FROM characters " +
                    "WHERE account_id = ? AND world_id = 1"
            ).use { st ->
                st.setLong(1, account.id)
                st.executeQuery().use { rs ->
                    if (!rs.next()) null else Player(
                        rs.getLong("id"), account.id, rs.getString("name"), rs.getString("sex"), rs.getString("location"),
                        rs.getInt("hp"), rs.getInt("mana"), rs.getBoolean("ghost"),
                        rs.getInt("str"), rs.getInt("dex"), rs.getInt("intel"), rs.getInt("exp"), rs.getInt("skill_points"),
                    )
                }
            }
        } ?: return null
        p.regenFrom = clock()
        refreshStats(p)
        players[p.id] = p
        byAccount[account.id] = p.id
        return p
    }

    private suspend fun refreshStats(p: Player) {
        p.equipped = db.tx { c -> inventoryRows(c, p.id) }.filter { it.third }.map { it.first }
        p.stats = Formulas.player(p.skills(), p.equipped, { content.items[it] })
        p.hp = p.hp.coerceAtMost(p.hpMax)
        p.mana = p.mana.coerceAtMost(p.manaMax)
    }

    private suspend fun save(p: Player) = db.tx { c ->
        c.prepareStatement(
            "UPDATE characters SET location = ?, hp = ?, mana = ?, ghost = ?, exp = ?, skill_points = ? WHERE id = ?"
        ).use { st ->
            st.setString(1, p.location)
            st.setInt(2, p.hp.coerceAtLeast(0))
            st.setInt(3, p.mana.coerceAtLeast(0))
            st.setBoolean(4, p.ghost)
            st.setInt(5, p.exp)
            st.setInt(6, p.points)
            st.setLong(7, p.id)
            st.executeUpdate()
        }
    }

    private fun eventsOf(id: Long) = events.getOrPut(id) {
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    private fun notify(id: Long) {
        events[id]?.tryEmit(Unit)
    }

    private fun notifyLocation(loc: String, except: Long) {
        val now = clock()
        for (q in players.values) if (q.id != except && q.location == loc && now - q.lastSeen < ACTIVE_SECONDS) notify(q.id)
    }

    private fun tellOthers(loc: String, except: Long, line: String) {
        val now = clock()
        for (q in players.values) if (q.id != except && q.location == loc && now - q.lastSeen < ACTIVE_SECONDS) {
            q.log(line)
            notify(q.id)
        }
    }

    private suspend fun viewLocked(p: Player): GameView {
        val now = clock()
        val loc = content.locations[p.location] ?: content.locations.getValue(Protocol.START_LOCATION)
        val others = players.values
            .filter { it.id != p.id && it.location == loc.id && now - it.lastSeen < ACTIVE_SECONDS }
            .map { if (it.ghost) "${it.name} (призрак)" else it.name }
            .sorted()
        val npcs = world.npcsIn(loc.id).map { NpcView(it.key, it.name, it.hp, it.proto.hpMax, p.id in it.enemies, Rules.attackable(it.key)) }
        val location = loc.view().copy(
            npcs = npcs,
            items = world.itemsAt(loc.id, now),
            players = others,
            corpses = world.corpsesAt(loc.id, now),
        )
        val inventory = db.tx { c -> inventoryRows(c, p.id) }.map { (id, count, equipped) ->
            InventoryItemView(id, content.itemName(id), count, equipped, Rules.equipSlot(id) != null)
        }
        val s = p.stats
        val character = CharacterView(
            id = p.id, name = p.name, sex = p.sex, location = p.location,
            hp = p.hp.coerceAtLeast(0), hpMax = p.hpMax, mana = p.mana.coerceAtLeast(0), manaMax = p.manaMax,
            str = p.str, dex = p.dex, int = p.int, skillPoints = p.points,
            ghost = p.ghost, exp = p.exp, expNext = Formulas.expThreshold(p.skills()),
            hit = s.hit, dmgMin = s.dmgMin, dmgMax = s.dmgMax, armor = s.armor, dodge = s.dodge,
        )
        return GameView(
            character, location, inventory,
            journal = p.journal.toList(),
            restSeconds = (p.busyUntil - now).coerceAtLeast(0).toInt(),
            canResurrect = canResurrect(p),
        )
    }

    // ---- inventory rows ----------------------------------------------------------------

    private fun addItem(c: Connection, characterId: Long, itemId: String, count: Int) {
        c.prepareStatement(
            "INSERT INTO character_items (character_id, item_id, count) VALUES (?, ?, ?) " +
                "ON CONFLICT (character_id, item_id) DO UPDATE SET count = character_items.count + EXCLUDED.count"
        ).use { st ->
            st.setLong(1, characterId)
            st.setString(2, itemId)
            st.setInt(3, count)
            st.executeUpdate()
        }
    }

    private fun inventoryRows(c: Connection, characterId: Long): List<Triple<String, Int, Boolean>> =
        c.prepareStatement("SELECT item_id, count, equipped FROM character_items WHERE character_id = ? ORDER BY equipped DESC, item_id").use { st ->
            st.setLong(1, characterId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getInt(2), rs.getBoolean(3))) }
            }
        }

    private fun setEquipped(c: Connection, characterId: Long, itemId: String, equipped: Boolean): Boolean =
        c.prepareStatement("UPDATE character_items SET equipped = ? WHERE character_id = ? AND item_id = ?").use { st ->
            st.setBoolean(1, equipped)
            st.setLong(2, characterId)
            st.setString(3, itemId)
            st.executeUpdate() == 1
        }

    companion object {
        /** Characters active in the last 10 minutes are shown and can be attacked by monsters. */
        const val ACTIVE_SECONDS = 600
        const val JOURNAL_SIZE = 30
    }
}
