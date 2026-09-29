package tz.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import tz.shared.GroundItemView
import tz.shared.NpcView
import kotlin.random.Random

/**
 * The live world, in memory: NPCs, their wandering, items on the ground and
 * spawn timers. Built from content/ on start (the old engine's starting
 * l_i state); nothing here is saved yet — NPCs respawn from content/ after a
 * restart and dropped items are short-lived anyway.
 *
 * Rules follow the old engine (docs/mechanics-world.md §3), simplified where
 * noted. All access goes through [mutex]; [tick] advances time.
 */
class World(
    private val content: Content,
    private val random: Random = Random.Default,
    now: Long = System.currentTimeMillis() / 1000,
) {
    data class Wander(val steps: Int, val minDelay: Int, val maxDelay: Int)

    class Npc(
        val key: String,
        val template: String,
        val name: String,
        var hp: Int,
        val hpMax: Int,
        var location: String,
        val home: String,
        val wander: Wander?,
        var nextMoveAt: Long,
    ) {
        /** Locations walked from home, to walk back (f_na.dat keeps the same trail). */
        val trail = ArrayDeque<String>()
    }

    class GroundItem(val id: String, val name: String, var count: Int, var expiresAt: Long)

    private sealed interface Timer {
        val location: String
    }

    private data class NpcSpawn(
        override val location: String,
        val key: String,
        val respawn: String,
        val wander: Wander?,
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
    private val timers = ArrayList<Pair<Long, Timer>>()

    /** Problems found while building the world (unknown templates etc.). */
    val problems = ArrayList<String>()

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
    }

    // ---- building from content ------------------------------------------------

    private fun placeObject(loc: String, key: String, value: JsonElement, now: Long) {
        when {
            value is JsonObject && key.startsWith("n.") -> {
                // Pets and hirelings of the 2007 players (key "owner") are not carried over.
                if ("owner" in value) return
                val char = value["char"] as? JsonObject ?: return
                val war = value["war"] as? JsonObject
                val name = char.str("name")?.substringBefore('*')?.takeIf { it.isNotBlank() } ?: return
                val hpMax = char.int("hp_max") ?: 1
                val home = war?.str("respawn")?.substringBefore(':')?.takeIf { it in content.locations } ?: loc
                val wander = parseWander(char.str("wander"))
                addNpc(Npc(key, templateOf(key), name, char.int("hp") ?: hpMax, hpMax, loc, home, wander, nextMove(now, wander)))
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
        val template = templateOf(t.key)
        val o = content.npcs[template] ?: run { problems += "${t.location}: no NPC template for ${t.key}"; return }
        val char = o["char"] as? JsonObject ?: return
        val name = char.str("name")?.takeIf { it.isNotBlank() } ?: return
        val hpMax = char.int("hp_max") ?: 1
        addNpc(Npc(t.key, template, name, hpMax, hpMax, t.location, t.location, t.wander, nextMove(now, t.wander)))
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

    /** Advances the world to [now]: due timers fire, NPCs wander, old items vanish. */
    suspend fun tick(now: Long) = mutex.withLock { tickLocked(now) }

    private fun tickLocked(now: Long) {
        val due = timers.filter { it.first <= now }
        timers.removeAll(due.toSet())
        for ((_, t) in due) when (t) {
            is NpcSpawn -> spawnNpc(t, now)
            is ItemSpawn -> spawnItem(t, now)
        }
        val moving = npcs.values.flatMap { it.values }.filter { it.wander != null && it.nextMoveAt <= now }
        for (npc in moving) wanderStep(npc, now)
        for (items in ground.values) items.values.removeAll { it.expiresAt != 0L && it.expiresAt <= now }
    }

    /**
     * One step of a wandering NPC: back along its trail once it is [Wander.steps]
     * away from home, otherwise through a random exit into a location of the
     * same zone (monsters do not enter guarded town streets, f_na.dat).
     */
    private fun wanderStep(npc: Npc, now: Long) {
        val w = npc.wander ?: return
        npc.nextMoveAt = now + random.nextInt(w.minDelay, w.maxDelay + 1)
        val here = content.locations[npc.location] ?: return
        val target: String
        if (npc.trail.size >= w.steps) {
            target = npc.trail.last()
        } else {
            val options = here.exits.map { it.target }.distinct().filter { t ->
                val loc = content.locations[t]
                loc != null && loc.zone == here.zone && npcs[t]?.containsKey(npc.key) != true
            }
            if (options.isEmpty()) return
            target = options[random.nextInt(options.size)]
        }
        if (npcs[target]?.containsKey(npc.key) == true) return
        npcs[npc.location]?.remove(npc.key)
        if (npc.trail.isNotEmpty() && npc.trail.last() == target) npc.trail.removeLast() else npc.trail.addLast(npc.location)
        npc.location = target
        addNpc(npc)
    }

    // ---- reading and changing -----------------------------------------------------

    suspend fun npcsAt(loc: String): List<NpcView> = mutex.withLock {
        npcs[loc]?.values?.map { NpcView(it.key, it.name) } ?: emptyList()
    }

    suspend fun itemsAt(loc: String, now: Long): List<GroundItemView> = mutex.withLock {
        ground[loc]?.values?.filter { it.expiresAt == 0L || it.expiresAt > now }
            ?.map { GroundItemView(it.id, it.name, it.count, takeable = !it.id.startsWith("i.s.")) } ?: emptyList()
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
        putItem(loc, GroundItem(itemId, content.itemName(itemId), count, now + tz.shared.Rules.DROPPED_ITEM_LIFETIME))
    }

    /** Puts back a stack that could not be given to a player (database error). */
    suspend fun restore(loc: String, item: GroundItem) = mutex.withLock { putItem(loc, item) }

    // ---- for tests and diagnostics --------------------------------------------------

    suspend fun allNpcs(): List<Npc> = mutex.withLock { npcs.values.flatMap { it.values } }

    suspend fun npcCount(): Int = mutex.withLock { npcs.values.sumOf { it.size } }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
