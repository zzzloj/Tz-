package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import tz.shared.ClanMemberView
import tz.shared.ClanView
import tz.shared.ContactView
import tz.shared.Errors
import tz.shared.ExchangeView
import tz.shared.GameView
import tz.shared.MessageView
import tz.shared.MessagesView
import tz.shared.Rules
import tz.shared.ShopItemView
import java.sql.Connection
import java.sql.SQLException

/*
 * Talking to people (docs/mechanics-world.md §6): speech in a location,
 * private and clan messages, exchange of things, clans.
 */

// ---- speech -------------------------------------------------------------------------------

/**
 * The chat filter of f_say.dat, made narrower: the old `\w*ху\w*` also
 * silenced words like «худой»; here only the rude roots are beeped.
 */
internal fun cleanSpeech(text: String): String {
    var s = text.replace(Regex("<[^>]*>"), "").replace(Regex("[\\u0000-\\u001f\"'\\\\$&|<>]"), "")
    for (d in listOf(".ru", ".org", ".net", ".com", ".ua")) s = s.replace(d, "")
    val rude = listOf(
        "(?iu)(с|c)(у|y)(ч)?(к|k)(а|a)", "(?iu)пид(о|o|а|a)р\\w*", "(?iu)\\b(х|x)(у|y)(й|я|е|ё|и|ю|л)\\w*",
        "(?iu)\\w*пизд\\w*", "(?iu)\\b(е|ё|e)б(а|a|у|и|л|ё|е)\\w*", "(?iu)\\w*[ъь]еб\\w*", "(?iu)\\w*бля\\w*",
    )
    for (r in rude) s = s.replace(Regex(r), "[бип]")
    return s.trim().take(Rules.SAY_MAX)
}

/** Says something to everyone here, or to the clan (online or not: it goes to their messages). */
suspend fun Game.say(account: Account, raw: String, channel: String): GameView = lock.withLock {
    val p = player(account)
    val text = cleanSpeech(raw)
    if (text.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
    if (text == p.lastSaid) throw ApiException(HttpStatusCode.Conflict, Errors.SAID_ALREADY)
    p.lastSaid = text
    val now = clock()
    when (channel) {
        "clan" -> {
            val clan = p.clanId ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_CLAN)
            db.tx { c ->
                c.prepareStatement(
                    "INSERT INTO messages (to_id, from_name, clan, text) SELECT character_id, ?, true, ? FROM clan_members WHERE clan_id = ?"
                ).use { st -> st.setString(1, p.name); st.setString(2, text); st.setLong(3, clan); st.executeUpdate() }
            }
            for (q in players.values) if (q.clanId == clan && q.id != p.id) notify(q.id)
            p.log("[клан] ${p.name}: $text")
        }
        else -> {
            val line = "${p.name} говорит: $text"
            p.log(line)
            // A ghost is heard by all with spiritism·10 %, otherwise each listener hears with his spiritism·20 % (f_say.dat:69-83).
            val loud = !p.ghost || dice.roll(0, 100) <= p.skill("spirit") * 10
            for (q in players.values) if (q.id != p.id && q.location == p.location && now - q.lastSeen < Game.ACTIVE_SECONDS) {
                val heard = loud || dice.roll(0, 100) <= q.skill("spirit") * 20
                q.log(if (heard) line else "${p.name} говорит: " + text.replace(Regex("\\p{Lu}"), "O").replace(Regex("\\p{Ll}"), "o"))
                notify(q.id)
            }
        }
    }
    viewLocked(p)
}

// ---- contacts and messages ---------------------------------------------------------------

private fun characterByName(c: Connection, name: String): Pair<Long, String>? =
    c.prepareStatement("SELECT id, name FROM characters WHERE world_id = 1 AND lower(name) = lower(?)").use { st ->
        st.setString(1, name.trim())
        st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null }
    }

suspend fun Game.messages(account: Account): MessagesView = lock.withLock { messagesView(player(account)) }

