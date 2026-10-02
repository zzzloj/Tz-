package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import tz.shared.AdminRequest
import tz.shared.AdminView
import tz.shared.ChoiceOption
import tz.shared.Errors
import tz.shared.Rules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Moderators and administrators (the old f_mod.dat and f_admin.dat, without
 * the file editing). Roles live in accounts.role; TZ_ADMINS names the
 * administrators. Every action goes to mod_log.
 */
object Moderation {
    /** Quick teleport places of f_admin.dat and f_mod.dat. */
    val PLACES = listOf(
        "Начало" to "_begin", "Банк" to "x1092x474", "Кладбище" to "x1284x393", "Замок" to "x902x254",
        "Северные ворота" to "x1011x396", "Восточные ворота" to "x1259x510", "Южные ворота" to "x1173x603",
        "Пещера" to "x1486x247", "Крепость" to "x1778x493", "Ансалон" to "x2169x273", "Подземелье" to "x2247x786",
        "Остров" to "x147x1227", "Египет" to "x3099x100", "Обитель Бога" to "qv",
    )

    /** A moderator bans for at most a day (the old «бан» of f_mod.dat was 24 hours). */
    const val MODER_BAN_MINUTES = 24 * 60
    const val DEFAULT_MUTE_MINUTES = 24 * 60

    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Moscow"))

    fun date(unix: Long): String = DATE.format(Instant.ofEpochSecond(unix))

    fun rank(role: String) = when (role) { Roles.ADMIN -> 2; Roles.MODER -> 1; else -> 0 }
}

suspend fun Game.adminView(account: Account, message: String? = null): AdminView {
    if (!account.moderator) throw ApiException(HttpStatusCode.Forbidden, Errors.FORBIDDEN)
    return AdminView(
        account.role, message, accounts.modLog(),
        Moderation.PLACES.filter { it.second in content.locations }.map { ChoiceOption(it.first, it.second) },
        if (account.admin) content.logic.gifts.map { (key, name) -> ChoiceOption(name, key) } else emptyList(),
        if (account.admin) worldState(Dialogs.GIFT_NEW) else null,
    )
}

