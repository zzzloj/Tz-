package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import tz.shared.AbilityView
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.Rules

/*
 * Techniques (f_usepriem.dat) and spells (f_usemagic.dat, plugin/m.*.dat),
 * docs/mechanics-combat.md §4–5. Summoning, charming and knocking riders off
 * horses come with pets and horses (stage 10); the leadership flag bonus
 * with the flag (stage 11).
 */

/** A defensive stance p.d.*: works against the first matching attack with [chance] %, or till [until]. */
class Stance(val id: String, val name: String, val until: Long, val chance: Int)

/** A blow other than the plain one: a technique, or a spell's magic (rmagic: no counter-blow, armour does not count). */
class Blow(val stats: Stats, val technique: String? = null, val title: String? = null, val rmagic: Boolean = false)

class StrikeResult(val hit: Formulas.Hit, val note: String)

/** Something a spell or technique is aimed at. */
sealed interface Aim {
    class Npc(val npc: World.Npc) : Aim
    class Pc(val p: Game.Player) : Aim
    class Item(val id: String) : Aim
}

object Spells {
    /** A portal made by m.portal: taking it moves you to the location after the "_". */
    const val PORTAL = "i.s.portal_"
    const val EMPTY_RUNE = "i.rr.empty"
    /** Places runes cannot take you to (m.recall.dat, m.portal.dat): the tavern rooms. */
    val TAVERN = setOf("x1087x543", "x1080x539", "x1087x528", "x1080x532")
    const val FALLBACK = "x2121x212"

    /** Nothing waits any more: summoning, charming and «Сбить с коня» came with subordinates (Pets.kt). */
    fun later(@Suppress("UNUSED_PARAMETER") id: String) = false
    /** The old admins' tools. */
    fun admin(id: String) = id == "m.modes" || id == "m.qv" || id == "m.w.a.qv"

    /**
     * What the spell or technique is aimed at (AbilityView.target): creature —
     * an NPC or another character here; creature_self — also yourself;
     * player / player_self — characters only; ghost — a ghost here; npc; rune
     * — a teleport rune in the backpack; null — nothing.
     */
    fun target(id: String, def: JsonObject?): String? = when {
        id.startsWith("p.d.") -> null
        id.startsWith("p.") -> "creature"
        id.startsWith("m.w.a.") -> null
        id.startsWith("m.w.") -> "creature"
        id == "m.heal" || id == "m.heal.great" -> "creature_self"
        id == "m.n" || id == "m.roj" -> "player"
        id == "m.armor" || id == "m.str" || id == "m.man" -> "player_self"
        id == "m.ressurect" -> "ghost"
        id == "m.peace" || id == "m.charm" || id == "m.charm.enemy" -> "npc"
        id == "m.recall" || id == "m.portal" || id == "m.mark" -> "rune"
        id == "m.kon" -> "player"
        (def?.int("needs_target") ?: 0) != 0 -> "creature"
        else -> null
    }

    /** Scroll i.m.<x> (burns) or rune i.r.<x> (stays) → spell m.<x>. */
    fun spellOfItem(id: String): String? = when {
        id.startsWith("i.m.") -> "m." + id.removePrefix("i.m.")
        id.startsWith("i.r.") -> "m." + id.removePrefix("i.r.")
        else -> null
    }

    fun usable(content: Content, id: String): Boolean =
        spellOfItem(id)?.let { content.items[it] != null } == true || (id.startsWith("i.rr.") && id != EMPTY_RUNE) || id in BOTTLES || id.startsWith("i.ms_")

    fun itemTarget(content: Content, id: String): String? =
        BOTTLES[id]?.let { if (it.single) "creature" else null } ?: spellOfItem(id)?.let { target(it, content.items[it]) }

    /**
     * Bottles and one-shot weapons (plugin/i.b.*.dat, i.q.pdeath.dat,
     * i.q.ssword.dat): damage, verb, rest; [single] — at one target, else at
     * everyone here; [holy] — only criminals and monsters; [magic] — the
     * magic branch (armour does not count, no counter-blow).
     */
    class Bottle(val min: Int, val max: Int, val verb: String, val rest: Int, val single: Boolean,
                 val magic: Boolean = true, val holy: Boolean = false, val poison: Boolean = false, val notInBank: Boolean = false)
    val BOTTLES = mapOf(
        "i.b.fire" to Bottle(1, 24, "огнем", 10, single = false),
        "i.b.holy" to Bottle(6, 24, "святой водой", 5, single = false, holy = true),
        "i.b.jad" to Bottle(1, 28, "ядом", 8, single = true),
        "i.b.jad.c" to Bottle(0, 0, "ядом", 8, single = true, poison = true),
        "i.q.pdeath" to Bottle(0, 90, "порошком смерти", 10, single = false, notInBank = true),
        "i.q.ssword" to Bottle(0, 90, "стекл.мечом", 7, single = true, magic = false, notInBank = true),
    )
    const val POISON_SECONDS = 300L

    /** Where a marked rune leads; null for an empty one. */
    fun runePlace(id: String): String? = id.removePrefix("i.rr.").takeIf { id.startsWith("i.rr.") && id != EMPTY_RUNE && it.isNotEmpty() }