private suspend fun Game.messagesView(p: Game.Player): MessagesView = db.tx { c ->
    val online = players.values.filter { clock() - it.lastSeen < Game.ACTIVE_SECONDS }.map { it.id }.toSet()
    val contacts = c.prepareStatement(
        "SELECT ch.id, ch.name, EXISTS (SELECT 1 FROM contacts b WHERE b.character_id = ch.id AND b.contact_id = ?) " +
            "FROM contacts a JOIN characters ch ON ch.id = a.contact_id WHERE a.character_id = ? ORDER BY ch.name"
    ).use { st ->
        st.setLong(1, p.id); st.setLong(2, p.id)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(ContactView(rs.getString(2), rs.getLong(1) in online, rs.getBoolean(3))) } }
    }
    val messages = c.prepareStatement(
        "SELECT from_name, text, extract(epoch FROM created_at)::bigint, clan, read FROM messages WHERE to_id = ? ORDER BY id DESC LIMIT 100"
    ).use { st ->
        st.setLong(1, p.id)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(MessageView(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4), rs.getBoolean(5))) } }
    }
    c.prepareStatement("UPDATE messages SET read = true WHERE to_id = ? AND NOT read").use { it.setLong(1, p.id); it.executeUpdate() }
    c.prepareStatement("DELETE FROM messages WHERE to_id = ? AND (created_at < now() - interval '30 days' OR id NOT IN (SELECT id FROM messages WHERE to_id = ? ORDER BY id DESC LIMIT 100))").use {
        it.setLong(1, p.id); it.setLong(2, p.id); it.executeUpdate()
    }
    MessagesView(contacts, messages)
}

/**
 * write: a private message to someone who has you in contacts (stored even
 * when they are offline — the old game only reached those online);
 * add: a contact standing here; remove: any contact.
 */
