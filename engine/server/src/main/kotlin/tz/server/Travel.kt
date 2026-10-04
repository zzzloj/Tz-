package tz.server

import kotlinx.coroutines.sync.withLock
import tz.shared.ChoiceOption
import tz.shared.ChoiceView
import tz.shared.Rules

/*
 * Moving about (g.php:249-270, addnpc g.php:768-796, f_goto.dat,
 * f_uselodka.dat, f_site_connect2.dat:60-107): locked passages, «ушёл /
 * пришёл» in the journals, guarded-zone notices, NPCs chasing their target,
 * the boat, and where a character wakes up after a break.
 */

internal object Travel {
    /** Exits the old engine refused (g.php:249-253); [has] tells whether a backpack item id contains the text. */
    fun locked(from: String, to: String, has: (String) -> Boolean): String? = when {
        from == "x927x253" && to == "x902x254" -> "Стражник: Стой!"
        to == "x1746x545" && !has("i.q.keykrep") -> "Без ключа не пройти"
        from == "x216x1099" && to == "x138x1380" ->
            "Между вами и входом в шахту встаёт грозный гном с гигантским двусторонним топором на плече, явно запрещая вам пройти внутрь"
        from == "x233x2330" && to == "_begi" && !has("i.q.keykrep1") -> "А вы симпотично смотритесь ;)"
        from == "x154x1540" && to == "x155x1540" -> "Ангел сверхъестественной силой не даёт вам двигаться дальше"
        else -> null
    }

    const val FIREBIRD = "n.a.b.jarpt.1"
    const val BOAT = "i.s.lodka"
    const val BROKEN_BOAT = "i.s.lodkas"
    const val PIER = "x1103x618"

    /** Where the boat sails (f_uselodka.dat): value → location, story. */
    val BOAT_TRIPS = mapOf(
        "1" to (PIER to "После нескольких часов плавания вы пристали к пирсу около города и обнаружили, что лодка опять дала течь"),
        "2" to ("x1965x623" to "Добравшись по реке до водопада, вы обнаруживаете справа тропу, ведущую в гору. По ней вы переносите лодку и, проплыв между отвесных скал, пристаёте на берегу крупного озера"),
        "3" to ("x672x336" to "Река принесла вас к морю. Проплыв ещё немного вдоль берега, вы причаливаете к песчаному пляжу"),
    )
    val BOAT_CHOICE = ChoiceView(BOAT, "Куда плыть?", listOf(
        ChoiceOption("К причалу", "1"), ChoiceOption("В Ансалон", "2"), ChoiceOption("К морю", "3"),
    ))

    /** Where a character wakes up after a break (f_site_connect2.dat:60-69): out of the arena, castle rooms, tavern rooms. */
    fun wakeUpAt(loc: String): String = when {
        loc == Rules.ARENA -> "x1229x582"
        loc.startsWith("c.") && CastleRules.castleOf(loc) != null -> loc.substring(0, 4) + "in"
        loc in Spells.TAVERN -> "x1095x532"
        loc.startsWith("qv") -> "_begin"
        else -> loc
    }
}

private fun Game.Player.female() = sex == "f"

/** Journal lines of a character walking [from] → [to] through the exit [label] (addnpc, g.php:768-796). */
internal suspend fun Game.walkLines(p: Game.Player, from: String, to: String, label: String?, hidden: Boolean) {
    if (!hidden) tellOthers(from, p.id, "${p.name} ${if (p.female()) "ушла" else "ушёл"} ${label ?: ""}".trimEnd())
    tellOthers(to, p.id, "${if (p.female()) "Пришла" else "Пришёл"} ${p.name}")
    val a = content.locations[from]?.zone
    val b = content.locations[to]?.zone
    if (a == 1 && b != 1) p.log("Вы покинули охраняемую территорию")
    if (a != 1 && b == 1) p.log("Вы на охраняемой территории")
}

/** NPC moves of the last tick («Крыса ушёл на север», «Пришёл Крыса») for the players who see them. */
internal suspend fun Game.npcMoveLines(now: Long) {
    for (m in world.drainMoves()) {
        val label = content.locations[m.from]?.exits?.firstOrNull { it.target == m.to }?.label
        for (q in players.values) {
            if (now - q.lastSeen >= Game.ACTIVE_SECONDS) continue
            if (q.location == m.from) { q.log(if (label != null) "${m.name} ушёл $label" else "${m.name} исчез"); notify(q.id) }
            else if (q.location == m.to) { q.log("Пришёл ${m.name}"); notify(q.id) }
        }
    }
}