    fun island(loc: String) = Law.wolfIsland(loc)
}

// ---- one blow with stances --------------------------------------------------------------

private fun roundHalf(x: Double) = Math.round(x).toInt()

/**
 * A blow of [a] against [d] with the defender's stance and the attacker's own
 * «реакция» (f_attackf.dat:23-47). Stances spent by this blow are removed.
 */
internal fun Game.strike(a: Stats, attacker: Game.Player?, d: Stats, defender: Game.Player?, blow: Blow?, now: Long): StrikeResult {
    var aw = a
    var dw = d
    var note = ""
    val rmagic = blow?.rmagic == true
    defender?.stance?.let { if (now >= it.until) defender.stance = null }
    // A rider is harder to hit, except by magic (f_attackf.dat:72).
    if (defender?.mount != null && !rmagic && !a.magic) aw = aw.copy(hit = aw.hit - 10)
    attacker?.stance?.let { if (now >= it.until) attacker.stance = null }
    val ds = defender?.stance
    fun spend(s: Stance) { defender?.stance = null; note = " (${s.name})" }
    if (rmagic || a.magic) {
        if (ds?.id == "p.d.z") {
            if (dice.roll(0, 100) <= ds.chance * 0.10) {
                val concentration = attacker?.stance?.takeIf { it.id == "p.d.c" }?.chance ?: 0
                if (dice.roll(0, 100) > concentration) aw = aw.copy(hit = 0)
            }
            spend(ds)
        }
        if (attacker?.stance?.id == "p.d.c") attacker.stance = null
    } else if (ds != null) {
        when (ds.id) {
            "p.d.u" -> if (a.ranged) { if (dice.roll(0, 100) <= ds.chance) dw = dw.copy(dodge = dw.dodge + 35); spend(ds) }
            "p.d.re" -> { if (dice.roll(0, 100) <= ds.chance) dw = dw.copy(dodge = dw.dodge + 20); spend(ds) }
            "p.d.p" -> { if (dice.roll(0, 100) <= ds.chance) dw = dw.copy(parry = dw.parry * 2); spend(ds) }
        }
    }
    val ds2 = defender?.stance
    if (blow?.technique == "p.g" && ds2?.id == "p.d.g") {
        if (dice.roll(0, 100) <= ds2.chance) aw = aw.copy(dmgMin = 0, dmgMax = 0)
        spend(ds2)
    }
    if (attacker?.stance?.id == "p.d.re") aw = aw.copy(dmgMin = roundHalf(aw.dmgMin * 0.6), dmgMax = roundHalf(aw.dmgMax * 0.6))
    defender?.stance?.takeIf { it.id == "p.d.o" && !rmagic }?.let { s ->
        if (dice.roll(0, 100) <= s.chance) aw = aw.copy(dmgMin = roundHalf(aw.dmgMin * 0.4), dmgMax = roundHalf(aw.dmgMax * 0.4))
        note = " (${s.name})"
    }
    // A spell stopped by «защита от магии» is a miss, not silence.
    val magicStats = if (rmagic && !aw.magic) aw.copy(verb = "магией") else aw
    val h = Formulas.attack(magicStats, dw, dice)
    return StrikeResult(if (h.outcome == Formulas.Outcome.FIZZLED && rmagic && note.isNotEmpty()) Formulas.Hit(Formulas.Outcome.MISS) else h, note)
}

// ---- what the character can use --------------------------------------------------------

internal fun Game.abilities(p: Game.Player, now: Long): List<AbilityView> = p.known.sorted().mapNotNull { id ->
    val def = content.items[id] ?: return@mapNotNull null
    val kind = when {
        id.startsWith("p.d.") -> "stance"
        id.startsWith("p.") -> "technique"
        id.startsWith("m.") -> "spell"
        else -> return@mapNotNull null
    }
    if (Spells.admin(id)) return@mapNotNull null
    AbilityView(
        id = id, name = def.str("name") ?: id, kind = kind,
        manaCost = def.int("mana_cost") ?: 0,
        target = Spells.target(id, def),
        readyIn = ((p.cooldowns[id] ?: 0) - now).coerceAtLeast(0),
        description = (def.str("description") ?: "").replace("<br/>", "\n"),
        later = Spells.later(id),
    )
}

/** Finds what [target] names here: an NPC key, a character's name (yourself if [self]), or an item in the backpack. */
internal suspend fun Game.aim(p: Game.Player, target: String?, now: Long, self: Boolean = false, ghosts: Boolean = false): Aim? {
    val t = target?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (t.startsWith("n.")) return world.npc(p.location, t)?.let { Aim.Npc(it) }
    if (t.startsWith("i.")) return if ((inventoryMap(p)[t] ?: 0) > 0) Aim.Item(t) else null
    if (self && t.equals(p.name, ignoreCase = true)) return Aim.Pc(p)
    return players.values.firstOrNull {
        it.id != p.id && it.location == p.location && now - it.lastSeen < Game.ACTIVE_SECONDS &&
            it.name.equals(t, ignoreCase = true) && (ghosts || !it.ghost)
    }?.let { Aim.Pc(it) }
}

