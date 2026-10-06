package tz.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import tz.shared.CorpseView
import tz.shared.GroundItemView
import tz.shared.Rules
import kotlin.random.Random

/**
 * The live world, in memory: NPCs (with their fight state), wandering,
 * items and corpses on the ground, spawn and respawn timers. Built from
 * content/ on start (the old engine's starting l_i state); not saved — after
 * a restart NPCs stand where content/ puts them.
 *
 * Rules follow the old engine (docs/mechanics-world.md §3,
 * docs/mechanics-combat.md §6.4). Game calls everything under its own lock;
 * [mutex] keeps the world consistent on its own as well.
 */
class World(
    private val content: Content,
    private val random: Random = Random.Default,
    now: Long = System.currentTimeMillis() / 1000,
) {
    @Serializable data class Wander(val steps: Int, val minDelay: Int, val maxDelay: Int)
    @Serializable data class Respawn(val location: String, val min: Int, val max: Int)
    @Serializable data class RandomLoot(val id: String, val chance: Int, val min: Int, val max: Int)
    /** One line of a trader's goods (`bank` "chance:min:max=id:count", f_speakbuy.dat). */
    @Serializable data class StockLine(val id: String, val chance: Int, val min: Int, val max: Int, val initial: Int)

    /** What an NPC is made of; kept so it can come back after death. */
    class Proto(
        val template: String,
        val name: String,
        val hpMax: Int,
        val stats: Stats,
        val items: Map<String, Int>,
        val randomItems: List<RandomLoot>,
        val butcher: Map<String, Int>,
        val wander: Wander?,
        val respawn: Respawn?,
        val stock: List<StockLine> = emptyList(),
    )

    class Npc(
        val key: String,
        val proto: Proto,
        var hp: Int,
        var location: String,
        val home: String,
        var nextMoveAt: Long,
        /** Items it carries; they fall into its corpse. */
        val items: MutableMap<String, Int>,
    ) {
        /** A name given by the owner (f_speakowner.dat «Изменить имя»), shown after the kind. */
        var customName: String? = null
        val name get() = customName?.let { "${proto.name} $it" } ?: proto.name
        val stats get() = proto.stats
        /** Whose it is: a pet, a horse, a mercenary, a summoned creature (Pets.kt); null — nobody's. */
        var owner: Owner? = null
        /** A criminal NPC (a summoned demon) attacks players like a monster. */
        var criminal = false
        val trail = ArrayDeque<String>()
        /**
         * Characters this NPC fights: everyone who struck it (and, for a monster,
         * whoever it picked). Its blows go round them in turn.
         */
        val enemies = LinkedHashSet<Long>()
        /** Free to strike again from this moment, milliseconds. */
        var busyUntil: Long = 0
        /** Ignite and poison burning on it; chilled (slower blows) till then, ms. */
        val dots = ArrayList<Dot>()
        var chilledUntil = 0L
        /** Damage dealt by each character, for sharing the experience. */
        val damageBy = HashMap<Long, Int>()
        var regenCarry = 0.0
        var regenLast = 0L
        /** Regeneration counts from the last hit or heal (char[5] in the old engine). */
        var regenFrom: Long = 0
        /** A trader's goods: id → count left, refilled at [restockAt] (only when someone looks, as before). */
        val goods = LinkedHashMap<String, Int>().apply { for (l in proto.stock) put(l.id, l.initial) }
        var restockAt = 0L
        /** A city guard fighting a monster (NPC against NPC). */
        var npcTarget: String? = null
        /** Vanishes at this time (city guards live 10 minutes); 0 — never. */
        var expiresAt = 0L
        /** Poisoned till then (i.b.jad.c): loses health instead of regenerating. */
        var poisonUntil = 0L
        var poisonLast = 0L
        /** Monsters (n.c.*) attack players on sight. */
        val aggressive get() = key.startsWith("n.c.")
        /** Who it is across restarts: the same key may stand in several places. */
        val id get() = "$home|$key"
    }

    /**
     * The old `owner` string (docs/data-fields.md §3.5): who owns it, whom it
     * follows and guards (character id; [guardNpc] — an NPC key), when it
     * leaves (0 — never), whether it then vanishes, when it leaves for lack
     * of walks (1 hour), where it goes home; [flag]: an escort sets this
     * player flag once out of the dungeon (Y < [flagBelowY]).
     */
    class Owner(
        val ownerId: Long,
        var follow: Long?,
        var guard: Long?,
        var until: Long,
        val vanish: Boolean,
        var idleUntil: Long,
        val home: String? = null,
        var flag: String? = null,
        val flagBelowY: Int = 0,
    ) {
        var guardNpc: String? = null
        var healAt = 0L
    }

    class GroundItem(val id: String, val name: String, var count: Int, var expiresAt: Long)

    class Corpse(
        val id: String,
        val name: String,
        val items: LinkedHashMap<String, Int>,
        val butcher: LinkedHashMap<String, Int>,
        val expiresAt: Long,
        /** Character id for a player's corpse. */
        val playerId: Long?,
        /** Anyone may take from it (f_kill.dat:17): a criminal's, a monster's or animal's, or one in a castle. */
        val free: Boolean = true,
        /** Clan of the dead character: clanmates may take without looting. */
        val clanId: Long? = null,
    ) {
        /** Template of a dead NPC: a necromancer raises it (f_necro.dat). */
        var template: String? = null
    }

    private sealed interface Timer {
        val location: String
    }

    /** Spawn from content/ timers (proto built from the template) or a respawn (proto kept). */
    private data class NpcSpawn(
        override val location: String,
        val key: String,
        val respawn: String,
        val wander: Wander?,
        val proto: Proto? = null,
    ) : Timer

    private data class ItemSpawn(
        override val location: String,
        val item: String,
        val minCount: Int,
        val maxCount: Int,
        val minDelay: Int?,
        val maxDelay: Int?,
    ) : Timer

    private val mutex = Mutex()
    private val npcs = HashMap<String, LinkedHashMap<String, Npc>>()
    private val ground = HashMap<String, LinkedHashMap<String, GroundItem>>()
    private val corpses = HashMap<String, LinkedHashMap<String, Corpse>>()
    private val timers = ArrayList<Pair<Long, Timer>>()
    private var corpseSeq = 0

    /** Problems found while building the world (unknown templates etc.). */
    val problems = ArrayList<String>()

    /** NPCs placed by content/ at start, "home|key" (WorldStore: which of them died). */
    private val initialIds = HashSet<String>()

    init {
        for ((locId, data) in content.locationData) {
            if (locId !in content.locations) continue
            val objects = data["objects"] as? JsonObject
            if (objects != null) for ((key, value) in objects) placeObject(locId, key, value, now)
            val timerData = data["timers"]
            val entries: List<JsonElement> = when (timerData) {
                is JsonObject -> timerData.values.toList()
                is JsonArray -> timerData.toList()
                else -> emptyList()
            }
            for (t in entries) parseTimer(locId, t)?.let { timers += now to it }
        }
        for (list in npcs.values) for (n in list.values) initialIds += n.id
    }

    // ---- building from content ------------------------------------------------

    private fun counted(e: JsonElement?): Map<String, Int> = when (e) {
        is JsonArray -> e.mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            id to (o.int("count") ?: 1)
        }.filter { it.second > 0 }.toMap()
        else -> emptyMap()
    }

    private fun randomLoot(e: JsonElement?): List<RandomLoot> = (e as? JsonArray)?.mapNotNull { x ->
        val o = x as? JsonObject ?: return@mapNotNull null
        RandomLoot(o.str("id") ?: return@mapNotNull null, o.int("chance") ?: 0, o.int("min") ?: 0, o.int("max") ?: 0)
    } ?: emptyList()

    private fun parseRespawn(s: String?): Respawn? {
        val p = s?.split(':') ?: return null
        val loc = p.getOrNull(0)?.takeIf { it in content.locations } ?: return null
        val min = p.getOrNull(1)?.toIntOrNull() ?: return null
        val max = p.getOrNull(2)?.toIntOrNull() ?: return null
        return Respawn(loc, min.coerceAtLeast(1), max.coerceAtLeast(min.coerceAtLeast(1)))
    }

    private fun placeObject(loc: String, key: String, value: JsonElement, now: Long) {
        when {
            value is JsonObject && key.startsWith("n.") -> {
                // Pets and hirelings of the 2007 players (key "owner") are not carried over.
                if ("owner" in value) return
                val char = value["char"] as? JsonObject ?: return
                val war = value["war"] as? JsonObject
                val name = char.str("name")?.substringBefore('*')?.takeIf { it.isNotBlank() } ?: return
                val nb = content.balanceNpcs[templateOf(key)]
                val hpMax = (nb?.int("hp") ?: char.int("hp_max") ?: 1).coerceAtLeast(1)
                val respawn = parseRespawn(war?.str("respawn"))
                val wander = parseWander(char.str("wander"))
                val proto = Proto(
                    templateOf(key), name, hpMax, Formulas.npc(war, nb),
                    // Random loot comes from the template: placed NPCs roll it on every respawn, as spawned ones do.
                    counted(value["items"]), randomLoot(content.npcs[templateOf(key)]?.get("itemsrnd")), counted(value["osvej"]), wander, respawn,
                    stockOf((value["bank"] as? JsonPrimitive)?.contentOrNull ?: content.npcs[templateOf(key)]?.str("bank")),
                )
                val home = respawn?.location ?: loc
                addNpc(Npc(key, proto, if (nb != null) hpMax else (char.int("hp") ?: hpMax).coerceIn(1, hpMax), loc, home, nextMove(now, wander), proto.items.toMutableMap()))
            }
            value is JsonPrimitive && key.startsWith("i.") -> {
                // "name|count|expires": expires 0 = never. Items whose time ran
                // out (most of the 2007 snapshot) are gone, as in the old engine.
                val parts = value.contentOrNull?.split('|') ?: return
                val count = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val expires = parts.getOrNull(2)?.toLongOrNull() ?: 0
                if (expires != 0L && expires < now) return
                val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: content.itemName(key)
                putItem(loc, GroundItem(key, name, maxOf(count, 1), expires))
            }
        }
    }

    /** "n.c.wolf.sl2" → template "n.c.wolf" (f_timernpc.dat: instance suffix after the last dot). */
    private fun templateOf(key: String): String =
        if (key in content.npcs) key else key.substringBeforeLast('.').takeIf { it in content.npcs } ?: key

    private fun parseWander(s: String?): Wander? {
        val p = s?.split(':') ?: return null
        val steps = p.getOrNull(0)?.toIntOrNull() ?: return null
        val min = p.getOrNull(1)?.toIntOrNull() ?: return null
        val max = p.getOrNull(2)?.toIntOrNull() ?: return null
        if (steps <= 0 || max <= 0) return null
        return Wander(steps, min.coerceAtLeast(1), max.coerceAtLeast(min.coerceAtLeast(1)))
    }

    private fun parseTimer(loc: String, t: JsonElement): Timer? {
        val s = (t as? JsonPrimitive)?.contentOrNull ?: run { problems += "$loc: timer that is not a string"; return null }
        val p = s.split('|')
        return when {
            p[0].startsWith("n.") -> NpcSpawn(loc, p[0], p.getOrElse(1) { "" }, parseWander(p.getOrNull(2)))
            p[0].startsWith("i.") -> ItemSpawn(
                loc, p[0],
                p.getOrNull(1)?.toIntOrNull() ?: 1, p.getOrNull(2)?.toIntOrNull() ?: 0,
                p.getOrNull(3)?.toIntOrNull(), p.getOrNull(4)?.toIntOrNull(),
            )
            else -> { problems += "$loc: unknown timer $s"; null }
        }
    }

    private fun spawnNpc(t: NpcSpawn, now: Long) {
        if (npcs[t.location]?.containsKey(t.key) == true) return
        val proto = t.proto ?: run {
            val template = templateOf(t.key)
            if (template !in content.npcs) { problems += "${t.location}: no NPC template for ${t.key}"; return }
            protoOf(template, t.wander, parseRespawn("${t.location}:${t.respawn}")) ?: return
        }
        addFromProto(t.key, proto, t.location, now)
    }

    private fun stockOf(s: String?): List<StockLine> =
        Regex("(\\d+):(\\d+):(\\d+)=([^:|]+):(\\d+)").findAll(s ?: "").map { m ->
            val (chance, min, max, id, count) = m.destructured
            StockLine(id, chance.toInt(), min.toInt(), max.toInt(), count.toInt())
        }.toList()

    /**
     * The trader's goods now: refilled when [now] passed the refill time —
     * each line gets rand(min,max) with its chance, else 0 — and the next
     * refill is set period·rand(0.7..1.3) ahead (f_speakbuy.dat:4-12).
     */
    suspend fun goods(npc: Npc, period: Int, now: Long): Map<String, Int> = mutex.withLock {
        if (now > npc.restockAt) {
            for (l in npc.proto.stock) {
                npc.goods[l.id] = if (random.nextInt(0, 101) > l.chance) 0 else random.nextInt(l.min, maxOf(l.min, l.max) + 1)
            }
            npc.restockAt = now + Math.round(period * random.nextInt(70, 131) / 100.0)
        }
        LinkedHashMap(npc.goods)
    }

    /** Takes [count] from a trader's goods; false if he has fewer. */
    suspend fun sellFromGoods(npc: Npc, id: String, count: Int): Boolean = mutex.withLock {
        val have = npc.goods[id] ?: 0
        if (have < count) return@withLock false
        npc.goods[id] = have - count
        true
    }

    private fun protoOf(template: String, wander: Wander?, respawn: Respawn?): Proto? {
        val o = content.npcs[template] ?: return null
        val char = o["char"] as? JsonObject ?: return null
        val name = char.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val nb = content.balanceNpcs[template]
        val hpMax = (nb?.int("hp") ?: char.int("hp_max") ?: 1).coerceAtLeast(1)
        return Proto(
            template, name, hpMax, Formulas.npc(o["war"] as? JsonObject, nb),
            counted(o["items"]), randomLoot(o["itemsrnd"]), counted(o["osvej"]), wander, respawn,
            stockOf(o.str("bank")),
        )
    }

    private fun addFromProto(key: String, proto: Proto, location: String, now: Long) {
        val items = proto.items.toMutableMap()
        for (r in proto.randomItems) {
            if (random.nextInt(1, 101) > r.chance) continue
            val n = if (r.max > r.min) random.nextInt(r.min, r.max + 1) else r.min
            if (n > 0) items[r.id] = (items[r.id] ?: 0) + n
        }
        val npc = Npc(key, proto, proto.hpMax, location, location, nextMove(now, proto.wander), items)
        npc.regenFrom = now
        addNpc(npc)
    }

    private fun spawnItem(t: ItemSpawn, now: Long) {
        val count = if (t.maxCount >= t.minCount) random.nextInt(t.minCount, t.maxCount + 1) else t.minCount
        val items = ground.getOrPut(t.location) { LinkedHashMap() }
        if (count <= 0) items.remove(t.item)
        else items[t.item] = GroundItem(t.item, content.itemName(t.item), count, 0)
        if (t.minDelay != null && t.maxDelay != null && t.maxDelay > 0) {
            timers += (now + random.nextInt(t.minDelay, maxOf(t.minDelay, t.maxDelay) + 1)) to t
        }
    }

    private fun addNpc(npc: Npc) {
        npcs.getOrPut(npc.location) { LinkedHashMap() }[npc.key] = npc
    }

    private fun putItem(loc: String, item: GroundItem) {
        val items = ground.getOrPut(loc) { LinkedHashMap() }
        val old = items[item.id]
        if (old == null) items[item.id] = item
        else {
            old.count += item.count
            old.expiresAt = if (old.expiresAt == 0L || item.expiresAt == 0L) 0 else maxOf(old.expiresAt, item.expiresAt)
        }
    }

    private fun nextMove(now: Long, w: Wander?): Long =
        if (w == null) Long.MAX_VALUE else now + random.nextInt(w.minDelay, w.maxDelay + 1)

    // ---- time -------------------------------------------------------------------

    /** Advances the world to [now]: due timers fire, NPCs wander, old items and corpses vanish. */
    /** Time of the last tick, to hide items that expired since. */
    private var lastTick = 0L

    suspend fun tick(now: Long) = mutex.withLock {
        lastTick = now
        val due = timers.filter { it.first <= now }
        timers.removeAll(due.toSet())
        for ((_, t) in due) when (t) {
            is NpcSpawn -> spawnNpc(t, now)
            is ItemSpawn -> spawnItem(t, now)
        }
        val moving = npcs.values.flatMap { it.values }
            .filter { it.proto.wander != null && it.owner == null && it.enemies.isEmpty() && it.nextMoveAt <= now }
        for (npc in moving) wanderStep(npc, now)
        for (list in npcs.values) list.values.removeAll { it.expiresAt != 0L && it.expiresAt <= now }
        for (items in ground.values) items.values.removeAll { it.expiresAt != 0L && it.expiresAt <= now }
        for (list in corpses.values) list.values.removeAll { it.expiresAt <= now }
    }

    /**
     * One step of a wandering NPC: back along its trail once it is [Wander.steps]
     * away from home, otherwise through a random exit into a location of the
     * same zone (monsters do not enter guarded town streets, f_na.dat).
     */
    private fun wanderStep(npc: Npc, now: Long) {
        val w = npc.proto.wander ?: return
        npc.nextMoveAt = now + random.nextInt(w.minDelay, w.maxDelay + 1)
        val here = content.locations[npc.location] ?: return
        val target: String
        if (npc.trail.size >= w.steps) {
            target = npc.trail.last()
        } else {
            val options = sameZoneExits(npc)
            if (options.isEmpty()) return
            target = options[random.nextInt(options.size)]
        }
        moveNpcLocked(npc, target)
    }

    private fun sameZoneExits(npc: Npc): List<String> {
        val here = content.locations[npc.location] ?: return emptyList()
        return here.exits.map { it.target }.distinct().filter { t ->
            val loc = content.locations[t]
            loc != null && loc.zone == here.zone && npcs[t]?.containsKey(npc.key) != true
        }
    }

    /** An NPC went from one location to another: Game writes «ушёл/пришёл» to the players there. */
    class Move(val name: String, val from: String, val to: String)
    private val moves = ArrayList<Move>()

    /** The moves since the last call. */
    suspend fun drainMoves(): List<Move> = mutex.withLock { moves.toList().also { moves.clear() } }

    /** Moves an NPC (a chase, a firebird flying off); false if the same key is already there. */
    suspend fun moveNpc(npc: Npc, target: String): Boolean = mutex.withLock { moveNpcLocked(npc, target) }

    private fun moveNpcLocked(npc: Npc, target: String): Boolean {
        if (npcs[target]?.containsKey(npc.key) == true) return false
        if (moves.size < 1000) moves += Move(npc.name, npc.location, target)
        npcs[npc.location]?.remove(npc.key)
        if (npc.trail.isNotEmpty() && npc.trail.last() == target) npc.trail.removeLast() else npc.trail.addLast(npc.location)
        npc.location = target
        addNpc(npc)
        return true
    }

    // ---- fights -------------------------------------------------------------------

    suspend fun npc(loc: String, key: String): Npc? = mutex.withLock { npcs[loc]?.get(key) }

    suspend fun npcsIn(loc: String): List<Npc> = mutex.withLock { npcs[loc]?.values?.toList() ?: emptyList() }

    /** A fleeing NPC runs through a random exit of its zone (f_run.dat); false if it cannot. */
    suspend fun flee(npc: Npc): Boolean = mutex.withLock {
        val options = sameZoneExits(npc)
        if (options.isEmpty()) return@withLock false
        moveNpcLocked(npc, options[random.nextInt(options.size)])
    }

    /**
     * Removes a killed NPC: its items and butcher loot go into a corpse for
     * [Rules.DROPPED_ITEM_LIFETIME] seconds, and it comes back after its
     * respawn time if it has one (f_kill.dat:69-89).
     */
    suspend fun kill(npc: Npc, now: Long): Corpse = mutex.withLock {
        npcs[npc.location]?.remove(npc.key)
        val free = npc.key.startsWith("n.c.") || npc.key.startsWith("n.a.") || CastleRules.inside(npc.location)
        val corpse = addCorpseLocked(npc.location, "труп: ${npc.name}", npc.items, npc.proto.butcher, now, null, free)
        // Zombies and summoned creatures do not rise again.
        if (!npc.key.startsWith("n.z.") && !npc.key.startsWith("n.s.")) corpse.template = npc.proto.template
        npc.owner = null
        npc.proto.respawn?.let { r ->
            timers += (now + random.nextInt(r.min, r.max + 1)) to NpcSpawn(r.location, npc.key, "", npc.proto.wander, npc.proto)
        }
        corpse
    }

    suspend fun addCorpse(loc: String, name: String, items: Map<String, Int>, now: Long, playerId: Long?, free: Boolean = true, clanId: Long? = null): Corpse =
        mutex.withLock { addCorpseLocked(loc, name, items, emptyMap(), now, playerId, free, clanId) }

    suspend fun corpse(loc: String, corpseId: String, now: Long): Corpse? = mutex.withLock { corpses[loc]?.get(corpseId)?.takeIf { it.expiresAt > now } }

    private fun addCorpseLocked(
        loc: String, name: String, items: Map<String, Int>, butcher: Map<String, Int>, now: Long, playerId: Long?,
        free: Boolean = true, clanId: Long? = null,
    ): Corpse {
        val corpse = Corpse("c${++corpseSeq}", name, LinkedHashMap(items), LinkedHashMap(butcher), now + Rules.DROPPED_ITEM_LIFETIME, playerId, free, clanId)
        corpses.getOrPut(loc) { LinkedHashMap() }[corpse.id] = corpse
        return corpse
    }

    /** Takes a whole stack out of a corpse; null if there is no such corpse or item. */
    suspend fun lootCorpse(loc: String, corpseId: String, itemId: String, now: Long): Int? = mutex.withLock {
        val c = corpses[loc]?.get(corpseId)?.takeIf { it.expiresAt > now } ?: return@withLock null
        c.items.remove(itemId)
    }

    /** Takes the butcher loot (meat, hides) out of a corpse. */
    suspend fun butcher(loc: String, corpseId: String, now: Long): Map<String, Int>? = mutex.withLock {
        val c = corpses[loc]?.get(corpseId)?.takeIf { it.expiresAt > now } ?: return@withLock null
        val loot = LinkedHashMap(c.butcher)
        c.butcher.clear()
        loot
    }

    suspend fun corpsesAt(loc: String, now: Long, looter: Long? = null, looterClan: Long? = null): List<CorpseView> = mutex.withLock {
        corpses[loc]?.values?.filter { it.expiresAt > now }?.map { c ->
            CorpseView(
                c.id, c.name,
                c.items.map { (id, n) -> GroundItemView(id, content.itemName(id), n, takeable = true) },
                canButcher = c.butcher.isNotEmpty(),
                looting = !c.free && c.playerId != looter && (c.clanId == null || c.clanId != looterClan),
                canRaise = c.template?.let { it.startsWith("n.c.") || it.startsWith("n.a.") } == true,
                mine = looter != null && c.playerId == looter,
                minutesLeft = ((c.expiresAt - now + 59) / 60).toInt(),
            )
        } ?: emptyList()
    }

    /** Location of a character's own corpse that still holds things. */
    suspend fun corpseOf(playerId: Long, now: Long): String? = mutex.withLock {
        corpses.entries.firstOrNull { (_, m) -> m.values.any { it.playerId == playerId && it.expiresAt > now && it.items.isNotEmpty() } }?.key
    }

    // ---- reading and changing -----------------------------------------------------

    suspend fun itemsAt(loc: String, now: Long): List<GroundItemView> = mutex.withLock {
        ground[loc]?.values?.filter { it.expiresAt == 0L || it.expiresAt > now }
            ?.map { GroundItemView(it.id, it.name, it.count, takeable = !it.id.startsWith("i.s.") || it.id == "i.s.arena" || it.id == Travel.BOAT || it.id.startsWith(Spells.PORTAL)) } ?: emptyList()
    }

    /** True if a fixture with an id starting with [prefix] stands here (e.g. i.s.res — resurrection stone). */
    suspend fun hasFixture(loc: String, prefix: String, exact: Boolean = false): Boolean = mutex.withLock {
        ground[loc]?.let { g -> if (exact) g[prefix]?.let { it.expiresAt == 0L || it.expiresAt > lastTick } == true else g.keys.any { it.startsWith(prefix) } } == true
    }

    /** Removes a whole stack from the ground; null if it is not here. Fixtures stay. */
    suspend fun take(loc: String, itemId: String, now: Long): GroundItem? = mutex.withLock {
        val items = ground[loc] ?: return@withLock null
        val item = items[itemId]?.takeIf { it.expiresAt == 0L || it.expiresAt > now } ?: return@withLock null
        if (item.id.startsWith("i.s.")) return@withLock null   // fixtures (trees, signs) stay; Game reports it
        items.remove(itemId)
        item
    }

    suspend fun drop(loc: String, itemId: String, count: Int, now: Long) = mutex.withLock {
        putItem(loc, GroundItem(itemId, content.itemName(itemId), count, now + Rules.DROPPED_ITEM_LIFETIME))
    }

    /** Puts back a stack that could not be given to a player (database error). */
    suspend fun restore(loc: String, item: GroundItem) = mutex.withLock { putItem(loc, item) }

    // ---- quests ----------------------------------------------------------------------

    /** Puts an NPC made from [template] under [key] into [loc] (no respawn). False if that key is already there. */
    suspend fun spawn(template: String, key: String, loc: String, now: Long): Boolean = mutex.withLock {
        if (npcs[loc]?.containsKey(key) == true) return@withLock false
        val proto = protoOf(template, null, null) ?: return@withLock false
        addFromProto(key, proto, loc, now)
        true
    }

    /** Gives an NPC items to carry (they fall into its corpse). False if the NPC is not there. */
    suspend fun giveNpc(key: String, loc: String, itemId: String, count: Int): Boolean = mutex.withLock {
        val npc = npcs[loc]?.get(key) ?: return@withLock false
        npc.items[itemId] = (npc.items[itemId] ?: 0) + count
        true
    }

    /** Takes an item an NPC carries; false if it has none. */
    suspend fun takeFromNpc(key: String, loc: String, itemId: String): Boolean = mutex.withLock {
        val npc = npcs[loc]?.get(key) ?: return@withLock false
        val n = npc.items[itemId] ?: return@withLock false
        if (n <= 1) npc.items.remove(itemId) else npc.items[itemId] = n - 1
        true
    }

    suspend fun npcHas(key: String, loc: String, itemId: String): Boolean = mutex.withLock {
        (npcs[loc]?.get(key)?.items?.get(itemId) ?: 0) > 0
    }

    /** Puts an item on the ground that never vanishes (quest objects: a repaired boat, a hidden clover). */
    suspend fun placePermanent(loc: String, itemId: String, count: Int) = mutex.withLock {
        putItem(loc, GroundItem(itemId, content.itemName(itemId), count, 0))
    }

    /** Puts an NPC made on the fly (a city guard) for [lifetime] seconds. */
    suspend fun spawnProto(key: String, proto: Proto, loc: String, now: Long, lifetime: Long): Npc = mutex.withLock {
        val npc = Npc(key, proto, proto.hpMax, loc, loc, Long.MAX_VALUE, HashMap(proto.items))
        npc.regenFrom = now
        if (lifetime > 0) npc.expiresAt = now + lifetime
        addNpc(npc)
        npc
    }

    /** An NPC's template as a proto, without wandering and respawn (pets, mercenaries, zombies). */
    suspend fun proto(template: String): Proto? = mutex.withLock { protoOf(template, null, null) }

    /**
     * Takes an NPC out of the world (a pet given back, a sheep sacrificed); it
     * comes back at [respawnAt] after min..max seconds, or by its own respawn.
     */
    suspend fun despawn(npc: Npc, now: Long, respawnAt: String? = null, min: Int = 0, max: Int = 0) = mutex.withLock {
        npcs[npc.location]?.remove(npc.key)
        npc.owner = null
        val r = if (respawnAt != null) Respawn(respawnAt, min, maxOf(min, max)) else npc.proto.respawn
        if (r != null) {
            val base = protoOf(npc.proto.template, npc.proto.wander, r) ?: npc.proto
            timers += (now + random.nextInt(r.min, r.max + 1)) to NpcSpawn(r.location, npc.key, "", npc.proto.wander, base)
        }
    }

    /** Removes a corpse (raised by a necromancer). */
    suspend fun removeCorpse(loc: String, corpseId: String): Corpse? = mutex.withLock { corpses[loc]?.remove(corpseId) }

    suspend fun removeNpc(key: String, loc: String): Boolean = mutex.withLock { npcs[loc]?.remove(key) != null }

    /** Removes an item (fixtures too) from the ground; false if it is not there. */
    suspend fun removeItem(loc: String, itemId: String): Boolean = mutex.withLock { ground[loc]?.remove(itemId) != null }

    // ---- crafting ------------------------------------------------------------------

    internal class NodeState(var stock: Int, var regrowAt: Long)
    private val nodes = HashMap<String, NodeState>()

    /**
     * One pick at a resource node (ore vein, tree) in [loc]: refills it to
     * [stockMax] once empty and past its regrow time; null if it is empty
     * (docs: content/crafting.json "nodes"). Call [spendNode] on success.
     */
    suspend fun nodeHasStock(loc: String, node: String, initial: Int, stockMax: Int, now: Long): Boolean = mutex.withLock {
        val n = nodes.getOrPut("$loc|$node") { NodeState(initial, 0) }
        if (n.stock <= 0 && now > n.regrowAt) n.stock = stockMax
        n.stock > 0
    }

    suspend fun spendNode(loc: String, node: String, regrowSeconds: Int, now: Long) = mutex.withLock {
        val n = nodes["$loc|$node"] ?: return@withLock
        n.stock -= 1
        if (n.stock <= 0) n.regrowAt = now + regrowSeconds
    }

    /** Takes [count] of an item lying on the ground (not fixtures); false if there are fewer. */
    suspend fun takeFromGround(loc: String, itemId: String, count: Int, now: Long): Boolean = mutex.withLock {
        val g = ground[loc] ?: return@withLock false
        val item = g[itemId]?.takeIf { it.expiresAt == 0L || it.expiresAt > now } ?: return@withLock false
        if (item.count < count) return@withLock false
        item.count -= count
        if (item.count <= 0) g.remove(itemId)
        true
    }

    /** Puts an object for [seconds] (a campfire). */
    suspend fun placeFor(loc: String, itemId: String, seconds: Int, now: Long) = mutex.withLock {
        putItem(loc, GroundItem(itemId, content.itemName(itemId), 1, now + seconds))
    }

    // ---- saving and restoring (WorldStore.kt) ------------------------------------------

    suspend fun snapshot(now: Long): WorldSnapshot = mutex.withLock {
        WorldSnapshot(
            savedAt = now,
            initial = initialIds.toList(),
            npcs = npcs.values.flatMap { it.values }.filter { it.expiresAt == 0L || it.expiresAt > now }.map { n ->
                NpcDto(
                    n.key, n.home, n.location, n.hp, HashMap(n.items), n.proto.dto(), n.customName, n.owner?.dto(), n.criminal,
                    HashMap(n.goods), n.restockAt, n.expiresAt, n.poisonUntil,
                )
            },
            items = ground.flatMap { (loc, g) -> g.values.filter { it.expiresAt == 0L || it.expiresAt > now }.map { ItemDto(loc, it.id, it.name, it.count, it.expiresAt) } },
            corpses = corpses.flatMap { (loc, list) ->
                list.values.filter { it.expiresAt > now }.map { c ->
                    CorpseDto(loc, c.id, c.name, HashMap(c.items), HashMap(c.butcher), c.expiresAt, c.playerId, c.free, c.clanId, c.template)
                }
            },
            timers = timers.mapNotNull { (at, t) -> (t as? NpcSpawn)?.takeIf { it.proto != null }?.let { TimerDto(at, it.location, it.key, it.proto!!.dto()) } },
            nodes = nodes.map { (k, v) -> NodeDto(k, v.stock, v.regrowAt) },
        )
    }

    /**
     * Puts the saved world over the one just built from content/: NPCs where
     * they were (content NPCs keep their content stats), those that were dead
     * wait for their respawn, pets keep their owners, things on the ground and
     * corpses come back. Content fixtures missing from the save stay.
     */
    suspend fun restore(s: WorldSnapshot, now: Long) = mutex.withLock {
        val fresh = HashMap<String, Npc>()
        for (list in npcs.values) for (n in list.values) fresh[n.id] = n
        val saved = s.npcs.filter { it.location in content.locations }
        val savedIds = saved.map { "${it.home}|${it.key}" }.toSet()
        val waiting = s.timers.map { "${it.location}|${it.key}" }.toSet()
        val wasInitial = s.initial.toSet()
        for ((id, n) in fresh) if (id in wasInitial && id !in savedIds) npcs[n.location]?.remove(n.key)
        for (d in saved) {
            // A content NPC that content/ no longer has (the seasonal «Продавец счастья») stays gone.
            if ("${d.home}|${d.key}" in wasInitial && fresh["${d.home}|${d.key}"] == null && d.owner == null) continue
            val n = fresh["${d.home}|${d.key}"]?.takeIf { it.id in savedIds }
                ?: Npc(d.key, d.proto.proto(), d.hp, d.location, d.home, Long.MAX_VALUE, HashMap()).also { it.nextMoveAt = nextMove(now, it.proto.wander) }
            npcs[n.location]?.remove(n.key)
            n.location = d.location
            n.hp = d.hp.coerceIn(1, n.proto.hpMax)
            n.items.clear(); n.items.putAll(d.items)
            n.customName = d.customName
            n.owner = d.owner?.owner()
            n.criminal = d.criminal
            for ((id, count) in d.goods) if (id in n.goods) n.goods[id] = count
            n.restockAt = d.restockAt
            n.expiresAt = d.expiresAt
            n.poisonUntil = d.poisonUntil
            n.poisonLast = now
            n.regenFrom = now
            n.trail.clear()
            addNpc(n)
        }
        timers.removeAll { (_, t) -> t is NpcSpawn && t.proto == null && "${t.location}|${t.key}".let { it in savedIds || it in waiting } }
        for (t in s.timers) if (t.location in content.locations) timers += maxOf(t.at, now) to NpcSpawn(t.location, t.key, "", t.proto.wander, t.proto.proto())

        val fixtures = ground.mapValues { (_, g) -> g.values.filter { it.id.startsWith("i.s.") } }
        ground.clear()
        for (i in s.items) if (i.location in content.locations && (i.expiresAt == 0L || i.expiresAt > now)) {
            // A permanent fixture content/ removed (the New Year tree) is not brought back from the save.
            if (i.id.startsWith("i.s.") && i.expiresAt == 0L && fixtures[i.location]?.any { it.id == i.id } != true) continue
            putItem(i.location, GroundItem(i.id, i.name, i.count, i.expiresAt))
        }
        for ((loc, list) in fixtures) for (f in list) if (ground[loc]?.containsKey(f.id) != true) putItem(loc, f)

        corpses.clear()
        for (c in s.corpses) if (c.location in content.locations && c.expiresAt > now) {
            corpses.getOrPut(c.location) { LinkedHashMap() }[c.id] =
                Corpse(c.id, c.name, LinkedHashMap(c.items), LinkedHashMap(c.butcher), c.expiresAt, c.playerId, c.free, c.clanId).also { it.template = c.template }
            c.id.removePrefix("c").toIntOrNull()?.let { if (it > corpseSeq) corpseSeq = it }
        }
        for (n in s.nodes) nodes[n.key] = NodeState(n.stock, n.regrowAt)
    }

    private fun Proto.dto() = ProtoDto(template, name, hpMax, stats, items, randomItems, butcher, wander, respawn, stock)
    private fun ProtoDto.proto() = Proto(template, name, hpMax, stats, items, randomItems, butcher, wander, respawn, stock)
    private fun Owner.dto() = OwnerDto(ownerId, follow, guard, until, vanish, idleUntil, home, flag, flagBelowY, guardNpc, healAt)
    private fun OwnerDto.owner() = Owner(ownerId, follow, guard, until, vanish, idleUntil, home, flag, flagBelowY).also { it.guardNpc = guardNpc; it.healAt = healAt }

    // ---- for tests and diagnostics --------------------------------------------------

    suspend fun allNpcs(): List<Npc> = mutex.withLock { npcs.values.flatMap { it.values } }

    suspend fun npcCount(): Int = mutex.withLock { npcs.values.sumOf { it.size } }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
