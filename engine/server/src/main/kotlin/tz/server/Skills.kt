package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.PeekItem
import tz.shared.PeekView
import tz.shared.Rules

/*
 * Skills used as commands (f_useskill.dat): meditation, peeking and
 * stealing; the thief's gloves; statistics (`st`), level ranks and titles
 * (f_lookuser.dat). Taming and necromancy come with subordinates.
 */

/** Indexes of the old `st` string (docs/mechanics-progression.md §6). */
internal object Stat {
    const val MONSTERS = 0
    const val PLAYERS = 1
    const val DEATHS = 2
    const val THEFTS = 3
    const val FISH = 4
    const val BRANCHES = 5
    const val ORE = 6
    const val GEMS_SET = 7
    const val TAMED = 8
    const val GEMS_FOUND = 9
    const val SIZE = 12
}

internal fun Game.Player.count(stat: Int, n: Int = 1) {
    if (stat in 0 until Stat.SIZE) stats[stat] += n
}

/** Level in percent of all attributes and skills (g_attr + g_skills = 62), rank and title by the best skill (f_lookuser.dat:71-116). */
internal object Levels {
    fun percent(s: Skills): Int {
        var sum = 0
        for (i in 0 until Skills.SIZE) if (i != Skills.EXP && i != Skills.POINTS) sum += s[i]
        return Math.round((sum - 5) * 100.0 / (Rules.ATTR_SUM + Rules.SKILL_SUM)).toInt()
    }

    fun rank(lev: Int): String = when {
        lev < 5 -> "Новичок"
        lev < 10 -> "Начинающий"
        lev < 20 -> "Умелый"
        lev < 30 -> "Опытный"
        lev < 40 -> "Искусный"
        lev < 50 -> "Профессионал"
        lev < 60 -> "Известный"
        lev < 70 -> "Знаменитый"
        lev < 80 -> "Мастер"
        lev < 90 -> "Грандмастер"
        else -> "Лорд"
    }

    /** Skill index → title, in the old order: on a tie the later one wins. */
    private val TITLES = listOf(
        8 to "боец", 9 to "воин", 10 to "лучник", 13 to "маг", 5 to "монах", 7 to "укротитель", 17 to "маскировщик",
        21 to "спиритуалист", 30 to "повар", 24 to "рудокоп", 25 to "кузнец", 26 to "лесоруб", 27 to "плотник",
        28 to "ювелир", 29 to "рыболов", 22 to "лекарь", 23 to "алхимик", 31 to "некромант", 6 to "вор", 32 to "друид",
    )

    fun title(s: Skills): String {
        var best = "боец"
        var max = 0
        for ((i, t) in TITLES) if (s[i] >= max) { best = t; max = s[i] }
        return if (max == 0) "боец" else best
    }

    /** «сильный и ловкий, но не очень умный» (f_lookuser.dat:121-136). */
    fun build(str: Int, dex: Int, int: Int): String {
        val good = listOfNotNull("сильный".takeIf { str > 3 }, "ловкий".takeIf { dex > 3 }, "умный".takeIf { int > 3 })
        val bad = listOfNotNull("слабый".takeIf { str == 1 }, "медлительный".takeIf { dex == 1 }, "не очень умный".takeIf { int == 1 })
        fun join(l: List<String>) = when (l.size) { 0 -> ""; 1 -> l[0]; 2 -> "${l[0]} и ${l[1]}"; else -> "${l[0]}, ${l[1]} и ${l[2]}" }
        if (good.isEmpty() && bad.isEmpty()) return "Телосложение среднее"
        return join(good) + (if (good.isNotEmpty() && bad.isNotEmpty()) ", но " else "") + join(bad)
    }
}

/** Uses a skill as a command: meditation, or stealing ([item] null — peek first). */
suspend fun Game.skill(account: Account, skill: String, target: String?, item: String?): GameView = lock.withLock {
    val p = alive(player(account))
    val now = clock()
    if (now < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
    when (skill) {
        "meditation" -> meditate(p, now)
        "steal" -> steal(p, target, item, now)
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.UNKNOWN_ABILITY)
    }
    save(p)
    viewLocked(p)
}

/** f_useskill.dat:6-14: S·12 % for +rand(1, S) mana, 5 s. */
private fun Game.meditate(p: Game.Player, now: Long) {
    val s = p.skill("meditation")
    if (p.mana >= p.manaMax) { p.log("Ваш запас маны полон"); return }
    if (s == 0) { p.log("Ваш навык медитации равен нулю, вы не умеете медитировать"); return }
    p.busyUntil = now + 5
    if (dice.roll(0, 100) <= s * 12) {
        val gain = dice.roll(1, s).coerceAtMost(p.manaMax - p.mana)
        p.mana += gain
        p.log("Мана +$gain")
    } else p.log("Медитация прервалась")
}

/** What's in [aim]'s backpack: id → (count, equipped). */
private suspend fun Game.backpackOf(aim: Aim): Map<String, Pair<Int, Boolean>> = when (aim) {
    is Aim.Npc -> aim.npc.items.filterValues { it > 0 }.mapValues { it.value to false }
    is Aim.Pc -> db.tx { c -> inventoryRows(c, aim.p.id) }.associate { (id, n, on) -> id to (n to on) }
    is Aim.Item -> emptyMap()
}

