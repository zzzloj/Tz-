package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import tz.shared.CastleView
import tz.shared.DialogOption
import tz.shared.DialogView
import tz.shared.Errors
import tz.shared.GameView

/*
 * Clan castles c.1..c.5 (docs/mechanics-world.md §6.3, docs/quests.md §52,
 * f_castle.dat, f_castlein.dat, f_speako.dat): an undefended castle is
 * taken by walking into its gate; a defended one is stormed — its guards
 * attack strangers (Pvp.kt, lawTick), no crimes count inside.
 */

internal class CastleState(
    val id: Int,
    var clanId: Long?,
    var clanName: String?,
    var sign: String,
    var lockedUntil: Long,
    var openUntil: Long,
    var knockAt: Long,
    val guests: MutableSet<Long>,
)

/** A hired guard of a castle, in the world as an NPC. */
internal class CastleGuard(val key: String, val castle: Int, val template: String, var location: String, var until: Long)

internal object CastleRules {
    val KEEPERS = mapOf(1 to "n.Bosvar", 2 to "n.Savaron", 3 to "n.Zidvan", 4 to "n.Antars", 5 to "n.Valor")
    /** Contract per day (f_speako.dat:84-93). */
    val GUARD_DAY = mapOf("n.o.castle1" to 50, "n.o.castle2" to 60, "n.o.castle3" to 70, "n.o.castle4" to 100)
    val ROOMS = listOf("gate", "main", "tron", "sklad", "hran")
    const val LOCK_SECONDS = 10 * 3600L
    const val REOPEN_SECONDS = 8 * 3600L
    const val CAPTURE_OPEN_SECONDS = 2 * 3600L
    const val KNOCK_SECONDS = 180L
    const val GUARDS_PER_ROOM = 5

    fun castleOf(loc: String): Int? = Regex("^c\\.(\\d)\\.").find(loc)?.groupValues?.get(1)?.toIntOrNull()
    fun inside(loc: String) = castleOf(loc) != null && !loc.endsWith(".in")
}

/** Loads castles and guards once; puts the guards and the keepers into the world. */
internal suspend fun Game.castles(): Map<Int, CastleState> {
    castleCache?.let { return it }
    val now = clock()
    val loaded = db.tx { c ->
        val guests = HashMap<Int, MutableSet<Long>>()
        c.prepareStatement("SELECT castle_id, character_id FROM castle_guests").use { st ->
            st.executeQuery().use { rs -> while (rs.next()) guests.getOrPut(rs.getInt(1)) { HashSet() } += rs.getLong(2) }
        }
        val castles = c.prepareStatement(
            "SELECT c.id, c.clan_id, cl.name, c.sign, c.locked_until, c.open_until, c.knock_at FROM castles c LEFT JOIN clans cl ON cl.id = c.clan_id"
        ).use { st ->
            st.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val id = rs.getInt(1)
                        put(id, CastleState(id, rs.getLong(2).takeIf { !rs.wasNull() }, rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getLong(6), rs.getLong(7), guests[id] ?: HashSet()))
                    }
                }
            }
        }
        val guards = c.prepareStatement("SELECT key, castle_id, template, location, until FROM castle_guards").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(CastleGuard(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getLong(5))) } }
        }
        castles to guards
    }
    for (g in loaded.second) { castleGuards[g.key] = g; world.spawn(g.template, g.key, g.location, now) }
    // Fix: the keepers of castles 1 and 2 were missing from the 2007 world (docs/quests.md §52).
    for ((n, keeper) in CastleRules.KEEPERS) if (world.npc("c.$n.sklad", keeper) == null) world.spawn(keeper, keeper, "c.$n.sklad", now)
    castleCache = HashMap(loaded.first)
    return castleCache!!
}

/** A lock that ran out leaves the gate open and not lockable for 8 hours (f_castle.dat:33-40). */
private fun normalize(c: CastleState, now: Long): Boolean {
    if (c.lockedUntil != 0L && now >= c.lockedUntil) {
        c.openUntil = c.lockedUntil + CastleRules.REOPEN_SECONDS
        c.lockedUntil = 0
        return true
    }
    return false
}