suspend fun Game.message(account: Account, op: String, to: String, text: String): MessagesView = lock.withLock {
    val p = player(account)
    when (op) {
        "add" -> {
            val q = players.values.firstOrNull { it.location == p.location && it.name.equals(to.trim(), ignoreCase = true) && it.id != p.id }
                ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
            db.tx { c ->
                c.prepareStatement("INSERT INTO contacts (character_id, contact_id) VALUES (?, ?) ON CONFLICT DO NOTHING").use {
                    it.setLong(1, p.id); it.setLong(2, q.id); it.executeUpdate()
                }
            }
        }
        "remove" -> db.tx { c ->
            val (id, _) = characterByName(c, to) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
            c.prepareStatement("DELETE FROM contacts WHERE character_id = ? AND contact_id = ?").use { it.setLong(1, p.id); it.setLong(2, id); it.executeUpdate() }
        }
        "write" -> {
            val body = cleanSpeech(text).take(Rules.MESSAGE_MAX)
            if (body.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
            val toId = db.tx { c ->
                val (id, _) = characterByName(c, to) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
                val allowed = c.prepareStatement("SELECT 1 FROM contacts WHERE character_id = ? AND contact_id = ?").use { st ->
                    st.setLong(1, id); st.setLong(2, p.id); st.executeQuery().use { it.next() }
                }
                if (!allowed) throw ApiException(HttpStatusCode.Forbidden, Errors.NOT_IN_CONTACTS)
                c.prepareStatement("INSERT INTO messages (to_id, from_name, text) VALUES (?, ?, ?)").use { st ->
                    st.setLong(1, id); st.setString(2, p.name); st.setString(3, body); st.executeUpdate()
                }
                id
            }
            notify(toId)
        }
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
    }
    messagesView(p)
}

internal suspend fun Game.unreadCount(p: Game.Player): Int = db.tx { c ->
    c.prepareStatement("SELECT count(*) FROM messages WHERE to_id = ? AND NOT read").use { st ->
        st.setLong(1, p.id); st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
    }
}

// ---- exchange (f_trade.dat) ---------------------------------------------------------------

/** One side of an exchange: whom it is with, what is offered, agreed or not. */
internal class ExchangeSide(val partner: Long) {
    val offer = LinkedHashMap<String, Int>()
    var agreed = false
}

internal suspend fun Game.exchangeView(p: Game.Player): ExchangeView? {
    val mine = exchanges[p.id] ?: return null
    val other = players[mine.partner] ?: return null
    val theirs = exchanges[other.id]?.takeIf { it.partner == p.id }
    fun list(m: Map<String, Int>) = m.map { (id, n) -> ShopItemView(id, content.itemName(id), 0, n) }
    return ExchangeView(other.name, theirs == null, list(mine.offer), list(theirs?.offer.orEmpty()), mine.agreed, theirs?.agreed == true)
}

/**
 * Exchange between two players standing together. Any change of either
 * offer cancels both agreements; when both agree, everything moves at
 * once in one transaction (the old one moved item by item and could stop
 * half-way).
 */
suspend fun Game.exchange(account: Account, op: String, with: String?, itemId: String?, count: Int): GameView = lock.withLock {
    val p = alive(player(account))
    val now = clock()
    fun partnerHere(s: ExchangeSide) = players[s.partner]?.takeIf { it.location == p.location && !it.ghost && now - it.lastSeen < Game.ACTIVE_SECONDS }
    when (op) {
        "start" -> {
            val q = players.values.firstOrNull { it.id != p.id && it.name.equals(with?.trim(), ignoreCase = true) && it.location == p.location && !it.ghost }
                ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
            exchanges[p.id]?.let { old -> if (old.partner != q.id) cancelExchange(p) }
            exchanges.getOrPut(p.id) { ExchangeSide(q.id) }
            val theirs = exchanges[q.id]
            q.log(if (theirs?.partner == p.id) "${p.name} открыл обмен с вами" else "${p.name} предлагает вам обмен")
            notify(q.id)
        }
        "add", "remove" -> {
            val mine = exchanges[p.id] ?: throw ApiException(HttpStatusCode.Conflict, Errors.NO_EXCHANGE)
            val id = itemId ?: throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
            if (op == "add") {
                if (!Rules.tradeable(id)) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TRADE)
                val rows = db.tx { c -> inventoryRows(c, p.id) }.firstOrNull { it.first == id }
                    ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
                if (rows.third) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TRADE)
                mine.offer[id] = count.coerceIn(1, rows.second)
            } else mine.offer.remove(id)
            mine.agreed = false
            exchanges[mine.partner]?.takeIf { it.partner == p.id }?.agreed = false
            players[mine.partner]?.let { notify(it.id) }
        }
        "agree" -> {
            val mine = exchanges[p.id] ?: throw ApiException(HttpStatusCode.Conflict, Errors.NO_EXCHANGE)
            val q = partnerHere(mine) ?: run { cancelExchange(p); throw ApiException(HttpStatusCode.Conflict, Errors.NO_EXCHANGE) }
            val theirs = exchanges[q.id]?.takeIf { it.partner == p.id }
            mine.agreed = true
            if (theirs != null && theirs.agreed) {
                db.tx { c ->
                    fun check(owner: Long, offer: Map<String, Int>) {
                        val have = inventoryRows(c, owner).associate { it.first to it.second }
                        for ((id, n) in offer) if ((have[id] ?: 0) < n) throw ApiException(HttpStatusCode.Conflict, Errors.NOT_IN_INVENTORY)
                    }
                    check(p.id, mine.offer); check(q.id, theirs.offer)
                    for ((id, n) in mine.offer) { takeRow(c, p.id, id, n); addItem(c, q.id, id, n) }
                    for ((id, n) in theirs.offer) { takeRow(c, q.id, id, n); addItem(c, p.id, id, n) }
                }
                exchanges.remove(p.id); exchanges.remove(q.id)
                p.log("Обмен с ${q.name} состоялся"); q.log("Обмен с ${p.name} состоялся")
                refreshStats(p); refreshStats(q)
            }
            notify(q.id)
        }
        "cancel" -> cancelExchange(p)
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
    }
    viewLocked(p)
}

