package tz.server

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The live world between restarts (the old engine kept it in its l_i files):
 * [World.snapshot] is written to world_snapshot now and then and on shutdown,
 * and [World.restore]d on start, so pets, dropped things, corpses and dead
 * monsters' respawn times survive a new deploy.
 */
class WorldStore(private val db: Db) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Takes the snapshot under the game's lock (pets and owners stay consistent), writes it outside. */
    suspend fun save(game: Game) {
        val snapshot = game.lock.withLock { game.world.snapshot(game.clock()) }
        val data = json.encodeToString(WorldSnapshot.serializer(), snapshot)
        db.tx { c ->
            c.prepareStatement(
                "INSERT INTO world_snapshot (id, data, saved_at) VALUES (1, ?, now()) ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, saved_at = now()"
            ).use { it.setString(1, data); it.executeUpdate() }
        }
    }

    /** Restores the saved world; false if there is none or it cannot be read (the world then starts from content/). */
    suspend fun restore(world: World, now: Long): Boolean {
        val data = db.tx { c ->
            c.prepareStatement("SELECT data FROM world_snapshot WHERE id = 1").use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
        } ?: return false
        val snapshot = try { json.decodeFromString(WorldSnapshot.serializer(), data) } catch (e: Exception) { System.err.println("world snapshot unreadable: $e"); return false }
        if (snapshot.version != WorldSnapshot.VERSION) return false
        world.restore(snapshot, now)
        return true
    }
}

@Serializable
data class WorldSnapshot(
    val version: Int = VERSION,
    val savedAt: Long,
    val initial: List<String> = emptyList(),
    val npcs: List<NpcDto> = emptyList(),
    val items: List<ItemDto> = emptyList(),
    val corpses: List<CorpseDto> = emptyList(),
    val timers: List<TimerDto> = emptyList(),
    val nodes: List<NodeDto> = emptyList(),
) {
    companion object { const val VERSION = 1 }
}

@Serializable
data class ProtoDto(
    val template: String,
    val name: String,
    val hpMax: Int,
    val stats: Stats,
    val items: Map<String, Int> = emptyMap(),
    val randomItems: List<World.RandomLoot> = emptyList(),
    val butcher: Map<String, Int> = emptyMap(),
    val wander: World.Wander? = null,
    val respawn: World.Respawn? = null,
    val stock: List<World.StockLine> = emptyList(),
)

@Serializable
data class OwnerDto(
    val ownerId: Long,
    val follow: Long? = null,
    val guard: Long? = null,
    val until: Long = 0,
    val vanish: Boolean = false,
    val idleUntil: Long = 0,
    val home: String? = null,
    val flag: String? = null,
    val flagBelowY: Int = 0,
    val guardNpc: String? = null,
    val healAt: Long = 0,
)

@Serializable
data class NpcDto(
    val key: String,
    val home: String,
    val location: String,
    val hp: Int,
    val items: Map<String, Int> = emptyMap(),
    val proto: ProtoDto,
    val customName: String? = null,
    val owner: OwnerDto? = null,
    val criminal: Boolean = false,
    val goods: Map<String, Int> = emptyMap(),
    val restockAt: Long = 0,
    val expiresAt: Long = 0,
    val poisonUntil: Long = 0,
)

@Serializable
data class ItemDto(val location: String, val id: String, val name: String, val count: Int, val expiresAt: Long = 0)

@Serializable
data class CorpseDto(
    val location: String,
    val id: String,
    val name: String,
    val items: Map<String, Int> = emptyMap(),
    val butcher: Map<String, Int> = emptyMap(),
    val expiresAt: Long,
    val playerId: Long? = null,
    val free: Boolean = true,
    val clanId: Long? = null,
    val template: String? = null,
)

@Serializable
data class TimerDto(val at: Long, val location: String, val key: String, val proto: ProtoDto)

@Serializable
data class NodeDto(val key: String, val stock: Int, val regrowAt: Long)