private suspend fun Game.saveCastle(c: CastleState) = db.tx { conn ->
    conn.prepareStatement("UPDATE castles SET clan_id = ?, sign = ?, locked_until = ?, open_until = ?, knock_at = ? WHERE id = ?").use { st ->
        if (c.clanId == null) st.setNull(1, java.sql.Types.BIGINT) else st.setLong(1, c.clanId!!)
        st.setString(2, c.sign); st.setLong(3, c.lockedUntil); st.setLong(4, c.openUntil); st.setLong(5, c.knockAt); st.setInt(6, c.id)
        st.executeUpdate()
    }
}

/** A dissolved clan leaves its castles empty. */
internal suspend fun Game.clanGone(clanId: Long, name: String?) {
    for (c in castles().values.filter { it.clanId == clanId }) {
        c.clanId = null; c.clanName = null; c.lockedUntil = 0; c.sign = ""; c.guests.clear()
        saveCastle(c)
        chronicle("${castleName(c.id)} опустел: клан ${name ?: ""} распущен")
    }
}

/** Tells the online members of a clan (the old game wrote to their journals). */
private fun Game.tellClan(clanId: Long?, line: String) {
    if (clanId == null) return
    for (q in players.values) if (q.clanId == clanId && clock() - q.lastSeen < Game.ACTIVE_SECONDS) { q.log(line); notify(q.id) }
}

/** Anyone defending: a guard anywhere in the castle, or a living member or guest of the owners. */
private suspend fun Game.defended(c: CastleState, except: Long): Boolean {
    val now = clock()
    for (room in CastleRules.ROOMS) {
        val loc = "c.${c.id}.$room"
        if (world.npcsIn(loc).any { it.key.startsWith("n.o.") }) return true
        if (players.values.any { q ->
                q.id != except && q.location == loc && !q.ghost && now - q.lastSeen < Game.ACTIVE_SECONDS &&
                    (q.clanId != null && q.clanId == c.clanId || q.id in c.guests)
            }) return true
    }
    return false
}

/**
 * Checks a step into a castle (f_castle.dat). Returns why it is refused,
 * or null to go on; takes the castle when a clan member walks into an
 * undefended gate of a castle that is not theirs.
 */
internal suspend fun Game.castleEntry(p: Game.Player, from: String, to: String): String? {
    val n = CastleRules.castleOf(to) ?: return null
    if (!CastleRules.inside(to) || CastleRules.inside(from)) return null
    val c = castles().getValue(n)
    val now = clock()
    if (normalize(c, now)) saveCastle(c)
    val own = c.clanId != null && c.clanId == p.clanId
    val guest = p.id in c.guests
    if (now < c.lockedUntil) return "Ворота замка заперты изнутри, их могут открыть только хозяева замка, либо стражники откроют сами, когда истечёт срок."
    if (p.ghost && !own && !guest) return "Призрак не может войти в чужой замок."
    if (own || guest) return null
    if (c.clanId != null && defended(c, p.id)) {
        // An attack: the guards and the owners inside fight (f_castle.dat:46-72).
        tellClan(c.clanId, "На ваш замок ${castleName(n)} напали! ${p.name} у ворот.")
        chronicle("На замок ${castleName(n)} напал ${p.name}" + (p.clanName?.let { " (клан $it)" } ?: ""), c.clanId)
        return null
    }
    if (p.clanId != null && to.endsWith(".gate")) {
        val old = c.clanId
        val oldName = c.clanName
        c.clanId = p.clanId; c.clanName = p.clanName
        c.lockedUntil = 0; c.openUntil = now + CastleRules.CAPTURE_OPEN_SECONDS; c.sign = ""
        c.guests.clear()
        saveCastle(c)
        db.tx { conn -> conn.prepareStatement("DELETE FROM castle_guests WHERE castle_id = ?").use { it.setInt(1, n); it.executeUpdate() } }
        // The keeper's teleport contract belonged to the old owners.
        CastleRules.KEEPERS[n]?.let { k -> db.tx { conn -> conn.prepareStatement("DELETE FROM world_state WHERE key = ?").use { it.setString(1, "$k.contract"); it.executeUpdate() } } }
        tellClan(old, "Ваш замок ${castleName(n)} захватил клан ${p.clanName}!")
        p.log("Вы захватили замок ${castleName(n)}!")
        chronicle("Клан ${p.clanName} захватил ${castleName(n)}" + (oldName?.let { ", отбив его у клана $it" } ?: ""))
    }
    return null
}