/** Crime for striking [aim] (f_attackf.dat:50-63) — the same as for a plain blow. */
private suspend fun Game.crimeFor(p: Game.Player, aim: Aim, now: Long) {
    when (aim) {
        is Aim.Npc -> npcAttackCrime(p, aim.npc, now)
        is Aim.Pc -> if (aim.p.id != p.id && !p.criminal(now) && !guiltyPlayer(aim.p, p, now)) commitCrime(p, "бандит", now)
        is Aim.Item -> {}
    }
}

/** One blow (a technique's or a spell's) at [aim]; the plain counter-blow follows unless it is a spell. */
private suspend fun Game.blowAt(p: Game.Player, aim: Aim, blow: Blow, now: Long) {
    crimeFor(p, aim, now)
    when (aim) {
        is Aim.Npc -> if (aim.npc.hp > 0) playerHits(p, aim.npc, now, answer = true, blow = blow)
        is Aim.Pc -> if (!aim.p.ghost) { playerHitsPlayer(p, aim.p, now, answer = true, attackerWasCriminal = p.criminal(now), blow = blow); save(aim.p) }
        is Aim.Item -> {}
    }
}

private fun Aim.alive(): Boolean = when (this) {
    is Aim.Npc -> npc.hp > 0
    is Aim.Pc -> !p.ghost
    is Aim.Item -> false
}

private fun Aim.name(): String = when (this) {
    is Aim.Npc -> npc.name
    is Aim.Pc -> p.name
    is Aim.Item -> id
}

/** Everyone else standing here, alive: characters and NPCs. */
private suspend fun Game.othersHere(p: Game.Player, now: Long): List<Aim> =
    players.values.filter { it.id != p.id && it.location == p.location && !it.ghost && now - it.lastSeen < Game.ACTIVE_SECONDS }.map { Aim.Pc(it) } +
        world.npcsIn(p.location).filter { it.hp > 0 }.map { Aim.Npc(it) }

private fun Game.tellHere(p: Game.Player, line: String) = tellOthers(p.location, p.id, line)

// ---- techniques ----------------------------------------------------------------------------

/** Uses a technique (f_usepriem.dat): a special blow at [target], or a defensive stance. */
suspend fun Game.technique(account: Account, id: String, target: String?): GameView = lock.withLock {
    val p = alive(player(account))
    val now = clock()
    val def = content.items[id]?.takeIf { it.str("kind") == "technique" && id in p.known }
        ?: throw ApiException(HttpStatusCode.BadRequest, Errors.UNKNOWN_ABILITY)
    val name = def.str("name") ?: id
    val cooldown = (def.int("cooldown") ?: 0).toLong()
    // Intelligence spoils the aim of techniques, and so does the saddle (f_usepriem.dat:24-26).
    val intPenalty = (p.int - 1) * 10 + (if (p.mount != null) 10 else 0)
    if (p.stats.ranged) {
        p.log("Приемы можно использовать только в рукопашном бою или с холодным оружием ближнего боя")
        return@withLock viewLocked(p)
    }
    if (id.startsWith("p.d.")) {
        if (id == "p.d.p" && p.equipped.none { it.startsWith("i.a.s.") }) {
            p.log("Щит должен находиться у вас в руках")
            return@withLock viewLocked(p)
        }
        p.stance = Stance(id, name, now + cooldown, if (id == "p.d.c") p.int * 16 else p.stats.hit - intPenalty)
        if (id == "p.d.o") p.busyUntil = now + cooldown
        p.log("Вы встали в стойку «$name»")
        return@withLock viewLocked(p)
    }
    val aim = aim(p, target, now)?.takeIf { it !is Aim.Item }
    if (aim == null) { p.log("Нет цели"); return@withLock viewLocked(p) }
    if (now < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
    if (!Law.mayFight(p, (aim as? Aim.Pc)?.p, now)) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_FIGHT_HERE)
    val ready = p.cooldowns[id] ?: 0
    if (now < ready) {
        p.log("Период «$name» не истек (еще ${(ready - now) / 60 + 1} минут)")
        return@withLock viewLocked(p)
    }
    p.cooldowns[id] = now + cooldown

    val w = p.stats
    val weapon = p.equipped.any { it.startsWith("i.w.") }
    val str = p.str
    val cold = p.skill("coldweapon")
    val hand = p.skill("hand")
    var hit = w.hit - intPenalty
    var min = w.dmgMin
    var max = w.dmgMax
    var delay = w.delay
    fun scale(k: Double) { min = roundHalf(min * k); max = roundHalf(max * k) }
    when (id) {
        "p.b" -> { scale(0.7); delay = roundHalf(delay * 0.5) }
        "p.d" -> hit -= 15
        "p.me" -> hit -= 10
        "p.m" -> { scale(0.7); hit += 40 }
        "p.n" -> scale(0.6)
        "p.r" -> scale(0.4)
        "p.vw", "p.vs" -> scale(0.2)
        "p.g" -> { hit -= 30; val add = if (weapon) str * 2 + cold else str + hand; min += add; max += add }
        "p.p" -> { hit -= 20; max += if (weapon) str * 3 + cold * 2 else str * 2 + hand }
        "p.s" -> { hit -= 10; max += if (weapon) str * 2 + cold else str + hand }
    }
    if (hit < 1) hit = 5
    if (hit > 95) hit = 95
    val stats = w.copy(hit = hit, dmgMin = min.coerceAtLeast(0), dmgMax = max.coerceAtLeast(0), delay = delay)
    val blow = Blow(stats, id, name)
    p.busyUntil = now + stats.delay
    blowAt(p, aim, blow, now)
    when (id) {
        "p.d" -> {
            if (aim.alive() && !p.ghost) blowAt(p, aim, blow, now)
            p.busyUntil = now + w.delay
        }
        "p.me" -> {
            // The mill strikes everyone else here too — the innocent and your own (f_usepriem.dat:54).
            for (other in othersHere(p, now)) if (other.name() != aim.name() && !p.ghost) blowAt(p, other, blow, now)
            p.busyUntil = now + 2L * w.delay
        }
    }
    if (!p.ghost && aim.alive()) afterEffects(p, aim, id, hit, now)
    save(p)
    viewLocked(p)
}

