package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import tz.shared.CastleSummary
import tz.shared.ClanSummary
import tz.shared.DialogOption
import tz.shared.Errors
import tz.shared.MapPoint
import tz.shared.MapView
import tz.shared.OnlineView
import tz.shared.Rules
import tz.shared.WorldView

/*
 * Stage 12, society and the last quests: the leadership flag (docs/mechanics-world.md §6.6),
 * marriage (§6.4, quest 27), the thieves' guild contract (quest 46), Tomrak's
 * blessings, Gred's bouquet order (quest 36) and bouquets, the demon of the
 * elements (quest 25), kill-and-collect orders, the castle alarm, the world
 * pages (who is online, clans, castles, the flag) and the map.
 */

internal object Society {
    const val FLAG = "i.flag"
    /** Where the flag first lies, and where it goes when dropped in a tavern room. */
    const val FLAG_START = "x1086x501"
    const val TAVERN_HALL = "x1095x532"
    const val RING = "i.ring.z"
    const val WEDDING_BOUQUET = "i.buket"
    const val DIVORCE = 500
    /** The thieves' guild gives contracts only with this many characters online (the old code: 30). */
    const val THIEVES_MIN_ONLINE = 30
    val DEMON_PLACES = listOf("x1141x50", "x1199x47", "x1182x57", "x1225x55", "x1071x46", "x1034x54")
    const val DEMON = "n.c.demogn"
    const val GRED_START = "i.bc_gred_i1h1p1o1"
    const val GRED_DONE = "i.bc_gred_i150h30p50o80"
}

// ---- the leadership flag ---------------------------------------------------------------------

/** Loads the flag once (world_state "flag") and puts it back on the ground if nobody holds it and it is not there. */
internal suspend fun Game.ensureFlag(now: Long) {
    if (!flagLoaded) {
        flagLoaded = true
        val saved = db.tx { c ->
            c.prepareStatement("SELECT value FROM world_state WHERE key = 'flag'").use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
        }
        // After a restart nobody is holding anything: the flag lies where it was.
        flagLoc = saved?.substringAfter('|')?.takeIf { it in content.locations } ?: Society.FLAG_START
        flagHolder = null
    }
    val loc = flagLoc ?: Society.FLAG_START
    if (flagHolder == null && world.itemsAt(loc, now).none { it.id == Society.FLAG }) world.placePermanent(loc, Society.FLAG, 1)
}

private suspend fun Game.saveFlag() = db.tx { c ->
    c.prepareStatement("INSERT INTO world_state (key, value) VALUES ('flag', ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value").use { st ->
        st.setString(1, "${flagHolder ?: ""}|${flagLoc ?: ""}"); st.executeUpdate()
    }
}

/** Picks the flag up (f_take.dat:7-15). */
internal suspend fun Game.takeFlag(p: Game.Player, now: Long) {
    ensureFlag(now)
    if (flagHolder != null || !world.removeItem(p.location, Society.FLAG)) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
    flagHolder = p.id
    flagLoc = p.location
    p.hasFlag = true
    refreshStats(p)
    saveFlag()
    p.log("Вы подняли флаг лидерства! Пока он у вас: +10 к здоровью, +20 к заклинаниям, +10 к приёмам, но напасть на вас может любой без преступления.")
    tellOthers(p.location, p.id, "${p.name} поднял флаг лидерства!")
}

/** The holder drops the flag (a drop, death, leaving the game): out of tavern rooms it goes to the hall. */
internal suspend fun Game.dropFlag(p: Game.Player, line: String) {
    if (!p.hasFlag) return
    p.hasFlag = false
    flagHolder = null
    val loc = if (p.location in Spells.TAVERN) Society.TAVERN_HALL else p.location
    flagLoc = loc
    world.placePermanent(loc, Society.FLAG, 1)
    refreshStats(p)
    saveFlag()
    tellOthers(p.location, p.id, line)
}

// ---- marriage ------------------------------------------------------------------------------

internal suspend fun Game.setSpouse(p: Game.Player, spouseId: Long?, spouseName: String?) {
    p.spouseId = spouseId
    p.spouseName = spouseName
    if (spouseId == null) clearState(p.id, "spouse") else setState(p.id, "spouse", "$spouseId|$spouseName", null)
}

