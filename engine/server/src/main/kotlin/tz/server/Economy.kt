package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tz.shared.BankView
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.InventoryItemView
import tz.shared.Rules
import tz.shared.ShopItemView
import tz.shared.ShopView
import java.sql.Connection

/*
 * Trade with NPCs and the bank (docs/mechanics-world.md §4.2-4.4):
 * f_speakbuy*.dat, f_speaksell*.dat, f_speaktobank*.dat, f_speakfrombank*.dat.
 */

/** A dialog topic that is a raw engine value ("1.2|1200" for buy, "0.6|i.w.:i.log" for sell). */
private fun Game.traderLine(dialog: String, topic: String): List<String>? {
    val v = (content.dialogs[dialog]?.get("topics") as? JsonObject)?.get(topic)
        ?: (content.logic.rawTopics[dialog]?.get(topic)?.let { JsonPrimitive(it) })
        ?: return null
    return (v as? JsonPrimitive)?.contentOrNull?.split('|')
}

/** Coins per piece before the trader's multiplier; runes always 50 (f_speakbuyto.dat:4). */
internal fun Game.basePrice(id: String, dublons: Boolean = false): Int {
    if (id.startsWith("i.rr.")) return 50
    val base = Formulas.baseId(id)
    val o = (if (dublons) content.dublonPrices[base] else null) ?: content.items[base] ?: content.items[id]
    return o?.int("price") ?: 0
}

/** round(price · count · mult); a price of 1 or less ignores the multiplier (f_speakbuyto.dat:15-18). */
internal fun tradePrice(price: Int, count: Int, mult: Double): Int =
    Math.round(price * count * (if (price <= 1) 1.0 else mult)).toInt()

private fun traderRefuses(npcKey: String, inventory: Map<String, Int>): String? =
    if (npcKey == "n.Natan" && (inventory["i.q.hagen"] ?: 0) < 1 && (inventory["i.q.ditrih"] ?: 0) < 1)
        "Прости, но я продаю палладинское оружие и броню только стражникам барона Дитриха или тем кто имеет грамоту от Лорда Хагена"
    else null

internal suspend fun Game.inventoryMap(p: Game.Player): Map<String, Int> =
    db.tx { c -> inventoryRows(c, p.id) }.associate { it.first to it.second }

/** The trader's list for [mode] (buy, buy2 or sell). */
internal suspend fun Game.shopView(p: Game.Player, npc: World.Npc, dialog: String, mode: String, message: String? = null): ShopView {
    val line = traderLine(dialog, mode) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_A_TRADER)
    val mult = line.getOrNull(0)?.toDoubleOrNull() ?: 1.0
    val inventory = inventoryMap(p)
    val dublons = mode == "buy2"
    val currency = if (dublons) "дублонов" else "монет"
    traderRefuses(npc.key, inventory)?.let { return ShopView(npc.key, npc.name, mode, currency, emptyList(), it) }
    val items = when (mode) {
        "buy", "buy2" -> {
            val period = line.getOrNull(1)?.toIntOrNull() ?: 1200
            world.goods(npc, period, clock()).filter { it.value > 0 }.map { (id, n) ->
                ShopItemView(id, content.itemName(id) + if (".." in id) " *" else "", tradePrice(basePrice(id, dublons), 1, mult), n)
            }
        }
        else -> {
            val filter = line.getOrNull(1)?.split(':')?.filter { it.isNotEmpty() }.orEmpty()
            inventory.filter { (id, _) -> id != Rules.MONEY && (filter.isEmpty() || filter.any { it in id }) }
                .map { (id, n) -> ShopItemView(id, content.itemName(id) + if (".." in id) " *" else "", tradePrice(basePrice(id), 1, mult), n) }
        }
    }
    val empty = when {
        items.isNotEmpty() -> null
        mode == "sell" -> "Сожалею, но у вас нет интересующих меня товаров."
        else -> "У меня сейчас нет товаров на продажу, все раскупили. Приходи в другой раз."
    }
    return ShopView(npc.key, npc.name, mode, currency, items, message ?: empty)
}

/** Buys from or sells to the NPC the character is talking to. */
suspend fun Game.shop(account: Account, npcKey: String, mode: String, itemId: String, requested: Int): GameView = lock.withLock {
    val p = alive(player(account))
    val npc = world.npc(p.location, npcKey) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_TARGET)
    if (p.talkingTo != npcKey) throw ApiException(HttpStatusCode.Conflict, Errors.TOPIC_CLOSED)
    val line = traderLine(npcKey, mode) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_A_TRADER)
    val mult = line.getOrNull(0)?.toDoubleOrNull() ?: 1.0
    val inventory = inventoryMap(p)
    traderRefuses(npcKey, inventory)?.let { throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_A_TRADER) }
    val name = content.itemName(itemId)
    val message: String = when (mode) {
        "buy", "buy2" -> {
            val dublons = mode == "buy2"
            val currencyItem = if (dublons) Rules.DUBLON else Rules.MONEY
            val goods = world.goods(npc, line.getOrNull(1)?.toIntOrNull() ?: 1200, clock())
            val have = goods[itemId] ?: 0
            if (have < 1) throw ApiException(HttpStatusCode.Conflict, Errors.OUT_OF_STOCK)
            val count = requested.coerceIn(1, have)
            val price = tradePrice(basePrice(itemId, dublons), count, mult)
            if ((inventory[currencyItem] ?: 0) < price) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_ENOUGH_MONEY)
            if (!world.sellFromGoods(npc, itemId, count)) throw ApiException(HttpStatusCode.Conflict, Errors.OUT_OF_STOCK)
            changeItem(p, currencyItem, -price)
            changeItem(p, itemId, count)
            "Вы купили $count $name за $price ${if (dublons) "дублонов" else "монет"}"
        }
        "sell" -> {
            val filter = line.getOrNull(1)?.split(':')?.filter { it.isNotEmpty() }.orEmpty()
            if (itemId == Rules.MONEY || (filter.isNotEmpty() && filter.none { it in itemId }))
                throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_WANTED)
            val have = inventory[itemId] ?: 0
            if (have < 1) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
            val count = requested.coerceIn(1, have)
            val price = tradePrice(basePrice(itemId), count, mult)
            changeItem(p, itemId, -count)
            changeItem(p, Rules.MONEY, price)
            refreshStats(p)
            "Вы продали $count $name за $price монет"
        }
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
    }
    p.log(message)
    save(p)
    viewLocked(p).copy(shop = shopView(p, npc, npcKey, mode, message))
}