/**
 * f_goto.dat: an NPC whose target walked into a neighbouring location follows
 * it — into the same zone only, except city guards. A character escapes with
 * hiding·4 + dex % (never from the guards). True if the NPC moved.
 */
internal suspend fun Game.chase(npc: World.Npc, targetId: Long, loc: String, now: Long): Boolean {
    if (npc.key.startsWith("n.o.") || npc.key == Travel.FIREBIRD) return false
    val q = players[targetId] ?: return false
    if (q.ghost || now - q.lastSeen >= Game.ACTIVE_SECONDS) return false
    val here = content.locations[loc] ?: return false
    if (here.exits.none { it.target == q.location }) return false
    val there = content.locations[q.location] ?: return false
    val guard = npc.key.startsWith("n.g.")
    if (!guard && there.zone != here.zone) return false
    if (!guard && dice.roll(0, 100) <= q.skill("hiding") * 4 + q.dex) {
        q.log("Вы скрылись от погони")
        notify(q.id)
        return false
    }
    return world.moveNpc(npc, q.location)
}

/** The firebird flies off as soon as a character comes (g.php:719). */
internal suspend fun Game.firebirdFlees(npc: World.Npc, loc: String): Boolean {
    val exits = content.locations[loc]?.exits?.map { it.target }?.filter { it in content.locations } ?: return false
    if (exits.isEmpty()) return false
    return world.moveNpc(npc, exits[dice.roll(0, exits.size - 1)])
}

/** Takes the boat (f_uselodka.dat): [arg] picks where to; without it the choice is asked. */
internal suspend fun Game.sail(p: Game.Player, arg: String?, now: Long): ChoiceView? {
    if (world.itemsAt(p.location, now).none { it.id == Travel.BOAT }) throw ApiException(io.ktor.http.HttpStatusCode.BadRequest, tz.shared.Errors.NO_SUCH_ITEM)
    val (to, story) = Travel.BOAT_TRIPS[arg] ?: return Travel.BOAT_CHOICE
    if (to == p.location) { p.log("Выберите другой пункт назначения"); return Travel.BOAT_CHOICE }
    val from = p.location
    tellOthers(from, p.id, "${p.name} уплыл на лодке")
    world.removeItem(from, Travel.BOAT)
    for (npc in world.npcsIn(from)) npc.enemies.remove(p.id)
    p.location = to
    p.attackTarget = null
    world.removeItem(to, Travel.BOAT)
    world.placePermanent(to, if (to == Travel.PIER) Travel.BROKEN_BOAT else Travel.BOAT, 1)
    tellOthers(to, p.id, "${if (p.sex == "f") "Приплыла" else "Приплыл"} ${p.name}")
    p.log(story)
    save(p)
    return null
}

/**
 * A character back after a break (f_site_connect2.dat:60-107): out of the
 * arena, castle and tavern rooms, and the crime term does not run while away.
 */
/**
 * Leaving by the exit button: the character is out of the world at once, nobody
 * can strike or rob it (owner 04.10.2026; the old game did so only when nobody
 * was near, f_logout.dat). Closing the app without it leaves the character
 * standing for [Game.ACTIVE_SECONDS], as before.
 */
suspend fun Game.leave(account: Account) = lock.withLock {
    val id = byAccount[account.id] ?: return@withLock
    val p = players[id] ?: return@withLock
    val now = clock()
    if (now - p.lastSeen >= Game.ACTIVE_SECONDS) return@withLock
    if (p.hasFlag) dropFlag(p, "${p.name} бросил флаг!")
    // The crime clock stops at the moment of leaving, not ACTIVE_SECONDS before it (see afterBreak).
    if (p.crime != null && p.crimeUntil > now) {
        p.crimeUntil -= Game.ACTIVE_SECONDS
        setState(p.id, "crime", p.crime!!, p.crimeUntil)
    }
    p.lastSeen = now - Game.ACTIVE_SECONDS
    p.fightingPlayer = null
    for (npc in world.npcsIn(p.location)) npc.enemies.remove(p.id)
    save(p)
    notifyLocation(p.location, p.id)
}

internal suspend fun Game.afterBreak(p: Game.Player, away: Long) {
    val loc = Travel.wakeUpAt(p.location)
    if (loc != p.location && loc in content.locations) p.location = loc
    if (away > 0 && p.crime != null && p.crimeUntil > p.lastSeen) {
        p.crimeUntil += away
        setState(p.id, "crime", p.crime!!, p.crimeUntil)
    }
}