/**
 * n.Lajma (quest 27, f_speak Lajma eval): [step] divorce / pending / choose /
 * propose / accept / decline. Rings are needed and at least two witnesses.
 */
internal suspend fun Game.weddingHandler(p: Game.Player, a: JsonObject, arg: String?, vars: MutableMap<String, String>,
                                        options: (List<DialogOption>) -> Unit) {
    val now = clock()
    fun here(id: Long?) = id?.let { players[it] }?.takeIf { it.location == p.location && now - it.lastSeen < Game.ACTIVE_SECONDS }
    when (a.str("step")) {
        "divorce" -> {
            vars["partner"] = p.spouseName ?: ""
            val other = p.spouseId
            setSpouse(p, null, null)
            if (other != null) {
                players[other]?.let { q -> setSpouse(q, null, null); q.log("${p.name} развёлся с вами"); notify(q.id) }
                    ?: clearState(other, "spouse")
            }
        }
        "pending" -> {
            val from = proposals[p.id]?.let { players[it] } ?: throw Game.HandlerFailed("")
            vars["partner"] = from.name
            options(listOf(DialogOption("Да!", "svok", "u:${from.id}"), DialogOption("Нет, не хочу!", "svno", "u:${from.id}")))
        }
        "choose" -> {
            if (p.spouseId != null) throw Game.HandlerFailed("Извини, <imja>, но ты уже состоишь в браке, сначала нужно развестись.")
            val others = players.values.filter { it.id != p.id && it.location == p.location && now - it.lastSeen < Game.ACTIVE_SECONDS && !it.ghost }
            options(others.map { DialogOption(it.name, "svadd", "u:${it.id}") } + DialogOption("Я передумал", "end"))
        }
        "propose" -> {
            if (p.spouseId != null) throw Game.HandlerFailed("Извини, <imja>, но ты уже состоишь в браке, сначала нужно развестись.")
            val q = here(arg?.removePrefix("u:")?.toLongOrNull()) ?: throw Game.HandlerFailed("Его рядом нет")
            if (q.sex == p.sex) throw Game.HandlerFailed("Однополые браки недопустимы")
            if (q.spouseId != null) throw Game.HandlerFailed("${q.name} уже в браке")
            proposals[q.id] = p.id
            vars["partner"] = q.name
            q.log("${p.name} предлагает вам своё сердце и руку (поговорите с Лаймой)"); notify(q.id)
            tellOthers(p.location, p.id, "Лайма говорит: ${p.name} предлагает ${q.name} своё сердце и руку")
        }
        "accept" -> {
            if (p.spouseId != null) throw Game.HandlerFailed("Извини, <imja>, но ты уже состоишь в браке, сначала нужно развестись.")
            val q = here(arg?.removePrefix("u:")?.toLongOrNull()) ?: throw Game.HandlerFailed("Его рядом нет")
            if (proposals[p.id] != q.id) throw Game.HandlerFailed("Статус изменился, кто-то рядом с вами тоже пытается сыграть свадьбу и поэтому произошла путаница?")
            if (q.spouseId != null) throw Game.HandlerFailed("${q.name} уже в браке")
            val witnesses = players.values.count { it.location == p.location && now - it.lastSeen < Game.ACTIVE_SECONDS }
            if (witnesses <= 3) throw Game.HandlerFailed("Недостаточно свидетелей, надо минимум два человека, кроме молодожёнов")
            val rings = db.tx { c -> listOf(p, q).all { x -> inventoryRows(c, x.id).any { it.first == Society.RING } } }
            if (!rings) {
                tellOthers(p.location, p.id, "Лайма говорит: а где же золотые кольца, молодожёны? Без колец свадьба не может состояться!")
                throw Game.HandlerFailed("У каждого из молодожёнов должно быть по золотому кольцу")
            }
            proposals.remove(p.id)
            setSpouse(p, q.id, q.name)
            setSpouse(q, p.id, p.name)
            changeItem(p, Society.WEDDING_BOUQUET, 1)
            changeItem(q, Society.WEDDING_BOUQUET, 1)
            vars["partner"] = q.name
            val line = "Лайма говорит: молодожёны, обменяйтесь кольцами. ${q.name} и ${p.name}, объявляю вас мужем и женой!"
            tellOthers(p.location, p.id, line)
            notify(q.id)
        }
        "decline" -> {
            val q = proposals.remove(p.id)?.let { players[it] }
            vars["partner"] = q?.name ?: ""
            q?.let { it.log("${p.name} не желает вступать с вами в брак"); notify(it.id) }
        }
    }
}