/** Stunning, knocking a weapon or a shield out (f_usepriem.dat:57-63), checked whether the blow landed or not. */
private suspend fun Game.afterEffects(p: Game.Player, aim: Aim, id: String, hit: Int, now: Long) {
    val tp = (aim as? Aim.Pc)?.p
    val guard = tp?.stance
    fun guarded(stance: String): Boolean {
        if (guard?.id != stance) return false
        tp.stance = null
        return dice.roll(0, 100) <= guard.chance
    }
    when (id) {
        "p.n" -> if (dice.roll(0, 100) <= hit - 40 && !guarded("p.d.n")) {
            when (aim) {
                is Aim.Npc -> aim.npc.busyUntil = now + 15
                is Aim.Pc -> { aim.p.busyUntil = now + 15; aim.p.log("Вас оглушили!"); notify(aim.p.id) }
                is Aim.Item -> {}
            }
            p.log("${aim.name()} оглушен!")
            tellHere(p, "${aim.name()} оглушен!")
        } else guarded("p.d.n")
        "p.r" -> if (tp != null) {
            val weapon = tp.equipped.firstOrNull { it.startsWith("i.w.") }
            if (dice.roll(0, 100) <= hit - 70 && weapon != null && !guarded("p.d.r")) knockOut(p, tp, weapon, "оружие", now)
            else guarded("p.d.r")
        }
        "p.vw" -> if (tp != null) {
            val shield = tp.equipped.firstOrNull { it.startsWith("i.a.s.") }
            if (dice.roll(0, 100) <= hit - 40 && shield != null) knockOut(p, tp, shield, "щит", now)
        }
        "p.vs" -> if (tp?.mount != null && dice.roll(0, 100) <= hit - 50 && !guarded("p.d.s")) unhorse(tp, now) else guarded("p.d.s")
    }
}

private suspend fun Game.knockOut(p: Game.Player, t: Game.Player, itemId: String, what: String, now: Long) {
    db.tx { c ->
        c.prepareStatement("UPDATE character_items SET equipped = FALSE WHERE character_id = ? AND item_id = ?").use { st ->
            st.setLong(1, t.id); st.setString(2, itemId); st.executeUpdate()
        }
    }
    changeItem(t, itemId, -1)
    world.drop(t.location, itemId, 1, now)
    refreshStats(t)
    t.busyUntil = now + 5
    t.log("У вас выбит $what!")
    p.log("У ${t.name} выбит $what!")
    tellHere(p, "У ${t.name} выбит $what!")
    notify(t.id)
}

// ---- spells --------------------------------------------------------------------------------

/** Casts a spell from the book (f_usemagic.dat) at [target]. */
suspend fun Game.cast(account: Account, spell: String, target: String?): GameView = lock.withLock {
    val p = alive(player(account))
    if (spell !in p.known || content.items[spell]?.str("kind") != "spell" || Spells.admin(spell))
        throw ApiException(HttpStatusCode.BadRequest, Errors.UNKNOWN_ABILITY)
    castSpell(p, spell, target, scroll = null)
    save(p)
    viewLocked(p)
}

/**
 * The cast itself. [scroll] is the scroll (i.m.*, burns) or rune (i.r.*,
 * stays) it is read from: then the spell need not be known and has no period.
 */