private fun Game.cancelExchange(p: Game.Player) {
    val mine = exchanges.remove(p.id) ?: return
    val q = players[mine.partner] ?: return
    if (exchanges[q.id]?.partner == p.id) exchanges.remove(q.id)
    q.log("${p.name} отменил обмен")
    notify(q.id)
}

private fun takeRow(c: Connection, characterId: Long, itemId: String, count: Int) {
    val deleted = c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ? AND count <= ?").use { st ->
        st.setLong(1, characterId); st.setString(2, itemId); st.setInt(3, count); st.executeUpdate()
    }
    if (deleted == 0) c.prepareStatement("UPDATE character_items SET count = count - ? WHERE character_id = ? AND item_id = ?").use { st ->
        st.setInt(1, count); st.setLong(2, characterId); st.setString(3, itemId); st.executeUpdate()
    }
}

// ---- clans -------------------------------------------------------------------------------

/** Reads the character's clan into the in-memory player. */
internal suspend fun Game.loadClan(p: Game.Player) {
    val row = db.tx { c ->
        c.prepareStatement("SELECT cl.id, cl.name, m.rank FROM clan_members m JOIN clans cl ON cl.id = m.clan_id WHERE m.character_id = ?").use { st ->
            st.setLong(1, p.id)
            st.executeQuery().use { rs -> if (rs.next()) Triple(rs.getLong(1), rs.getString(2), rs.getString(3)) else null }
        }
    }
    p.clanId = row?.first; p.clanName = row?.second; p.clanRank = row?.third
}

internal suspend fun Game.clanInvites(p: Game.Player): List<String> = db.tx { c ->
    c.prepareStatement("SELECT cl.name FROM clan_invites i JOIN clans cl ON cl.id = i.clan_id WHERE i.character_id = ? ORDER BY cl.name").use { st ->
        st.setLong(1, p.id); st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }
}

private suspend fun Game.clanView(p: Game.Player, message: String? = null): ClanView {
    val invites = clanInvites(p)
    val clan = p.clanId ?: return ClanView(invites = invites, message = message)
    val now = clock()
    val (info, members) = db.tx { c ->
        val info = c.prepareStatement("SELECT info FROM clans WHERE id = ?").use { st ->
            st.setLong(1, clan); st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "" }
        }
        val members = c.prepareStatement(
            "SELECT ch.id, ch.name, m.rank FROM clan_members m JOIN characters ch ON ch.id = m.character_id WHERE m.clan_id = ? ORDER BY ch.name"
        ).use { st ->
            st.setLong(1, clan)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(ClanMemberView(rs.getString(2), rs.getString(3), players[rs.getLong(1)]?.let { now - it.lastSeen < Game.ACTIVE_SECONDS } == true))
                }
            }
        }
        info to members
    }
    return ClanView(p.clanName, p.clanRank, info, members, invites, canManage(p), message)
}

/** Head or seneschal holding a guild stone manage the clan (plugin/i.guildstone.dat). */
private suspend fun Game.canManage(p: Game.Player): Boolean =
    (p.clanRank == "head" || p.clanRank == "seneschal") && (inventoryMap(p)[Rules.GUILD_STONE] ?: 0) > 0

suspend fun Game.clan(account: Account): ClanView = lock.withLock { clanView(player(account)) }