/** The marriage stands only while both say so (f_site_connect2.dat:131-141). */
internal suspend fun Game.checkMarriage(p: Game.Player) {
    val other = p.spouseId ?: return
    val back = stateOf(other, "spouse")
    if (back == null || !back.startsWith("${p.id}|")) {
        setSpouse(p, null, null)
        p.log("Ваш брак расторгнут")
    }
}

/** A wounded spouse (f_attackf.dat:115-130): the other gets a way to them for 5 minutes, once in 5 minutes. */
internal fun Game.woundedSpouse(b: Game.Player, now: Long) {
    if (b.hp >= b.hpMax || now < b.steleNext) return
    val q = b.spouseId?.let { players[it] } ?: return
    if (now - q.lastSeen >= Game.ACTIVE_SECONDS || q.location == b.location) return
    b.steleNext = now + 300
    q.steleTo = b.location
    q.steleUntil = now + 300
    val place = content.locations[b.location]?.name ?: b.location
    q.log("Ваш${if (q.sex == "f") "" else "а"} ${b.spouseLabelFor(q)} ($place) ранен${if (b.sex == "f") "а" else ""}!")
    notify(q.id)
}

private fun Game.Player.spouseLabelFor(q: Game.Player) = if (q.sex == "f") "муж" else "жена"

/** Goes to the wounded spouse (f_stele.dat). */
internal suspend fun Game.stele(p: Game.Player, now: Long) {
    val to = p.steleTo?.takeIf { now < p.steleUntil && it in content.locations } ?: run { p.log("Уже поздно"); return }
    for (npc in world.npcsIn(p.location)) npc.enemies.remove(p.id)
    tellOthers(p.location, p.id, "${p.name} исчез")
    p.location = to
    p.steleTo = null
    tellOthers(to, p.id, "Появился ${p.name}")
    p.log("Вы спешите на помощь")
}

// ---- quests and handlers ---------------------------------------------------------------------

/** Handlers of stage 12; a HandlerFailed shows its text. */
internal suspend fun Game.societyHandler(p: Game.Player, a: JsonObject, arg: String?, vars: MutableMap<String, String>,
                                         options: (List<DialogOption>) -> Unit): String? {
    val now = clock()
    when (a.str("handler")) {
        "wedding" -> weddingHandler(p, a, arg, vars, options)
        // Tomrak: armour +5 till the equipment changes, or max health +10 (n.telemag kast1/kast2).
        "tomrak-armor" -> { p.clearBuffs(); p.armorBuff = 5; refreshStats(p) }
        "tomrak-life" -> { p.hpBuff = 10; p.regenFrom = now }
        "gred-bouquet-give" -> changeItem(p, Society.GRED_START, 1)
        "gred-bouquet-take" -> {
            if ((inventoryMap(p)[Society.GRED_DONE] ?: 0) < 1) throw Game.HandlerFailed("Я не вижу у тебя никакого букета")
            changeItem(p, Society.GRED_DONE, -1)
            changeItem(p, "i.i.kc", 1)
            addExp(p, 100)
        }
        "thieves-contract" -> return thievesContract(p, now)
        // The SMS services of 2007 are gone.
        "smsCode", "claim-dublons" -> throw Game.HandlerFailed("Эта услуга старой игры больше не работает")
    }
    return null
}

/**
 * The thieves' guild (quest 46, n.Rudolf): a contract on a random character
 * online; kill him and come back for 1000 coins, thief's gloves and 100 experience.
 */