private fun Game.castleName(n: Int) = content.locations["c.$n.gate"]?.name ?: "Замок $n"

internal suspend fun Game.castleView(p: Game.Player): CastleView? {
    val n = CastleRules.castleOf(p.location) ?: return null
    val c = castles().getValue(n)
    val now = clock()
    if (normalize(c, now)) saveCastle(c)
    val own = c.clanId != null && c.clanId == p.clanId
    return CastleView(
        id = n, name = castleName(n), owner = c.clanName, sign = c.sign,
        lockedMinutes = ((c.lockedUntil - now).coerceAtLeast(0) + 59) / 60,
        member = own, guest = p.id in c.guests,
        canOpen = own && now < c.lockedUntil,
        canKnock = !own && c.clanId != null && p.location.endsWith(".in") && !p.ghost,
    )
}

/** knock (outside, every 3 min), open (owners), sign (owners). */
suspend fun Game.castle(account: Account, op: String, text: String?): GameView = lock.withLock {
    val p = player(account)
    // To the castle under attack (f_castle.dat:17-30): only while strangers are inside.
    if (op == "tele") {
        val (_, room) = castleAlarm(alive(p), clock()) ?: run { p.log("В вашем замке спокойно"); return@withLock viewLocked(p) }
        for (npc in world.npcsIn(p.location)) npc.enemies.remove(p.id)
        tellOthers(p.location, p.id, "${p.name} исчез")
        p.location = room
        tellOthers(room, p.id, "Появился ${p.name}")
        save(p)
        return@withLock viewLocked(p)
    }
    val n = CastleRules.castleOf(p.location) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_A_TRADER)
    val c = castles().getValue(n)
    val now = clock()
    normalize(c, now)
    val own = c.clanId != null && c.clanId == p.clanId
    val msg = when (op) {
        "knock" -> when {
            p.ghost -> "Призраки не могут стучать в ворота"
            own || c.clanId == null -> "Это не нужно"
            now < c.knockAt -> "Стучать можно не чаще чем раз в 3 минуты"
            else -> {
                c.knockAt = now + CastleRules.KNOCK_SECONDS
                tellClan(c.clanId, "${p.name} стучит в ворота вашего замка ${castleName(n)}!")
                "Вы постучали в ворота, все, кто состоит в клане, которому принадлежит замок, извещены об этом."
            }
        }
        "open" -> if (own && now < c.lockedUntil) {
            c.lockedUntil = 0; c.openUntil = now + CastleRules.REOPEN_SECONDS
            "Вы открыли ворота, чтобы запереть их снова, поговорите со стражниками"
        } else "Ворота и так открыты"
        "sign" -> if (own) {
            c.sign = cleanSpeech(text ?: "").take(100)
            "Вывеска обновлена"
        } else throw ApiException(HttpStatusCode.Forbidden, Errors.CLAN_RIGHTS)
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
    }
    saveCastle(c)
    p.log(msg)
    viewLocked(p)
}

// ---- guards --------------------------------------------------------------------------------

/** Removes guards whose contract ran out. */
internal suspend fun Game.expireGuards(now: Long) {
    if (castleCache == null) return
    val gone = castleGuards.values.filter { it.until <= now }
    for (g in gone) {
        world.removeNpc(g.key, g.location)
        castleGuards.remove(g.key)
        db.tx { c -> c.prepareStatement("DELETE FROM castle_guards WHERE key = ?").use { it.setString(1, g.key); it.executeUpdate() } }
    }
}