suspend fun Game.clanAction(account: Account, op: String, name: String?, rank: String?, clanName: String?, text: String?): ClanView = lock.withLock {
    val p = player(account)
    val message: String = when (op) {
        "accept", "decline" -> {
            val cn = clanName ?: throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
            db.tx { c ->
                val id = c.prepareStatement("SELECT cl.id FROM clan_invites i JOIN clans cl ON cl.id = i.clan_id WHERE i.character_id = ? AND lower(cl.name) = lower(?)").use { st ->
                    st.setLong(1, p.id); st.setString(2, cn); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_CLAN)
                c.prepareStatement("DELETE FROM clan_invites WHERE character_id = ? AND clan_id = ?").use { it.setLong(1, p.id); it.setLong(2, id); it.executeUpdate() }
                if (op == "accept") {
                    if (p.clanId != null) throw ApiException(HttpStatusCode.Conflict, Errors.CLAN_RIGHTS)
                    c.prepareStatement("INSERT INTO clan_members (character_id, clan_id, rank) VALUES (?, ?, 'neophyte')").use {
                        it.setLong(1, p.id); it.setLong(2, id); it.executeUpdate()
                    }
                }
            }
            loadClan(p)
            if (op == "accept") "Вы вступили в клан ${p.clanName}" else "Приглашение отклонено"
        }
        "leave" -> leaveClan(p) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_CLAN)
        "invite", "kick", "rank", "head", "info" -> {
            val clan = p.clanId ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_CLAN)
            if (!canManage(p)) throw ApiException(HttpStatusCode.Forbidden, Errors.CLAN_RIGHTS)
            val head = p.clanRank == "head"
            when (op) {
                "info" -> {
                    if (!head) throw ApiException(HttpStatusCode.Forbidden, Errors.CLAN_RIGHTS)
                    db.tx { c -> c.prepareStatement("UPDATE clans SET info = ? WHERE id = ?").use { it.setString(1, cleanSpeech(text ?: "").take(500)); it.setLong(2, clan); it.executeUpdate() } }
                    "Описание клана сохранено"
                }
                "invite" -> {
                    // Invite someone standing here and not in a clan (the old rule).
                    val q = players.values.firstOrNull { it.location == p.location && it.name.equals(name?.trim(), ignoreCase = true) && it.id != p.id }
                        ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
                    if (q.clanId != null) return@withLock clanView(p, "${q.name} состоит в клане ${q.clanName}, а приглашать можно только тех, кто не состоит ни в одном клане")
                    db.tx { c -> c.prepareStatement("INSERT INTO clan_invites (clan_id, character_id) VALUES (?, ?) ON CONFLICT DO NOTHING").use { it.setLong(1, clan); it.setLong(2, q.id); it.executeUpdate() } }
                    q.log("${p.name} приглашает вас в клан ${p.clanName}"); notify(q.id)
                    "${q.name} приглашён в ваш клан"
                }
                else -> {
                    if (!head) throw ApiException(HttpStatusCode.Forbidden, Errors.CLAN_RIGHTS)
                    val (qid, qname) = db.tx { c -> characterByName(c, name ?: "") } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
                    if (qid == p.id) throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
                    val changed = db.tx { c ->
                        when (op) {
                            "kick" -> c.prepareStatement("DELETE FROM clan_members WHERE character_id = ? AND clan_id = ?").use { it.setLong(1, qid); it.setLong(2, clan); it.executeUpdate() }
                            "rank" -> {
                                if (rank !in setOf("seneschal", "vassal", "neophyte")) throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
                                c.prepareStatement("UPDATE clan_members SET rank = ? WHERE character_id = ? AND clan_id = ?").use { it.setString(1, rank); it.setLong(2, qid); it.setLong(3, clan); it.executeUpdate() }
                            }
                            else -> { // head: pass leadership
                                val n = c.prepareStatement("UPDATE clan_members SET rank = 'head' WHERE character_id = ? AND clan_id = ?").use { it.setLong(1, qid); it.setLong(2, clan); it.executeUpdate() }
                                if (n == 1) c.prepareStatement("UPDATE clan_members SET rank = 'seneschal' WHERE character_id = ?").use { it.setLong(1, p.id); it.executeUpdate() }
                                n
                            }
                        }
                    }
                    if (changed == 0) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_CLAN)
                    players[qid]?.let { q ->
                        loadClan(q)
                        q.log(when (op) {
                            "kick" -> "Вы выгнаны из клана ${p.clanName}!"
                            "rank" -> "Глава клана ${p.clanName} изменил ваш ранг на ${Rules.CLAN_RANKS[rank]}!"
                            else -> "Вы теперь глава клана ${p.clanName}!"
                        })
                        notify(q.id)
                    }
                    loadClan(p)
                    when (op) {
                        "kick" -> "Вы выгнали из клана $qname"
                        "rank" -> "Ранг у игрока $qname успешно изменён"
                        else -> "$qname теперь глава клана"
                    }
                }
            }
        }
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
    }
    p.log(message)
    clanView(p, message)
}