private fun Aim.key(): String = when (this) {
    is Aim.Npc -> npc.key
    is Aim.Pc -> p.name
    is Aim.Item -> id
}

private fun Aim.title(): String = when (this) {
    is Aim.Npc -> npc.name
    is Aim.Pc -> p.name
    is Aim.Item -> id
}

/** Shows [aim]'s backpack; stealing from it is allowed for 60 s (the old «vk» mark). */
internal suspend fun Game.showPeek(p: Game.Player, aim: Aim, now: Long) {
    val items = backpackOf(aim)
    p.peekTarget = aim.key()
    p.peekUntil = now + 60
    if (items.isEmpty()) { p.log("У ${aim.title()} нет ни одного предмета."); return }
    p.peekView = PeekView(aim.key(), aim.title(), items.map { (id, v) -> PeekItem(id, content.itemName(id), v.first, v.second) })
}

/** Caught: «вор» for 20 minutes, the victim and everyone here hear of it, an NPC attacks (f_useskill.dat:65,85-87). */
private suspend fun Game.caught(p: Game.Player, aim: Aim, mine: String, theirs: String, others: String, now: Long) {
    commitCrime(p, "вор", now, Rules.CRIME_SECONDS - 600)
    p.log(mine)
    when (aim) {
        is Aim.Npc -> aim.npc.enemies += p.id
        is Aim.Pc -> { aim.p.log(theirs); notify(aim.p.id) }
        is Aim.Item -> {}
    }
    for (q in players.values) if (q.id != p.id && q.location == p.location && (aim !is Aim.Pc || q.id != aim.p.id) && now - q.lastSeen < Game.ACTIVE_SECONDS) {
        q.log(others); notify(q.id)
    }
}

/** f_useskill.dat:34-91. */
private suspend fun Game.steal(p: Game.Player, target: String?, item: String?, now: Long) {
    if (target == null) { p.log("Не у кого воровать"); return }
    if (target.equals(p.name, ignoreCase = true)) { p.log("Нельзя воровать у самого себя"); return }
    if (p.location == Rules.BANK_LOCATION) { p.log("В банке воровать нельзя"); return }
    if (p.location == Rules.ARENA) { p.log("На арене воровать нельзя"); return }
    val aim = aim(p, target, now)?.takeIf { it !is Aim.Item } ?: run { p.log("Не у кого воровать"); return }
    val fighting = when (aim) {
        is Aim.Npc -> p.id in aim.npc.enemies || p.attackTarget == aim.npc.key
        is Aim.Pc -> aim.p.attackTarget == "u:${p.id}" || p.attackTarget == "u:${aim.p.id}"
        is Aim.Item -> false
    }
    if (fighting) { p.log("Нельзя воровать у того, кто вас атакует или кого атакуете вы"); return }
    // Awareness: a character's own skill, any NPC's is 3.
    val awareness = if (aim is Aim.Pc) aim.p.skill("look") else 3
    val name = aim.title()
    if (item == null) {
        p.busyUntil = now + 5
        if (dice.roll(0, 100) < 6 * (p.dex + p.skill("steallook") - awareness)) showPeek(p, aim, now)
        else caught(p, aim, "Вас заметили!", "${p.name} пытался подглядеть в ваш рюкзак!", "${p.name} пытался подглядеть в рюкзак $name!", now)
        return
    }
    if (p.peekTarget != aim.key() || now > p.peekUntil) {
        caught(p, aim, "$name заметил, что вы хотите его обворовать: в следующий раз будьте побыстрее",
            "${p.name} пытался вас обворовать!", "${p.name} пытался обворовать $name!", now)
        return
    }
    p.busyUntil = now + 10
    val backpack = backpackOf(aim)
    val (count, equipped) = backpack[item] ?: run { p.log("У $name нет этого предмета"); return }
    if (aim is Aim.Pc && !Rules.tradeable(item)) { p.log("Эту вещь украсть нельзя"); return }
    var chance = 4 * (p.dex + p.skill("steal") - awareness)
    if (equipped) chance /= 2
    if (chance <= 0) { p.log("У вас слишком низкие навыки воровства и подглядывания"); return }
    if (dice.roll(0, 100) >= chance) {
        caught(p, aim, "$name застал вас за воровством!", "${p.name} пытался вас обворовать!", "${p.name} пытался обворовать $name!", now)
        return
    }
    // The whole stack changes hands.
    when (aim) {
        is Aim.Npc -> aim.npc.items.remove(item)
        is Aim.Pc -> {
            db.tx { c ->
                c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ?").use { st ->
                    st.setLong(1, aim.p.id); st.setString(2, item); st.executeUpdate()
                }
            }
            if (equipped) refreshStats(aim.p)
            save(aim.p)
        }
        is Aim.Item -> {}
    }
    changeItem(p, item, count)
    p.count(Stat.THEFTS)
    p.log("Вы украли у $name ${if (count > 1) "$count " else ""}${content.itemName(item)}!")
    // Experience only outside ordinary lands and only from the watchful (f_useskill.dat:84).
    if (awareness > 0 && (content.locations[p.location]?.zone ?: 0) != 0) addExp(p, dice.roll(0, awareness))
}