suspend fun Game.admin(account: Account, r: AdminRequest): AdminView {
    if (!account.moderator) throw ApiException(HttpStatusCode.Forbidden, Errors.FORBIDDEN)
    fun adminOnly() { if (!account.admin) throw ApiException(HttpStatusCode.Forbidden, Errors.FORBIDDEN) }
    suspend fun target(): Account {
        val t = accounts.byCharacter(r.target) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
        // Nobody acts on an equal or higher rank, except an administrator on himself.
        if (t.id != account.id && Moderation.rank(t.role) >= Moderation.rank(account.role)) throw ApiException(HttpStatusCode.Forbidden, Errors.FORBIDDEN)
        return t
    }
    val name = r.target.trim()
    val message: String = when (r.op) {
        "mute" -> {
            val t = target()
            val minutes = (if (r.minutes > 0) r.minutes else Moderation.DEFAULT_MUTE_MINUTES).coerceAtMost(30 * 24 * 60)
            val until = Accounts.now() + minutes * 60L
            accounts.setMute(t.id, until)
            accounts.log(account, "mute", name, "$minutes мин" + if (r.text.isNotBlank()) ": ${r.text.trim()}" else "")
            tellAccount(t.id, "Бан! Вам запрещено говорить до ${Moderation.date(until)}" + if (r.text.isNotBlank()) " (${r.text.trim()})" else "")
            "$name не может писать до ${Moderation.date(until)}"
        }
        "unmute" -> {
            val t = target()
            accounts.setMute(t.id, 0)
            accounts.log(account, "unmute", name)
            tellAccount(t.id, "Вам снова можно говорить")
            "$name снова может писать"
        }
        "ban" -> {
            val t = target()
            if (!account.admin && (r.minutes <= 0 || r.minutes > Moderation.MODER_BAN_MINUTES)) throw ApiException(HttpStatusCode.Forbidden, Errors.FORBIDDEN)
            val until = if (r.minutes > 0) Accounts.now() + r.minutes * 60L else Long.MAX_VALUE / 2
            accounts.setBan(t.id, until, r.text.trim())
            forgetAccount(t.id)
            accounts.log(account, "ban", name, (if (r.minutes > 0) "${r.minutes} мин" else "навсегда") + if (r.text.isNotBlank()) ": ${r.text.trim()}" else "")
            if (r.minutes > 0) "$name заблокирован до ${Moderation.date(until)}" else "$name заблокирован навсегда"
        }
        "unban" -> {
            adminOnly()
            val t = target()
            accounts.setBan(t.id, 0, "")
            accounts.log(account, "unban", name)
            "$name разблокирован"
        }
        "kick" -> {
            val t = target()
            db.tx { c -> c.prepareStatement("DELETE FROM sessions WHERE account_id = ?").use { it.setLong(1, t.id); it.executeUpdate() } }
            forgetAccount(t.id)
            accounts.log(account, "kick", name)
            "$name выброшен из игры"
        }
        "teleport" -> {
            val loc = r.text.trim()
            if (loc !in content.locations) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_FOUND)
            val who = if (name.isEmpty()) account else target()
            moveAccount(who.id, loc, "Вас перенесла неведомая сила")
            accounts.log(account, "teleport", name.ifEmpty { "себя" }, loc)
            (if (name.isEmpty()) "Вы" else name) + " → " + (content.locations[loc]?.name ?: loc)
        }
        "summon" -> {
            val t = target()
            val here = lock.withLock { byAccount[account.id]?.let { players[it] }?.location } ?: throw ApiException(HttpStatusCode.Conflict, Errors.NO_CHARACTER)
            moveAccount(t.id, here, "Вас призвали")
            accounts.log(account, "summon", name, here)
            "$name призван к вам"
        }
        "broadcast" -> {
            val text = r.text.replace(Regex("<[^>]*>"), "").trim().take(Rules.SAY_MAX)
            if (text.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
            lock.withLock { for (q in players.values) { q.log("Всем внимание: $text"); notify(q.id) } }
            accounts.log(account, "broadcast", "", text)
            "Отправлено всем"
        }
        "give" -> {
            adminOnly()
            val t = target()
            val item = r.item.trim()
            if (item !in content.items && item != Rules.MONEY) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
            val count = r.count.coerceIn(1, 100_000)
            lock.withLock {
                val charId = db.tx { c ->
                    val id = c.prepareStatement("SELECT id FROM characters WHERE account_id = ? AND world_id = 1").use { st ->
                        st.setLong(1, t.id); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                    } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
                    addItem(c, id, item, count)
                    id
                }
                players[charId]?.let { q -> q.log("Вы получили: ${content.itemName(item)}" + if (count > 1) " ×$count" else ""); refreshStats(q); notify(q.id) }
            }
            accounts.log(account, "give", name, "$item ×$count")
            "$name получил ${content.itemName(item)} ×$count"
        }
        "gift" -> {
            // A kit for Edward to hand out: to one character, or to everybody with target «*».
            adminOnly()
            val kit = r.item.trim()
            val kitName = content.logic.gifts[kit] ?: throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
            val key = Dialogs.GIFT_PREFIX + kit
            val line = "Вас ждёт подарок: «$kitName». Заберите его у Эдварда в Переулке."
            if (name == "*") {
                val n = db.tx { c ->
                    c.prepareStatement(
                        "INSERT INTO character_state (character_id, key, value, until) SELECT id, ?, '1', NULL FROM characters WHERE world_id = 1 " +
                            "ON CONFLICT (character_id, key) DO UPDATE SET value = EXCLUDED.value, until = EXCLUDED.until"
                    ).use { it.setString(1, key); it.executeUpdate() }
                }
                lock.withLock { for (q in players.values) { q.log(line); notify(q.id) } }
                accounts.log(account, "gift", "*", "$kit ($n)")
                "Подарок «$kitName» — всем ($n)"
            } else {
                val t = target()
                val charId = db.tx { c ->
                    c.prepareStatement("SELECT id FROM characters WHERE account_id = ? AND world_id = 1").use { st ->
                        st.setLong(1, t.id); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                    }
                } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
                setState(charId, key, "1", null)
                tellAccount(t.id, line)
                accounts.log(account, "gift", name, kit)
                "$name: подарок «$kitName»"
            }
        }
        "giftNew" -> {
            // The kit every new character gets from now on; an empty kit stops it.
            adminOnly()
            val kit = r.item.trim()
            if (kit.isEmpty()) {
                db.tx { c -> c.prepareStatement("DELETE FROM world_state WHERE key = ?").use { it.setString(1, Dialogs.GIFT_NEW); it.executeUpdate() } }
                accounts.log(account, "giftNew", "", "—")
                "Новым персонажам подарок не выдаётся"
            } else {
                val kitName = content.logic.gifts[kit] ?: throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
                setWorldState(Dialogs.GIFT_NEW, kit, null)
                accounts.log(account, "giftNew", "", kit)
                "Новые персонажи получат подарок «$kitName»"
            }
        }
        "role" -> {
            adminOnly()
            val t = accounts.byCharacter(name) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
            val role = r.text.trim()
            if (role != Roles.PLAYER && role != Roles.MODER) throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
            if (t.admin) throw ApiException(HttpStatusCode.Forbidden, Errors.FORBIDDEN)
            accounts.setRole(t.id, role)
            accounts.log(account, "role", name, role)
            "$name: " + if (role == Roles.MODER) "модератор" else "игрок"
        }
        "log" -> "Журнал модерации"
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
    }
    return adminView(account, message)
}