// ---- bank ------------------------------------------------------------------------------

/** Banker check (f_speak.dat:37-38) with the faction rule applied to operations too. */
private suspend fun Game.bankerFee(p: Game.Player, npcKey: String): Int {
    // Uin's "bank" topic was a slip that opened the bank at the start street; not carried over.
    if (npcKey == "n.beginner" || (content.dialogs[npcKey]?.get("topics") as? JsonObject)?.containsKey("bank") != true)
        throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_A_TRADER)
    val faction = db.tx { c ->
        c.prepareStatement("SELECT value FROM character_state WHERE character_id = ? AND key = 'faction'").use { st ->
            st.setLong(1, p.id)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "" }
        }
    }
    return when (npcKey) {
        "n.t.bankir" -> if (faction == "t") 20 else throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_A_TRADER)
        "n.p.bankir" -> if (faction == "p") 200 else throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_A_TRADER)
        else -> 0
    }
}

private fun bankRows(c: Connection, characterId: Long): List<Pair<String, Int>> =
    c.prepareStatement("SELECT item_id, count FROM character_bank WHERE character_id = ? ORDER BY item_id").use { st ->
        st.setLong(1, characterId)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) } }
    }

internal suspend fun Game.bankView(p: Game.Player, npc: World.Npc, message: String? = null): BankView {
    val fee = bankerFee(p, npc.key)
    val rows = db.tx { c -> bankRows(c, p.id) }
    return BankView(
        npc.key, npc.name,
        rows.map { (id, n) -> InventoryItemView(id, content.itemName(id), n, false, false) },
        fee, message,
    )
}

/** Puts things into the bank cell (op "put") or takes them out ("take"); one cell per character, shared by all bankers. */
suspend fun Game.bank(account: Account, npcKey: String, op: String, itemId: String, requested: Int): GameView = lock.withLock {
    val p = alive(player(account))
    val npc = world.npc(p.location, npcKey) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_TARGET)
    if (p.talkingTo != npcKey) throw ApiException(HttpStatusCode.Conflict, Errors.TOPIC_CLOSED)
    val fee = bankerFee(p, npcKey)
    val name = content.itemName(itemId)
    val message = db.tx { c ->
        val bank = bankRows(c, p.id).toMap()
        val inv = inventoryRows(c, p.id).associate { it.first to it.second }
        when (op) {
            "put" -> {
                val have = inv[itemId] ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
                val count = requested.coerceIn(1, have)
                if (itemId == Rules.MONEY && (bank[Rules.MONEY] ?: 0) + count > Rules.BANK_MONEY_MAX)
                    return@tx "Извините, в банке можно хранить не более ${Rules.BANK_MONEY_MAX} монет"
                if (itemId !in bank && bank.size >= Rules.BANK_STACKS_MAX) throw ApiException(HttpStatusCode.Conflict, Errors.BANK_FULL)
                val money = inv[Rules.MONEY] ?: 0
                if (fee > 0 && money - (if (itemId == Rules.MONEY) count else 0) < fee)
                    throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_ENOUGH_MONEY)
                moveRow(c, "character_items", "character_bank", p.id, itemId, count)
                if (fee > 0) moveRow(c, "character_items", null, p.id, Rules.MONEY, fee)
                "Вы положили в банк $count $name" + if (fee > 0) " (плата $fee монет)" else ""
            }
            "take" -> {
                val have = bank[itemId] ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
                val count = requested.coerceIn(1, have)
                moveRow(c, "character_bank", "character_items", p.id, itemId, count)
                "Вы забрали из банка $count $name"
            }
            else -> throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
        }
    }
    refreshStats(p)
    p.log(message)
    viewLocked(p).copy(bank = bankView(p, npc, message))
}

/** Moves [count] of an item between the backpack and bank tables (to = null: it just disappears, a fee). */
private fun moveRow(c: Connection, from: String, to: String?, characterId: Long, itemId: String, count: Int) {
    val deleted = c.prepareStatement("DELETE FROM $from WHERE character_id = ? AND item_id = ? AND count <= ?").use { st ->
        st.setLong(1, characterId); st.setString(2, itemId); st.setInt(3, count); st.executeUpdate()
    }
    if (deleted == 0) c.prepareStatement("UPDATE $from SET count = count - ? WHERE character_id = ? AND item_id = ?").use { st ->
        st.setInt(1, count); st.setLong(2, characterId); st.setString(3, itemId); st.executeUpdate()
    }
    if (to != null) c.prepareStatement(
        "INSERT INTO $to (character_id, item_id, count) VALUES (?, ?, ?) " +
            "ON CONFLICT (character_id, item_id) DO UPDATE SET count = $to.count + EXCLUDED.count"
    ).use { st ->
        st.setLong(1, characterId); st.setString(2, itemId); st.setInt(3, count); st.executeUpdate()
    }
}
