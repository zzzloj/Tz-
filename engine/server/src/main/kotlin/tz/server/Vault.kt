package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import tz.shared.ClanVaultItemView
import tz.shared.ClanVaultView
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.Rules
import java.sql.Connection

/**
 * The clan vault (owner 04.10.2026), in place of the four rented rooms of the
 * castle vaults (i.key.zhl*.dat): one per clan, 50 stacks, opened at any
 * banker or in the vault room of the clan's own castle; losing the castle
 * loses nothing. Whoever puts a thing chooses the lowest rank that may take it;
 * the one who put it and the head can always take it back.
 */
internal object VaultRules {
    fun rankOf(r: String?) = Rules.CLAN_RANK_ORDER.indexOf(r ?: "")
    fun mayTake(rank: String?, me: Long, access: String, owner: Long) =
        owner == me || rank == "head" || (rankOf(rank) >= 0 && rankOf(rank) >= rankOf(access))
    const val LOG_LINES = 30
}

private class VaultRow(val id: Long, val item: String, val count: Int, val access: String, val ownerId: Long, val owner: String)

private fun vaultRows(c: Connection, clanId: Long): List<VaultRow> =
    c.prepareStatement("SELECT id, item_id, count, access, owner_id, owner_name FROM clan_vault WHERE clan_id = ? ORDER BY item_id, id").use { st ->
        st.setLong(1, clanId)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(VaultRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getLong(5), rs.getString(6))) } }
    }

private fun vaultLog(c: Connection, clanId: Long, line: String) =
    c.prepareStatement("INSERT INTO clan_vault_log (clan_id, line) VALUES (?, ?)").use { st -> st.setLong(1, clanId); st.setString(2, line); st.executeUpdate() }

/** Where the vault may be opened: at a banker one is talking to, or in the own castle's vault room. */
private suspend fun Game.checkVaultPlace(p: Game.Player, npc: String) {
    if (npc.isNotEmpty()) {
        world.npc(p.location, npc) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_TARGET)
        if (p.talkingTo != npc) throw ApiException(HttpStatusCode.Conflict, Errors.TOPIC_CLOSED)
        bankerFee(p, npc)   // throws for anyone who is not a banker (or not this faction's)
    } else if (!inOwnVault(p)) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_VAULT_HERE)
}

internal suspend fun Game.inOwnVault(p: Game.Player): Boolean {
    val n = CastleRules.castleOf(p.location) ?: return false
    return p.clanId != null && p.location == "c.$n.hran" && castles()[n]?.clanId == p.clanId
}

internal suspend fun Game.vaultView(p: Game.Player, npc: String, message: String? = null): ClanVaultView {
    val clan = p.clanId ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_CLAN)
    val (rows, log) = db.tx { c ->
        vaultRows(c, clan) to if (p.clanRank == "head" || p.clanRank == "seneschal")
            c.prepareStatement(
                "SELECT to_char(at AT TIME ZONE 'Europe/Moscow', 'DD.MM HH24:MI') || ' ' || line FROM clan_vault_log WHERE clan_id = ? ORDER BY id DESC LIMIT ${VaultRules.LOG_LINES}"
            ).use { st -> st.setLong(1, clan); st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        else emptyList()
    }
    return ClanVaultView(
        npc, p.clanName ?: "",
        rows.map { r -> ClanVaultItemView(r.id, r.item, content.itemName(r.item), r.count, r.access, r.owner, VaultRules.mayTake(p.clanRank, p.id, r.access, r.ownerId)) },
        Rules.CLAN_VAULT_SLOTS, log, message,
    )
}

/** open; put [itemId] × [requested] for [access] and above; take [slot] × [requested]. */
suspend fun Game.vault(account: Account, op: String, npc: String, itemId: String, requested: Int, access: String, slot: Long): GameView = lock.withLock {
    val p = alive(player(account))
    loadClan(p)
    val clan = p.clanId ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_CLAN)
    checkVaultPlace(p, npc)
    val message: String? = when (op) {
        "open" -> null
        "put" -> {
            if (access !in Rules.CLAN_RANK_ORDER) throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
            if (!Rules.tradeable(itemId) || itemId == Society.FLAG) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TRADE)
            db.tx { c ->
                val row = inventoryRows(c, p.id).firstOrNull { it.first == itemId } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
                if (row.third) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TRADE)
                val count = requested.coerceIn(1, row.second)
                val rows = vaultRows(c, clan)
                if (rows.none { it.item == itemId && it.access == access && it.ownerId == p.id } && rows.size >= Rules.CLAN_VAULT_SLOTS)
                    throw ApiException(HttpStatusCode.Conflict, Errors.BANK_FULL)
                removeItem(c, p.id, itemId, count)
                c.prepareStatement(
                    "INSERT INTO clan_vault (clan_id, item_id, count, access, owner_id, owner_name) VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (clan_id, item_id, access, owner_id) DO UPDATE SET count = clan_vault.count + EXCLUDED.count, owner_name = EXCLUDED.owner_name"
                ).use { st ->
                    st.setLong(1, clan); st.setString(2, itemId); st.setInt(3, count); st.setString(4, access)
                    st.setLong(5, p.id); st.setString(6, p.name); st.executeUpdate()
                }
                val name = content.itemName(itemId)
                vaultLog(c, clan, "${p.name} положил $count $name (${Rules.CLAN_RANKS[access]?.lowercase()} и выше)")
                "Вы положили в хранилище клана $count $name"
            }
        }
        "take" -> db.tx { c ->
            val r = vaultRows(c, clan).firstOrNull { it.id == slot } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
            if (!VaultRules.mayTake(p.clanRank, p.id, r.access, r.ownerId)) throw ApiException(HttpStatusCode.Forbidden, Errors.VAULT_RIGHTS)
            val count = requested.coerceIn(1, r.count)
            if (count == r.count) c.prepareStatement("DELETE FROM clan_vault WHERE id = ?").use { it.setLong(1, r.id); it.executeUpdate() }
            else c.prepareStatement("UPDATE clan_vault SET count = count - ? WHERE id = ?").use { it.setInt(1, count); it.setLong(2, r.id); it.executeUpdate() }
            c.prepareStatement(
                "INSERT INTO character_items (character_id, item_id, count) VALUES (?, ?, ?) " +
                    "ON CONFLICT (character_id, item_id) DO UPDATE SET count = character_items.count + EXCLUDED.count"
            ).use { st -> st.setLong(1, p.id); st.setString(2, r.item); st.setInt(3, count); st.executeUpdate() }
            val name = content.itemName(r.item)
            vaultLog(c, clan, "${p.name} забрал $count $name" + if (r.ownerId != p.id) " (положил ${r.owner})" else "")
            "Вы забрали из хранилища клана $count $name"
        }
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
    }
    if (message != null) { refreshStats(p); p.log(message) }
    viewLocked(p).copy(vault = vaultView(p, npc, message))
}

private fun removeItem(c: Connection, characterId: Long, itemId: String, count: Int) {
    val deleted = c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ? AND count <= ?").use { st ->
        st.setLong(1, characterId); st.setString(2, itemId); st.setInt(3, count); st.executeUpdate()
    }
    if (deleted == 0) c.prepareStatement("UPDATE character_items SET count = count - ? WHERE character_id = ? AND item_id = ?").use { st ->
        st.setInt(1, count); st.setLong(2, characterId); st.setString(3, itemId); st.executeUpdate()
    }
}