/** Leaves the clan; a head leaves only when alone (and the clan is dissolved). Null if not in a clan. */
private suspend fun Game.leaveClan(p: Game.Player): String? {
    val clan = p.clanId ?: return null
    val name = p.clanName
    val result = db.tx { c ->
        val count = c.prepareStatement("SELECT count(*) FROM clan_members WHERE clan_id = ?").use { st ->
            st.setLong(1, clan); st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        when {
            p.clanRank == "head" && count > 1 -> "Сначала передайте главенство другому члену клана (камнем гильдии)"
            p.clanRank == "head" -> {
                c.prepareStatement("DELETE FROM clans WHERE id = ?").use { it.setLong(1, clan); it.executeUpdate() }
                "Клан $name распущен"
            }
            else -> {
                c.prepareStatement("DELETE FROM clan_members WHERE character_id = ?").use { it.setLong(1, p.id); it.executeUpdate() }
                "Вы покинули клан $name"
            }
        }
    }
    loadClan(p)
    return result
}

/**
 * Dialog handlers of n.guildmaster (content/logic/n.guildmaster.json):
 * status line, leaving, typing the name, registering a clan, and the
 * «problem with my clan» check.
 */
internal suspend fun Game.clanHandler(p: Game.Player, a: JsonObject, vars: MutableMap<String, String>, askInput: (String) -> Unit): String? {
    when (a.str("handler")) {
        "clan-status" -> {
            vars["clanStatus"] = if (p.clanName == null) "не состоишь ни в одном клане."
                else "состоишь в клане ${p.clanName} (${Rules.CLAN_RANKS[p.clanRank]?.lowercase()})."
            vars["clanAction"] = when {
                p.clanName == null -> "Я хочу создать клан"
                p.clanRank == "head" -> "Я хочу распустить свой клан"
                else -> "Я хочу выйти из клана"
            }
        }
        "clan-leave" -> leaveClan(p)?.let { throw Game.HandlerFailed(it) }
        "clan-name-input" -> askInput(a.str("next") ?: "newnow2")
        "clan-create" -> {
            val name = vars["arg"] ?: ""
            if (p.clanId != null) throw Game.HandlerFailed("Ты уже состоишь в клане ${p.clanName}")
            if (!Rules.CLAN_NAME.matches(name)) throw Game.HandlerFailed("Имя клана — от 3 до 20 букв или цифр, без пробелов")
            val cost = a.int("cost") ?: Rules.CLAN_COST
            if ((inventoryMap(p)[Rules.MONEY] ?: 0) < cost) throw Game.HandlerFailed("Регистрация клана стоит $cost монет, у тебя столько нет")
            try {
                db.tx { c ->
                    val id = c.prepareStatement("INSERT INTO clans (name) VALUES (?) RETURNING id").use { st ->
                        st.setString(1, name); st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                    }
                    c.prepareStatement("INSERT INTO clan_members (character_id, clan_id, rank) VALUES (?, ?, 'head')").use {
                        it.setLong(1, p.id); it.setLong(2, id); it.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                if (e.sqlState == Accounts.UNIQUE_VIOLATION) throw Game.HandlerFailed("Клан $name уже есть в реестре, выбери другое имя")
                throw e
            }
            changeItem(p, Rules.MONEY, -cost)
            changeItem(p, a.str("give") ?: Rules.GUILD_STONE, 1)
            loadClan(p)
            vars["clan"] = name
        }
        "clan-restore" -> {
            // The clan tag is always read from the register now; this only confirms membership.
            val asked = vars["arg"] ?: ""
            if (p.clanName == null || !p.clanName.equals(asked, ignoreCase = true))
                throw Game.HandlerFailed("[читает записи] Хм, в реестре клана $asked тебя нет.")
            if (p.clanRank != "head") throw Game.HandlerFailed("[читает записи] Да, ты в клане ${p.clanName}, всё в порядке.")
        }
    }
    return null
}