private suspend fun Game.thievesContract(p: Game.Player, now: Long): String {
    suspend fun readWorld(key: String) = db.tx { c ->
        c.prepareStatement("SELECT value FROM world_state WHERE key = ? AND (until IS NULL OR until > ?)").use { st ->
            st.setString(1, key); st.setLong(2, now); st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }
    suspend fun setWorld(key: String, value: String?, until: Long?) = db.tx { c ->
        if (value == null) c.prepareStatement("DELETE FROM world_state WHERE key = ?").use { it.setString(1, key); it.executeUpdate() }
        else c.prepareStatement("INSERT INTO world_state (key, value, until) VALUES (?, ?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, until = EXCLUDED.until").use { st ->
            st.setString(1, key); st.setString(2, value); if (until == null) st.setNull(3, java.sql.Types.BIGINT) else st.setLong(3, until); st.executeUpdate()
        }
    }
    var target = readWorld("thieves.target")
    val killed = stateOf(p.id, "pk")
    var prefix = ""
    if (target != null && killed == target && target != p.name) {
        setState(p.id, "pk", "—", null)
        changeItem(p, Rules.MONEY, 1000)
        changeItem(p, "i.q.pervor", 1)
        addExp(p, 100)
        setWorld("thieves.target", null, null)
        setWorld("thieves.next", "1", now + 60L * dice.roll(1, 120))
        prefix = "Чистая работа, молодец. Твоя награда за убийство $target составляет 1000 монет и эти перчатки вора. "
        target = null
    }
    val online = players.values.filter { now - it.lastSeen < Game.ACTIVE_SECONDS }
    if (target == null || online.none { it.name == target }) {
        target = null
        if (online.size < Society.THIEVES_MIN_ONLINE) {
            setWorld("thieves.target", null, null)
            return prefix + "Сожалею, слишком мало игроков онлайн, нужно как минимум ${Society.THIEVES_MIN_ONLINE} человек."
        }
        val next = readWorld("thieves.next")
        if (next != null) return prefix + "Извини, сейчас нет заказов."
        val pick = online.filter { it.id != p.id }.randomOrNull(rnd) ?: return prefix + "Извини, сейчас нет заказов."
        target = pick.name
        setWorld("thieves.target", target, null)
    }
    return prefix + if (target == p.name) "Уп-с, кажется, уже кто-то заказал тебя... Награду за себя не получишь, придётся подождать смены заказа."
    else "Так-с, есть заказ на убийство $target, награда 1000 монет, а также наши фирменные перчатки вора."
}

/** Kill-and-collect orders (f_kill.dat:83-86): flag «kvest» = template|item|max|chance. */
internal suspend fun Game.killOrder(p: Game.Player, npc: World.Npc) {
    val order = stateOf(p.id, "kvest")?.split('|') ?: return
    if (order.size < 4 || order[0] !in npc.key) return
    val max = order[2].toIntOrNull() ?: return
    if (dice.roll(0, 100) <= (order[3].toIntOrNull() ?: 0) && (inventoryMap(p)[order[1]] ?: 0) < max) {
        changeItem(p, order[1], 1)
        p.log("Вы нашли: ${content.itemName(order[1])}")
    }
}

/** The vial of the demon broken on the black altar (plugin/i.q.dv.dat, quest 25). */
internal suspend fun Game.demonVial(p: Game.Player, now: Long): Boolean {
    if (!world.hasFixture(p.location, "i.s.alt")) { p.log("Чтобы разбить пузырёк, используйте его у чёрного алтаря!"); return true }
    if (world.allNpcs().any { it.key == Society.DEMON }) { p.log("Демон уже разбужен, найдите и убейте его."); return true }
    changeItem(p, "i.q.dv", -1)
    world.spawn(Society.DEMON, Society.DEMON, Society.DEMON_PLACES[dice.roll(0, Society.DEMON_PLACES.size - 1)], now)
    p.log("Вы разбудили Демона!")
    return true
}

/** Flowers into bouquets (plugin/i.bc.dat): a whole stack of flowers joins a bouquet (at most 5 kinds) or starts one. */
internal suspend fun Game.bouquet(p: Game.Player, flower: String, target: String?) {
    if (flower.startsWith("i.bc_")) { p.log("Чтобы добавить цветы в букет, используйте цветок и выберите этот букет"); return }
    val inv = inventoryMap(p)
    val t = target ?: run { p.log("Выберите букет или другой цветок"); return }
    if (!t.startsWith("i.bc")) { p.log("Цветы можно использовать либо на готовые букеты, либо на другие цветы."); return }
    val n = inv[flower] ?: 0
    if (n < 1 || (inv[t] ?: 0) < 1) { p.log("Цветы должны быть в вашем рюкзаке"); return }
    val letter = flower.removePrefix("i.bc.").firstOrNull() ?: return
    if (t.startsWith("i.bc_")) {
        val master = t.substringBeforeLast('_')
        val parts = Regex("([a-z])(\\d+)").findAll(t.substringAfterLast('_')).map { it.groupValues[1][0] to it.groupValues[2].toInt() }.toMutableList()
        val i = parts.indexOfFirst { it.first == letter }
        if (i < 0 && parts.size >= 5) { p.log("В один букет можно добавить не более 5 разных типов цветов"); return }
        if (i >= 0) parts[i] = letter to parts[i].second + n else parts += letter to n
        changeItem(p, flower, -n)
        changeItem(p, t, -1)
        changeItem(p, "${master}_" + parts.joinToString("") { "${it.first}${it.second}" }, 1)
    } else {
        val other = if (t == flower) 0 else inv[t] ?: 0
        val otherLetter = t.removePrefix("i.bc.").firstOrNull()
        changeItem(p, flower, -n)
        if (t != flower) changeItem(p, t, -other)
        changeItem(p, "i.bc_${p.name}_$letter$n" + if (t != flower && otherLetter != null) "$otherLetter$other" else "", 1)
    }
    p.log("Вы собрали букет")
}

// ---- castle alarm ---------------------------------------------------------------------------

/** A castle of [p]'s clan with strangers inside (f_castle.dat:17-30): its id and the room they are in. */
internal suspend fun Game.castleAlarm(p: Game.Player, now: Long): Pair<Int, String>? {
    val clan = p.clanId ?: return null
    val c = castles().values.firstOrNull { it.clanId == clan } ?: return null
    for (room in listOf("gate", "main", "tron", "sklad")) {
        val loc = "c.${c.id}.$room"
        if (players.values.any { q -> q.location == loc && q.id != p.id && !q.ghost && now - q.lastSeen < Game.ACTIVE_SECONDS && q.clanId != clan && q.id !in c.guests })
            return c.id to loc
    }
    return null
}

// ---- world pages and the map ----------------------------------------------------------------

suspend fun Game.worldView(): WorldView = lock.withLock {
    val now = clock()
    ensureFlag(now)
    val online = players.values.filter { now - it.lastSeen < Game.ACTIVE_SECONDS }.sortedBy { it.name }
        .map { OnlineView(it.name, Levels.percent(it.skills().also { s -> s[Skills.POINTS] = it.points }), it.clanName, it.crime.takeIf { _ -> it.criminal(now) }) }
    val clans = db.tx { c ->
        c.prepareStatement("SELECT cl.name, count(m.character_id) FROM clans cl LEFT JOIN clan_members m ON m.clan_id = cl.id GROUP BY cl.name ORDER BY count(m.character_id) DESC, cl.name").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(ClanSummary(rs.getString(1), rs.getInt(2))) } }
        }
    }
    val castles = castles().values.sortedBy { it.id }.map { CastleSummary(it.id, content.locations["c.${it.id}.gate"]?.name ?: "Замок ${it.id}", it.clanName) }
    val holder = flagHolder?.let { players[it] }
    val loc = holder?.location ?: flagLoc
    WorldView(online, clans, castles, holder?.name, loc?.let { content.locations[it]?.name }, loc)
}

/** Every location with coordinates, for the map (m.php draws the same). */
fun Game.mapView(): MapView = MapView(content.locations.values.mapNotNull { l ->
    val m = Regex("^x(\\d+)x(\\d+)$").find(l.id) ?: return@mapNotNull null
    MapPoint(l.id, m.groupValues[1].toInt(), m.groupValues[2].toInt(), l.zone)
})
