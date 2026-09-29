package tz.server

import io.ktor.http.HttpStatusCode
import tz.shared.CharacterView
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.InventoryItemView
import tz.shared.Protocol
import tz.shared.Rules
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/**
 * Player actions in the world: the game screen, moving, taking, dropping and
 * equipping items. Characters and inventories live in PostgreSQL, the world
 * (NPCs, items on the ground) in [World].
 */
class Game(
    private val content: Content,
    private val db: Db,
    private val accounts: Accounts,
    val world: World,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private data class Seen(val name: String, val location: String, val at: Long)

    /** Who was where recently, to show other players in a location. */
    private val presence = ConcurrentHashMap<Long, Seen>()

    suspend fun view(account: Account): GameView = view(requireCharacter(account))

    suspend fun move(account: Account, target: String): GameView = view(accounts.move(account, target))

    suspend fun take(account: Account, itemId: String): GameView {
        val ch = requireCharacter(account)
        if (itemId.startsWith("i.s.")) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TAKE)
        val now = clock()
        val item = world.take(ch.location, itemId, now) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
        try {
            db.tx { c ->
                c.prepareStatement(
                    "INSERT INTO character_items (character_id, item_id, count) VALUES (?, ?, ?) " +
                        "ON CONFLICT (character_id, item_id) DO UPDATE SET count = character_items.count + EXCLUDED.count"
                ).use { st ->
                    st.setLong(1, ch.id)
                    st.setString(2, item.id)
                    st.setInt(3, item.count)
                    st.executeUpdate()
                }
            }
        } catch (e: Exception) {
            world.restore(ch.location, item)
            throw e
        }
        return view(ch)
    }

    suspend fun drop(account: Account, itemId: String): GameView {
        val ch = requireCharacter(account)
        val count = db.tx { c ->
            c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ? RETURNING count").use { st ->
                st.setLong(1, ch.id)
                st.setString(2, itemId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
            }
        } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
        world.drop(ch.location, itemId, count, clock())
        return view(ch)
    }

    /** Puts an item on; whatever occupied the same slot (or conflicts, shield vs bow) comes off. */
    suspend fun equip(account: Account, itemId: String): GameView {
        val ch = requireCharacter(account)
        val slot = Rules.equipSlot(itemId) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_EQUIP)
        db.tx { c ->
            val owned = inventoryRows(c, ch.id)
            if (owned.none { it.first == itemId }) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
            val off = owned.filter { (id, _, equipped) ->
                equipped && id != itemId && (Rules.equipSlot(id) == slot || Rules.conflicts(id, itemId))
            }.map { it.first }
            for (id in off) setEquipped(c, ch.id, id, false)
            setEquipped(c, ch.id, itemId, true)
        }
        return view(ch)
    }

    suspend fun unequip(account: Account, itemId: String): GameView {
        val ch = requireCharacter(account)
        db.tx { c ->
            if (!setEquipped(c, ch.id, itemId, false)) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
        }
        return view(ch)
    }

    private suspend fun requireCharacter(account: Account): CharacterView =
        accounts.character(account) ?: throw ApiException(HttpStatusCode.Conflict, Errors.NO_CHARACTER)

    private suspend fun view(ch: CharacterView): GameView {
        val now = clock()
        presence[ch.id] = Seen(ch.name, ch.location, now)
        // A location removed from content/ must not lock the player out.
        val loc = content.locations[ch.location] ?: content.locations.getValue(Protocol.START_LOCATION)
        val others = presence.entries
            .filter { (id, s) -> id != ch.id && s.location == loc.id && now - s.at < PRESENCE_SECONDS }
            .map { it.value.name }
            .sorted()
        val location = loc.view().copy(
            npcs = world.npcsAt(loc.id),
            items = world.itemsAt(loc.id, now),
            players = others,
        )
        val inventory = db.tx { c -> inventoryRows(c, ch.id) }.map { (id, count, equipped) ->
            InventoryItemView(id, content.itemName(id), count, equipped, Rules.equipSlot(id) != null)
        }
        return GameView(ch, location, inventory)
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
        /** Characters active in the last 10 minutes are shown in their location. */
        const val PRESENCE_SECONDS = 600
    }
}