/**
 * Lanset hires a guard (n.o.castle1..4): it goes straight to the gate of
 * the player's clan castle with a one-day contract. (In the old game the
 * player had to walk him there within 30 minutes; followers come later.)
 */
internal suspend fun Game.hireCastleGuard(p: Game.Player, template: String): String {
    val n = castles().values.firstOrNull { it.clanId != null && it.clanId == p.clanId }?.id
        ?: throw Game.HandlerFailed("У твоего клана нет замка, стражнику некого охранять. Сначала захвати замок.")
    if (world.npcsIn("c.$n.gate").count { it.key.startsWith("n.o.") } >= CastleRules.GUARDS_PER_ROOM)
        throw Game.HandlerFailed("У ворот твоего замка и так полно стражников.")
    val now = clock()
    val key = "$template.${rnd.nextInt(100, 100000)}"
    val g = CastleGuard(key, n, template, "c.$n.gate", now + 86400)
    db.tx { c ->
        c.prepareStatement("INSERT INTO castle_guards (key, castle_id, template, location, until) VALUES (?, ?, ?, ?, ?)").use { st ->
            st.setString(1, key); st.setInt(2, n); st.setString(3, template); st.setString(4, g.location); st.setLong(5, g.until); st.executeUpdate()
        }
    }
    castleGuards[key] = g
    world.spawn(template, key, g.location, now)
    return "Стражник уже отправлен к воротам замка ${castleName(n)}, контракт на сутки."
}

private suspend fun Game.saveGuard(g: CastleGuard) = db.tx { c ->
    c.prepareStatement("UPDATE castle_guards SET location = ?, until = ? WHERE key = ?").use { st ->
        st.setString(1, g.location); st.setLong(2, g.until); st.setString(3, g.key); st.executeUpdate()
    }
}