internal suspend fun Game.castSpell(p: Game.Player, spell: String, target: String?, scroll: String?) {
    val now = clock()
    val def = content.items[spell] ?: throw ApiException(HttpStatusCode.BadRequest, Errors.UNKNOWN_ABILITY)
    val name = def.str("name") ?: spell
    if (now < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
    val kind = Spells.target(spell, def)
    val aim: Aim? = when (kind) {
        null -> null
        "creature_self", "player_self" -> aim(p, target, now, self = true)
        "ghost" -> aim(p, target, now, ghosts = true)
        else -> aim(p, target, now)
    }
    val fits = when (kind) {
        null -> true
        "creature" -> aim is Aim.Npc || (aim is Aim.Pc && aim.p.id != p.id)
        "creature_self" -> aim is Aim.Npc || aim is Aim.Pc
        "player" -> aim is Aim.Pc && aim.p.id != p.id
        "player_self" -> aim is Aim.Pc
        "ghost" -> aim is Aim.Pc
        "npc" -> aim is Aim.Npc
        "rune" -> aim is Aim.Item
        else -> false
    }
    if (!fits) { p.log(if (kind == "rune") "Заклинание можно использовать только на руну телепортации" else "Нет цели"); return }
    if (spell.startsWith("m.w.a.") && othersHere(p, now).isEmpty()) { p.log("Нет цели"); return }
    val cost = def.int("mana_cost") ?: 0
    if (p.mana < cost) { p.log("Недостаточно маны"); return }
    val ready = p.cooldowns[spell] ?: 0
    if (scroll == null && now < ready) { p.log("Период «$name» не истек (еще ${(ready - now) / 60 + 1} минут)"); return }
    val magicSkill = p.skill("magic")
    val level = def.int("level") ?: 1
    val strPenalty = (maxOf(p.str, 2) - 2) * 4
    // On horseback −10 (f_usemagic.dat:25).
    val chance = ((magicSkill * 0.5 + p.int * 1.5) * 10 - level * 10 + 10 - strPenalty - (if (p.mount != null) 10 else 0)).coerceAtMost(95.0)
    if (chance <= 0 || magicSkill == 0) { p.log("Слишком слабый навык магии"); return }
    p.mana -= cost
    p.busyUntil = now + (def.int("cast_time") ?: 0) + 3 - p.dex + strPenalty
    if (scroll == null) {
        val period = (def.int("cooldown") ?: 0).toLong() + if (spell.startsWith("m.w.")) (maxOf(p.str, 2) - 2) * 1200L else 0L
        p.cooldowns[spell] = now + period
    }
    if (scroll != null && scroll.startsWith("i.m.")) changeItem(p, scroll, -1)
    def.str("words")?.takeIf { it.isNotBlank() }?.let { words ->
        val line = "${p.name}: $words" + if (spell.startsWith("m.w.") && p.stance?.id == "p.d.c") " (концентрация)" else ""
        p.log(line); tellHere(p, line)
    }
    val success = dice.roll(0, 100) <= chance
    if (!success) {
        p.log("Заклинание сорвалось")
        tellHere(p, "${p.name} (заклинание сорвалось)")
        // A failed battle spell still marks the attacker (f_usemagic.dat:56).
        if (spell.startsWith("m.w.")) battleSpell(p, spell, def, aim, now, loss = true)
        return
    }
    spellEffect(p, spell, def, aim, now)
}

private suspend fun Game.spellEffect(p: Game.Player, spell: String, def: JsonObject, aim: Aim?, now: Long) {
    val int = p.int
    val pmin = def.int("power_min") ?: 0
    val pmax = def.int("power_max") ?: 0
    when {
        spell.startsWith("m.w.") -> battleSpell(p, spell, def, aim, now, loss = false)
        spell.startsWith("m.heal") -> heal(p, spell, pmin, pmax, aim, now)
        spell == "m.n" || spell == "m.roj" -> {
            val t = (aim as Aim.Pc).p
            t.busyUntil = now + 20
            t.log("Рой мошек мешает вам что-либо сделать!")
            tellHere(p, "${t.name} облеплен мошкой!")
            p.log("${t.name} облеплен мошкой!")
            notify(t.id)
        }
        spell == "m.maddnes" -> madness(p, now)
        spell == "m.peace" -> { val n = (aim as Aim.Npc).npc; n.enemies.clear(); n.npcTarget = null; p.log("${n.name} успокаивается") }
        spell == "m.silence" -> { for (n in world.npcsIn(p.location)) { n.enemies.clear(); n.npcTarget = null }; p.log("Все вокруг успокаиваются") }
        spell == "m.charm.enemy" -> p.log("Заклинание на него не подействует")   // never worked in the old game either
        spell.startsWith("m.s.") -> summon(p, spell, now)
        spell == "m.charm" -> charm(p, (aim as Aim.Npc).npc, now)
        spell == "m.kon" -> {
            val t = (aim as Aim.Pc).p
            if (t.mount == null) { p.log("Выбить из седла можно только всадника"); return }
            // A blow of 0 only for the crime (plugin/m.kon.dat:6-17).
            crimeFor(p, aim, now)
            unhorse(t, now)
        }
        spell == "m.armor" -> buff(p, (aim as Aim.Pc).p, "Броня", int) { it.armorBuff = int; refreshStats(it) }
        spell == "m.str" -> buff(p, (aim as Aim.Pc).p, "Макс.жизнь", int * 2) { it.hpBuff = int * 2 }
        spell == "m.man" -> buff(p, (aim as Aim.Pc).p, "Макс.мана", int * 2) { it.manaBuff = int * 2 }
        spell == "m.meditation" -> {
            var gain = roundHalf((p.hp - 1) * 0.7).coerceAtLeast(0)
            gain = gain.coerceAtMost(p.manaMax - p.mana)
            p.hp = 1
            p.mana += gain
            p.regenFrom = now
            p.log("Мана +$gain")
        }
        spell == "m.ressurect" -> {
            val t = (aim as Aim.Pc).p
            if (!t.ghost) { p.log("${t.name} не призрак"); return }
            t.ghost = false
            t.hp = t.hpMax * Rules.RESURRECT_HP_PERCENT / 100
            t.regenFrom = now
            t.log("${p.name} воскресил вас")
            p.log("Вы воскресили ${t.name}")
            save(t); notify(t.id)
        }
        spell == "m.recall" -> recall(p, (aim as Aim.Item).id, now)
        spell == "m.portal" -> portal(p, (aim as Aim.Item).id, now)
        spell == "m.mark" -> mark(p, (aim as Aim.Item).id)
        spell == "m.search" -> search(p, now)
        spell == "m.createfood" -> {
            val r = dice.roll(0, 100)
            val food = when {
                r <= 20 -> "i.f.apple"; r <= 35 -> "i.f.cabbage"; r <= 55 -> "i.f.bread"
                r <= 75 -> "i.f.sandwich"; r <= 85 -> "i.f.mushroom"; else -> "i.f.sausage"
            }
            world.drop(p.location, food, 1, now)
            p.log("Появляется ${content.itemName(food)}")
        }
        spell == "m.buket" -> {
            val letters = "roftphlgvadibjeknmxs"
            val id = "i.bc." + letters[dice.roll(0, letters.length - 1)]
            changeItem(p, id, 1)
            p.log("У вас в руках ${content.itemName(id)}")
        }
        else -> p.log("Ничего не произошло")
    }
}

/** Battle spells m.w.* (plugin/m.w.dat): a magic blow at the target or, for m.w.a.*, at everyone here. */
private suspend fun Game.battleSpell(p: Game.Player, spell: String, def: JsonObject, aim: Aim?, now: Long, loss: Boolean) {
    val int = p.int
    val pmin = def.int("power_min") ?: 0
    val pmax = def.int("power_max") ?: 0
    val stats = p.stats.copy(
        hit = if (loss) 0 else 100,
        dmgMin = (pmin - 10 + int * 2).coerceAtLeast(0), dmgMax = pmax + int * 2,
        delay = def.int("cast_time") ?: 0, ranged = false, verb = "магией", ammo = "",
    )
    val blow = Blow(stats, title = null, rmagic = true)
    val criminalsOnly = (def.int("criminals_only") ?: 0) != 0
    val targets = if (spell.startsWith("m.w.a.")) othersHere(p, now) else listOfNotNull(aim)
    for (t in targets) {
        if (p.ghost) break
        val guilty = when (t) {
            is Aim.Npc -> t.npc.key.startsWith("n.c.")
            is Aim.Pc -> t.p.criminal(now)
            is Aim.Item -> false
        }
        if (criminalsOnly && !guilty) continue
        if (spell == "m.w.hit" && !loss && t is Aim.Pc) t.p.mana = (t.p.mana - dice.roll(pmin, pmax)).coerceAtLeast(0)
        if (loss) crimeFor(p, t, now) else blowAt(p, t, blow, now)
    }
    if (spell == "m.w.vamp" && !loss && !p.ghost) {
        val gain = dice.roll(2, maxOf(2, pmin)).coerceAtMost(p.hpMax - p.hp)
        if (gain > 0) { p.hp += gain; p.log("Жизнь +$gain") }
    }
}

/** Healing (plugin/m.heal.dat); m.heal.all heals you, your clan, your followers and your castle's guards. */
private suspend fun Game.heal(p: Game.Player, spell: String, pmin: Int, pmax: Int, aim: Aim?, now: Long) {
    val targets = if (spell == "m.heal.all") {
        val castle = CastleRules.castleOf(p.location)?.let { castles()[it] }
        listOf<Aim>(Aim.Pc(p)) +
            players.values.filter { it.id != p.id && it.location == p.location && now - it.lastSeen < Game.ACTIVE_SECONDS && p.clanId != null && it.clanId == p.clanId }.map { Aim.Pc(it) } +
            world.npcsIn(p.location).filter { it.key.startsWith("n.o.") && castle?.clanId != null && castle.clanId == p.clanId }.map { Aim.Npc(it) }
    } else listOfNotNull(aim)
    for (t in targets) {
        val amount = dice.roll((pmin - 10 + p.int * 2).coerceAtLeast(0), pmax + p.int * 2)
        when (t) {
            is Aim.Pc -> {
                val gain = amount.coerceAtMost(t.p.hpMax - t.p.hp).coerceAtLeast(0)
                t.p.hp += gain
                t.p.log("Жизнь +$gain")
                if (t.p.id != p.id) { p.log("${t.p.name}: жизнь +$gain"); save(t.p); notify(t.p.id) }
                if (t.p.id != p.id && !p.criminal(now) && t.p.criminal(now)) commitCrime(p, "лечил преступника", now)
            }
            is Aim.Npc -> {
                val gain = amount.coerceAtMost(t.npc.proto.hpMax - t.npc.hp).coerceAtLeast(0)
                t.npc.hp += gain
                p.log("${t.npc.name}: жизнь +$gain")
                if (!p.criminal(now) && t.npc.key.startsWith("n.c.")) commitCrime(p, "лечил преступника", now)
            }
            is Aim.Item -> {}
        }
    }
}

private suspend fun Game.buff(p: Game.Player, t: Game.Player, what: String, amount: Int, apply: suspend (Game.Player) -> Unit) {
    apply(t)
    t.log("$what +$amount")
    if (t.id != p.id) { p.log("${t.name}: ${what.lowercase()} +$amount"); save(t); notify(t.id) }
}

/**
 * Madness (plugin/m.maddnes.dat): in a guarded street the caster is caught
 * (80 %) — becomes the madman and a «провокатор»; otherwise a random
 * creature here (not a guard) strikes another.
 */
private suspend fun Game.madness(p: Game.Player, now: Long) {
    val zone = content.locations[p.location]?.zone ?: 0
    val here = (othersHere(p, now) + Aim.Pc(p)).filter { !(it is Aim.Npc && it.npc.key.startsWith("n.g.")) }
    val mad: Aim = if (zone == 1 && dice.roll(0, 100) < 80) {
        commitCrime(p, "провокатор", now)
        Aim.Pc(p)
    } else here[dice.roll(0, here.size - 1)]
    val victim = here[dice.roll(0, here.size - 1)]
    if (victim.name() == mad.name()) {
        p.log("Безумие прошло, никого не тронув"); tellHere(p, "Безумие прошло, никого не тронув")
        return
    }
    val line = "Безумие завладело ${mad.name()}!"
    p.log(line); tellHere(p, line)
    when (mad) {
        is Aim.Pc -> {
            val a = mad.p
            when (victim) {
                is Aim.Npc -> { npcAttackCrime(a, victim.npc, now); playerHits(a, victim.npc, now, answer = true) }
                is Aim.Pc -> {
                    if (!a.criminal(now) && !guiltyPlayer(victim.p, a, now)) commitCrime(a, "бандит", now)
                    playerHitsPlayer(a, victim.p, now, answer = true, attackerWasCriminal = a.criminal(now)); save(victim.p)
                }
                is Aim.Item -> {}
            }
            save(a)
        }
        is Aim.Npc -> {
            val n = mad.npc
            n.busyUntil = now + n.stats.delay
            when (victim) {
                is Aim.Npc -> { n.npcTarget = victim.npc.key; npcHitsNpc(n, victim.npc, now, players.values.filter { it.location == p.location }) }
                is Aim.Pc -> { n.enemies += victim.p.id; npcHits(n, victim.p, now, answer = true); save(victim.p) }
                is Aim.Item -> {}
            }
        }
        is Aim.Item -> {}
    }
}

private fun Game.runeRefusal(place: String, from: String?): String? = when {
    place == Rules.ARENA -> "В арену нельзя телепортироваться, переназначьте руну в другое место"
    Spells.island(place) || (from != null && Spells.island(from)) ->
        "На Волчьем острове магия рун перемещения не работает из-за влияния магических самоцветов в горной породе"
    place in Spells.TAVERN -> "В таверну нельзя телепортироваться, переназначьте руну в другое место"
    else -> null
}

private suspend fun Game.teleport(p: Game.Player, place: String) {
    val to = if (place in content.locations) place else Spells.FALLBACK
    for (npc in world.npcsIn(p.location)) npc.enemies.remove(p.id)
    tellHere(p, "${p.name} исчезает в клубах серого дыма")
    p.location = to
    save(p)
    notifyLocation(to, except = p.id)
}

private suspend fun Game.recall(p: Game.Player, rune: String, now: Long) {
    val place = Spells.runePlace(rune) ?: run { p.log("Руна не помечена ни в одно место"); return }
    runeRefusal(place, p.location)?.let { p.log(it); return }
    teleport(p, place)
    p.log("Вы исчезаете в клубах серого дыма и оказываетесь в совершенно другом месте.")
}

private suspend fun Game.portal(p: Game.Player, rune: String, now: Long) {
    val place = Spells.runePlace(rune) ?: run { p.log("Руна не помечена ни в одно место"); return }
    runeRefusal(place, null)?.let { p.log(it); return }
    world.placeFor(p.location, Spells.PORTAL + place, 180, now)
    val line = "${p.name} создал портал телепортации"
    p.log(line); tellHere(p, line)
}

/** Stepping into a portal (f_take.dat): it stays for others until it fades. */
internal suspend fun Game.usePortal(p: Game.Player, itemId: String, now: Long) {
    if (world.itemsAt(p.location, now).none { it.id == itemId }) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
    val place = itemId.removePrefix(Spells.PORTAL)
    teleport(p, place)
    p.log("Вы шагнули в портал")
}

private suspend fun Game.mark(p: Game.Player, rune: String) {
    val loc = p.location
    val refusal = when {
        loc.startsWith("c.") -> "В замках нельзя помечать руны"
        loc == Rules.ARENA -> "На арене нельзя помечать руны"
        loc.startsWith("x3") -> "В этих землях нельзя помечать руны"
        Spells.island(loc) -> "На Волчьем острове магия рун перемещения не работает из-за влияния магических самоцветов в горной породе"
        else -> null
    }
    if (refusal != null) { p.log(refusal); return }
    changeItem(p, rune, -1)
    changeItem(p, "i.rr.$loc", 1)
    p.log("Руна помечена в это место")
}

/** m.search: who is in the neighbouring locations. */
private suspend fun Game.search(p: Game.Player, now: Long) {
    val here = content.locations[p.location] ?: return
    p.log("Вы чувствуете, что:")
    for (exit in here.exits) {
        if (exit.target == p.location) continue
        val npcs = world.npcsIn(exit.target)
        val people = players.values.filter { it.location == exit.target && now - it.lastSeen < Game.ACTIVE_SECONDS }
        val parts = listOfNotNull(
            npcs.count { it.key.startsWith("n.c.") }.takeIf { it > 0 }?.let { "$it монстров" },
            people.count { !it.ghost && it.criminal(now) }.takeIf { it > 0 }?.let { "$it преступников" },
            people.count { it.ghost }.takeIf { it > 0 }?.let { "$it призраков" },
            people.count { !it.ghost && !it.criminal(now) }.takeIf { it > 0 }?.let { "$it игроков" },
            npcs.count { it.key.startsWith("n.a.") }.takeIf { it > 0 }?.let { "$it животных" },
            npcs.count { !it.key.startsWith("n.c.") && !it.key.startsWith("n.a.") }.takeIf { it > 0 }?.let { "$it человек" },
            world.itemsAt(exit.target, now).size.takeIf { it > 0 }?.let { "$it предметов" },
        )
        p.log("  ${exit.label}: " + (parts.joinToString(", ").ifEmpty { "никого" }))
    }
}

/**
 * Reading a scroll or a rune of a spell, or using a marked teleport rune
 * (plugin/i.rr.dat: 7 mana, the chance of a level-2 spell, 10 s rest).
 * Returns false if the item is not magic.
 */
internal suspend fun Game.useMagicItem(p: Game.Player, itemId: String, target: String?): Boolean {
    Spells.BOTTLES[itemId]?.let { useBottle(p, itemId, it, target); return true }
    if (summonScroll(p, itemId, clock())) return true
    Spells.spellOfItem(itemId)?.takeIf { content.items[it] != null }?.let { spell ->
        castSpell(p, spell, target, scroll = itemId)
        return true
    }
    val place = Spells.runePlace(itemId) ?: return false
    val now = clock()
    if (now < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
    if (p.mana < 7) { p.log("Недостаточно маны"); return true }
    val magicSkill = p.skill("magic")
    var chance = (magicSkill * 0.5 + p.int * 1.5) * 10 - 20
    if (chance < 25) chance = 10.0
    if (magicSkill == 0) { p.log("Слишком слабый навык магии"); return true }
    p.mana -= 7
    p.busyUntil = now + 10
    val line = "${p.name}: Tira Ruen"
    p.log(line); tellHere(p, line)
    if (dice.roll(0, 100) > chance) { p.log("Заклинание сорвалось"); return true }
    runeRefusal(place, p.location)?.let { p.log(it); return true }
    teleport(p, place)
    p.log("Вы исчезаете в клубах серого дыма и оказываетесь в совершенно другом месте.")
    return true
}

/** Throws a bottle or strikes with a one-shot weapon (Spells.BOTTLES). */
private suspend fun Game.useBottle(p: Game.Player, itemId: String, b: Spells.Bottle, target: String?) {
    val now = clock()
    if (now < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
    val name = content.itemName(itemId)
    if (b.notInBank && p.location == Rules.BANK_LOCATION) { p.log("В банке нельзя использовать: $name"); return }
    if (itemId == "i.q.ssword" && (p.str < 5 || p.dex < 4)) { p.log("Необходимо минимум сила 5 и ловкость 4"); return }
    val targets: List<Aim> = if (b.single) {
        listOfNotNull(aim(p, target, now)?.takeIf { it !is Aim.Item }).ifEmpty { p.log("Нет цели"); return }
    } else othersHere(p, now).filter { t ->
        !b.holy || when (t) { is Aim.Npc -> t.npc.key.startsWith("n.c."); is Aim.Pc -> t.p.criminal(now); is Aim.Item -> false }
    }
    changeItem(p, itemId, -1)
    p.busyUntil = now + b.rest
    if (itemId == "i.q.pdeath" && p.location == "x2375x934") {
        val line = "[громовой голос] КАК ТЫ СМЕЕШЬ МЕНЯ ТРЕВОЖИТЬ, ${p.name.uppercase()}?"
        p.log(line); tellHere(p, line)
    }
    val stats = p.stats.copy(hit = 100, dmgMin = b.min, dmgMax = b.max, delay = b.rest, ranged = false, verb = b.verb, ammo = "")
    val blow = Blow(stats, rmagic = b.magic)
    for (t in targets) {
        if (p.ghost) break
        if (!Law.mayFight(p, (t as? Aim.Pc)?.p, now)) continue
        if (b.poison) when (t) {
            is Aim.Npc -> t.npc.poisonUntil = now + Spells.POISON_SECONDS
            is Aim.Pc -> { t.p.poisonUntil = now + Spells.POISON_SECONDS; t.p.log("Вас отравили!"); notify(t.p.id) }
            is Aim.Item -> {}
        }
        blowAt(p, t, blow, now)
    }
}