private suspend fun Game.worldState(key: String): String? = db.tx { c ->
    c.prepareStatement("SELECT value FROM world_state WHERE key = ? AND (until IS NULL OR until > ?)").use { st ->
        st.setString(1, key); st.setLong(2, Accounts.now()); st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }
}

/** A line in the journal of whoever plays [accountId] now. */
private suspend fun Game.tellAccount(accountId: Long, line: String) = lock.withLock {
    byAccount[accountId]?.let { players[it] }?.let { it.log(line); notify(it.id) }
}

/** Moves a character, online or not. */
private suspend fun Game.moveAccount(accountId: Long, loc: String, line: String) = lock.withLock {
    val p = byAccount[accountId]?.let { players[it] }
    if (p != null) {
        val from = p.location
        p.location = loc
        p.attackTarget = null
        p.log(line)
        save(p)
        notify(p.id)
        notifyLocation(from, except = p.id)
        notifyLocation(loc, except = p.id)
    } else db.tx { c ->
        c.prepareStatement("UPDATE characters SET location = ? WHERE account_id = ? AND world_id = 1").use { it.setString(1, loc); it.setLong(2, accountId); it.executeUpdate() }
    }
}

/** Takes a character out of the running world (banned, kicked, deleted): the flag falls, an exchange ends. */
internal suspend fun Game.forgetAccount(accountId: Long) = lock.withLock {
    val p = byAccount[accountId]?.let { players[it] } ?: return@withLock
    if (p.hasFlag) dropFlag(p, "${p.name} потерял флаг!")
    if (exchanges.containsKey(p.id)) cancelExchange(p)
    proposals.remove(p.id)
    save(p)
    players.remove(p.id)
    byAccount.remove(accountId)
    notifyLocation(p.location, except = p.id)
    notify(p.id)
}

/** Deletes the account for good after checking the password. */
suspend fun Game.deleteAccount(account: Account, password: String) {
    accounts.delete(account, password)
    forgetAccount(account.id)
}