/** The guard's own conversation (f_speako.dat), answered by the server instead of content/dialogs. */
internal suspend fun Game.guardDialog(p: Game.Player, npc: World.Npc, g: CastleGuard, topic: String, arg: String?): DialogView {
    val c = castles().getValue(g.castle)
    val now = clock()
    normalize(c, now)
    fun say(text: String, options: List<DialogOption> = listOf(DialogOption("Ясно", "begin"))) = DialogView(npc.key, npc.name, text, options = options)
    if (c.clanId == null) return say("Этот замок никому не принадлежит, я не буду защищать его, пока не определится хозяин этой земли!", emptyList())
    if (c.clanId != p.clanId) return say("Вы не состоите в клане, которому принадлежит этот замок, поэтому мне нечего вам сказать. Найдите в замке барона Дитриха моего командира, стражника Лансета, он знает все о крепостях.", emptyList())
    val neophyte = p.clanRank == "neophyte"
    val day = CastleRules.GUARD_DAY[g.template] ?: 50
    return when (topic) {
        "move" -> {
            val target = "c.${g.castle}.${arg ?: ""}"
            when {
                arg !in CastleRules.ROOMS || target !in content.locations -> say("Сожалею, сир, но в вашем замке нет такого места")
                world.npcsIn(target).count { it.key.startsWith("n.o.") } >= CastleRules.GUARDS_PER_ROOM -> say("Сожалею, сир, но там уже полно стражников.")
                else -> {
                    world.removeNpc(g.key, g.location)
                    g.location = target
                    world.spawn(g.template, g.key, target, now)
                    saveGuard(g)
                    say("Слушаюсь, сир.", emptyList())
                }
            }
        }
        "close" -> when {
            arg != "yes" -> say("Вы действительно хотите закрыть ворота замка на ближайшие 10 часов? Если придёт кто-то из хозяев и откроет ворота, либо истекут эти 10 часов, то ворота нельзя будет закрыть в течение следующих 8 часов.",
                listOf(DialogOption("Да, опускайте решётку прямо сейчас!", "close", "yes"), DialogOption("Нет, я передумал", "begin")))
            now < c.lockedUntil -> say("Ворота и так закрыты!")
            now < c.openUntil -> say("Сожалею, мы не можем закрыть ворота ещё ${(c.openUntil - now + 59) / 60} мин., их открыли совсем недавно и рычаги ещё не вернулись в исходное состояние")
            else -> {
                c.lockedUntil = now + CastleRules.LOCK_SECONDS; c.openUntil = 0
                saveCastle(c)
                p.log("Вы заперли ворота замка на ближайшие 10 часов")
                say("Ворота заперты на 10 часов, сир.")
            }
        }
        "new" -> if (neophyte) say("Неофиты не могут приглашать гостей") else {
            val waiting = players.values.filter { it.location == "c.${g.castle}.in" && it.clanId != c.clanId && it.id !in c.guests && now - it.lastSeen < Game.ACTIVE_SECONDS }
            if (waiting.isEmpty()) say("У ворот замка никого нет")
            else say("Кого из ожидающих у ворот замка отныне считать гостем?", waiting.map { DialogOption(it.name, "add", it.name) } + DialogOption("Никого", "begin"))
        }
        "add" -> {
            val q = players.values.firstOrNull { it.name == arg && it.location == "c.${g.castle}.in" }
            if (neophyte || q == null) say("Сожалею, сир, у ворот нет такого человека.") else {
                c.guests += q.id
                db.tx { conn -> conn.prepareStatement("INSERT INTO castle_guests (castle_id, character_id) VALUES (?, ?) ON CONFLICT DO NOTHING").use { it.setInt(1, g.castle); it.setLong(2, q.id); it.executeUpdate() } }
                q.log("Вы теперь гость замка ${castleName(g.castle)}"); notify(q.id)
                say("${q.name} с этого момента является гостем этого замка и может беспрепятственно входить и выходить из него.")
            }
        }
        "list" -> {
            val names = db.tx { conn ->
                conn.prepareStatement("SELECT ch.name FROM castle_guests gu JOIN characters ch ON ch.id = gu.character_id WHERE gu.castle_id = ? ORDER BY ch.name").use { st ->
                    st.setInt(1, g.castle); st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            }
            if (names.isEmpty()) say("Сожалею, сир, но список гостей замка пуст.")
            else say("Кого больше не считать гостем замка?", names.map { DialogOption(it, "del", it) } + DialogOption("Никого", "begin"))
        }
        "del" -> {
            if (neophyte) return say("Неофиты не могут менять список гостей")
            val id = db.tx { conn ->
                conn.prepareStatement("DELETE FROM castle_guests gu USING characters ch WHERE ch.id = gu.character_id AND gu.castle_id = ? AND ch.name = ? RETURNING ch.id").use { st ->
                    st.setInt(1, g.castle); st.setString(2, arg ?: ""); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            }
            if (id == null) say("$arg не является гостем этого замка, сир.") else { c.guests -= id; say("$arg с этого момента больше не является гостем замка.") }
        }
        "status" -> say("Я буду на службе ещё ${(g.until - now) / 3600} ч. Вы хотите продлить контракт?",
            listOf(1, 3, 5).map { DialogOption("$it дн. — ${it * day} монет", "cont", it.toString()) } + DialogOption("Нет", "begin"))
        "cont" -> {
            val days = arg?.toIntOrNull()?.takeIf { it in setOf(1, 3, 5) } ?: return say("Не понял, на какой срок вы хотите продлить контракт?")
            val cost = days * day
            if ((inventoryMap(p)[tz.shared.Rules.MONEY] ?: 0) < cost) return say("У вас недостаточно денег (надо $cost монет)")
            changeItem(p, tz.shared.Rules.MONEY, -cost)
            g.until += days * 86400L
            saveGuard(g)
            say("Контракт продлён на $days дн. Клянусь защищать этот замок от любого, кто посмеет оспаривать ваши права на него.")
        }
        else -> {
            val here = g.location.substringAfterLast('.')
            val moves = if (here == "gate") listOf("tron" to "В тронный зал", "sklad" to "В кладовую", "main" to "Общий зал")
                else listOfNotNull("gate" to "К воротам", if (here == "sklad") "hran" to "В хранилище" else null, if (here == "hran") "sklad" to "В кладовую" else null)
            say("Что прикажете, сир?", moves.map { (to, label) -> DialogOption("Иди: $label", "move", to) } + listOf(
                DialogOption("Запереть ворота", "close"), DialogOption("Пропустить гостя...", "new"),
                DialogOption("Список гостей", "list"), DialogOption("Статус?", "status"),
            ))
        }
    }
}

// ---- keepers (raw/speak n.Antars and the others) ------------------------------------------

/** Handlers of the castle keepers' dialogs: access, rune choice, teleport contract, teleport. */
internal suspend fun Game.keeperHandler(
    p: Game.Player, npcKey: String, a: JsonObject, arg: String?, setOptions: (List<DialogOption>) -> Unit,
): String? {
    val n = CastleRules.KEEPERS.entries.firstOrNull { it.value == npcKey }?.key
        ?: CastleRules.castleOf(p.location) ?: throw Game.HandlerFailed("Хранителя нет на месте")
    val c = castles().getValue(n)
    when (a.str("handler")) {
        "castle-keeper-access" -> when {
            c.clanId == null -> throw Game.HandlerFailed("Этот замок никому не принадлежит, мне некому служить.")
            c.clanId != p.clanId -> throw Game.HandlerFailed("Я служу только клану, которому принадлежит этот замок.")
        }
        "castle-rune-list" -> {
            val runes = inventoryMap(p).keys.filter { it.startsWith("i.rr.") && it != "i.rr.empty" && it.removePrefix("i.rr.") in content.locations }
            if (runes.isEmpty()) throw Game.HandlerFailed("У вас нет помеченных рун телепорта.")
            setOptions(runes.map { DialogOption(content.locations.getValue(it.removePrefix("i.rr.")).name, "tele", it) } + DialogOption("Не сейчас", "end"))
        }
        "castle-contract" -> {
            if (c.clanId != p.clanId || p.clanRank != "head") throw Game.HandlerFailed("Вы не являетесь главой клана, которому принадлежит замок")
            val rune = arg?.takeIf { (inventoryMap(p)[it] ?: 0) > 0 && it.startsWith("i.rr.") } ?: throw Game.HandlerFailed("У вас нет этой руны")
            val cost = a.int("cost") ?: 500
            if ((inventoryMap(p)[tz.shared.Rules.MONEY] ?: 0) < cost) throw Game.HandlerFailed("У вас не достаточно денег")
            changeItem(p, tz.shared.Rules.MONEY, -cost)
            val until = clock() + (a.int("seconds") ?: 604800)
            db.tx { conn ->
                conn.prepareStatement("INSERT INTO world_state (key, value, until) VALUES (?, ?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, until = EXCLUDED.until").use { st ->
                    st.setString(1, a.str("contract") ?: "$npcKey.contract"); st.setString(2, rune.removePrefix("i.rr.")); st.setLong(3, until); st.executeUpdate()
                }
            }
        }
        "castle-teleport" -> {
            if (c.clanId != p.clanId) throw Game.HandlerFailed("Я служу только клану, которому принадлежит этот замок.")
            val target = db.tx { conn ->
                conn.prepareStatement("SELECT value FROM world_state WHERE key = ? AND (until IS NULL OR until > ?)").use { st ->
                    st.setString(1, a.str("contract") ?: "$npcKey.contract"); st.setLong(2, clock())
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }?.takeIf { it in content.locations } ?: throw Game.HandlerFailed("Глава клана не подписывал со мной контракта")
            p.location = target
            save(p)
            notifyLocation(target, except = p.id)
        }
    }
    return null
}
