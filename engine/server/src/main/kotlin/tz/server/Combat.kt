package tz.server

import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/**
 * Combat parameters of a character or NPC — the old engine's `war` string
 * with names (docs/data-fields.md §3.2, docs/mechanics-combat.md §1).
 */
@kotlinx.serialization.Serializable
data class Stats(
    val hit: Int,
    val dmgMin: Int,
    val dmgMax: Int,
    val delay: Int,
    val ranged: Boolean,
    val armor: Int,
    val dodge: Int,
    val parry: Int,
    val shieldArmor: Int,
    val magicDodge: Int,
    val magicParry: Int,
    val magicResist: Int,
    val verb: String,
    val expValue: Int,
    val ammo: String,
    /** Gems (i.i.am, i.i.ne…) change maximum HP and mana (char[2], char[4]). */
    val hpBonus: Int = 0,
    val manaBonus: Int = 0,
    /** Extra crit chance in percent (the ..kp gem, f_attackf.dat:99). */
    val critBonus: Int = 0,
) {
    val magic: Boolean get() = verb == "магией" || verb == "молнией"
}

/** A character's attributes and skills, indexed like the old `skills` string (§3.3). */
class Skills(private val values: IntArray = IntArray(SIZE)) {
    operator fun get(i: Int) = values.getOrElse(i) { 0 }
    operator fun set(i: Int, v: Int) { values[i] = v }
    fun sumExceptExp(): Int = values.withIndex().filter { it.index != EXP }.sumOf { it.value }

    companion object {
        const val SIZE = 34
        const val STR = 0; const val DEX = 1; const val INT = 2; const val EXP = 3; const val POINTS = 4
        const val HAND = 8; const val COLD = 9; const val RANGED = 10; const val PARRY = 11; const val DODGE = 12
        const val MRES = 14; const val MDODGE = 15

        fun of(str: Int, dex: Int, int: Int, exp: Int = 0, points: Int = 0) = Skills().apply {
            this[STR] = str; this[DEX] = dex; this[INT] = int; this[EXP] = exp; this[POINTS] = points
        }
    }
}

/** Integer random in [from, to], inclusive both ends like PHP rand(). */
fun interface Dice {
    fun roll(from: Int, to: Int): Int

    companion object {
        fun of(random: Random) = Dice { a, b -> if (a >= b) a else random.nextInt(a, b + 1) }
    }
}

object Formulas {
    private fun phpRound(x: Double): Int = Math.round(x).toInt()   // half away from zero for x >= 0, as PHP round()

    /**
     * Player combat parameters from attributes, skills and equipped items:
     * f_calcparam.dat with the gems set into items ("i.a.b.dr..ob..am"), but
     * without sharpening, sets and the leadership flag (not in the game yet).
     * [item] gives the item's content/ JSON.
     */
    fun player(skills: Skills, equipped: List<String>, item: (String) -> JsonObject?, mounted: Boolean = false): Stats {
        val str = skills[Skills.STR]; val dex = skills[Skills.DEX]; val int = skills[Skills.INT]
        var hit = 0; var dmgMin = 0; var dmgMax = 0; var delay = 0; var ranged = false
        var armor = 0; var shieldArmor = 0; var verb = ""; var ammo = ""
        var parry = 2 * (dex + skills[Skills.PARRY] + (str - 1) * 2)
        var dodge = dex + skills[Skills.DODGE] + (str - 1) * 2
        var magicDodge = 5 * (int + skills[Skills.MDODGE] - str)
        var magicParry = 10 * (int + skills[Skills.MRES] - str)
        var magicResist = 15 * (skills[Skills.MRES] + int - str)
        var hitPenalty = 0
        var weapon = false
        var hpBonus = 0
        var manaBonus = 0
        val gemsUsed = HashSet<String>()

        // A gem effect [index, value]: war[index] += value, or char[index − 50] for HP (52) and mana (54).
        fun addEffect(index: Int, v: Int) {
            when (index) {
                0 -> hit += v
                1 -> dmgMin += v
                2 -> dmgMax += v
                3 -> delay += v
                5 -> armor += v
                6 -> dodge += v
                7 -> parry += v
                8 -> shieldArmor += v
                9 -> magicDodge += v
                10 -> magicParry += v
                11 -> magicResist += v
                52 -> hpBonus += v
                54 -> manaBonus += v
            }
        }

        for (id in equipped) {
            val o = item(baseId(id)) ?: continue
            val req = requirement(o, id)
            val strDef = (req.getOrElse(0) { 0 } - str).coerceAtLeast(0)
            val dexDef = (req.getOrElse(1) { 0 } - dex).coerceAtLeast(0)
            val intDef = (req.getOrElse(2) { 0 } - int).coerceAtLeast(0)
            hitPenalty += dexDef * 10
            if (id.startsWith("i.a.")) {
                val a = o.int("armor") ?: 0
                val eff = a - strDef * 2 - intDef * 2
                if (id.startsWith("i.a.s.")) { if (eff > 0) shieldArmor = eff } else if (eff > 0) armor += eff
            }
            if (id.startsWith("i.w.")) {
                weapon = true
                delay = (o.int("speed") ?: 0) - phpRound(dex / 2.0)
                if (id.startsWith("i.w.r.")) ranged = true
                verb = o.str("verb") ?: ""
                ammo = o.str("ammo") ?: ""
                hit += if (ranged) 10 * (dex + skills[Skills.RANGED] - 1) - (if (mounted) 10 else 0)
                else 10 * (dex + skills[Skills.COLD])
                val min = o.int("dmg_min") ?: 0
                val max = o.int("dmg_max") ?: 0
                if (verb == "магией") {
                    dmgMin += min - strDef * 2 - intDef * 4
                    dmgMax += max - strDef * 2 - intDef * 5
                } else {
                    dmgMin += min - strDef * 2 - intDef * 2
                    dmgMax += max - strDef * 2 - intDef * 2
                }
                // Sharpening "-N-" adds up to +6 (f_calcparam.dat:68-70).
                SHARP.find(id)?.groupValues?.get(1)?.toIntOrNull()?.let { val n = it.coerceAtMost(6); dmgMin += n; dmgMax += n }
                if (!id.startsWith("i.w.r.c.")) { dmgMin += str; dmgMax += str }
            }
            // Gems: each kind counts once however many items carry it.
            for (m in GEM.findAll(id)) {
                val gem = m.groupValues[1]
                if (!gemsUsed.add(gem)) continue
                val effects = item("i.i.$gem")?.get("effects") as? kotlinx.serialization.json.JsonArray ?: continue
                for (e in effects) {
                    val pair = (e as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                    if (pair != null && pair.size == 2) addEffect(pair[0], pair[1])
                }
            }
        }
        if (!weapon) {
            dmgMin += str + skills[Skills.HAND] - 1
            dmgMax += str + skills[Skills.HAND] + 1
            hit += 10 * (dex + skills[Skills.HAND] + 2)
            if (hit >= 100) hit = 95
            if (mounted) hit -= 20
            delay = 5 - phpRound(dex / 2.0)
            verb = "кулаками"
        }
        // Sets (f_calcparam.dat:100-102): the adamant one, and the ogre and troll one with a wolf's head.
        fun wears(part: String) = equipped.any { part in it }
        if (wears("i.a.h.ms") && wears("i.a.b.sborn") && wears("i.a.p.ms") && wears("i.a.l.ms") && wears("i.w.s.master")) {
            hpBonus += 5; manaBonus += 5; hit += 5; armor += 5; dmgMin += 4; dmgMax += 3
        }
        if (wears("i.a.l.ogr") && wears("i.a.p.ogr") && wears("i.a.b.troll") && (wears("i.a.h.whitewolf") || wears("i.a.h.wolf"))) {
            hpBonus += 5; manaBonus += 5; armor += 4
        }
        if (equipped.any { it.contains("..do") }) hitPenalty += 20   // dolerite: −20 % accuracy
        hit -= hitPenalty
        if (hit <= 0) hit = 5
        if (hit > 95) hit = 95
        if (equipped.none { it.startsWith("i.a.s.") }) parry = 0
        return Stats(
            hit = hit,
            dmgMin = dmgMin.coerceAtLeast(0),
            dmgMax = dmgMax.coerceAtLeast(0),
            delay = delay.coerceAtLeast(3),
            ranged = ranged,
            armor = armor.coerceAtLeast(0),
            dodge = dodge.coerceAtLeast(0),
            parry = parry.coerceAtLeast(0),
            shieldArmor = shieldArmor,
            magicDodge = magicDodge.coerceAtLeast(0),
            magicParry = magicParry.coerceAtLeast(0),
            magicResist = magicResist,
            verb = verb,
            expValue = skills.sumExceptExp(),
            ammo = ammo,
            hpBonus = hpBonus,
            manaBonus = manaBonus,
            critBonus = if (equipped.any { it.contains("..kp") }) 4 else 0,
        )
    }

    private val SHARP = Regex("""-(\d+)-""")
    private val GEM = Regex("""\.\.([A-Za-z0-9]+)""")

    /** "str:dex:int[:hp]" of armour (field req) or a weapon (field req). */
    private fun requirement(o: JsonObject, id: String): List<Int> {
        if (!id.startsWith("i.a.") && !id.startsWith("i.w.")) return emptyList()
        val r = o["req"] ?: return emptyList()
        return when (r) {
            is kotlinx.serialization.json.JsonArray -> r.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
            is kotlinx.serialization.json.JsonPrimitive -> r.content.split(':').map { it.toIntOrNull() ?: 0 }
            else -> emptyList()
        }
    }

    /** Base item id without maker, sharpening and gem suffixes (data-fields §0). */
    fun baseId(id: String): String = when {
        '_' in id -> id.substringBefore('_')
        '-' in id -> id.substringBefore('-')
        else -> id
    }

    /** NPC parameters straight from its `war` fields (NPCs never go through calcparam). */
    fun npc(war: JsonObject?): Stats = Stats(
        hit = war?.int("hit_chance") ?: 0,
        dmgMin = war?.int("dmg_min") ?: 0,
        dmgMax = war?.int("dmg_max") ?: 0,
        delay = (war?.int("attack_delay") ?: 5).coerceAtLeast(1),
        ranged = (war?.int("is_ranged") ?: 0) != 0,
        armor = war?.int("armor") ?: 0,
        dodge = war?.int("dodge") ?: 0,
        parry = war?.int("parry") ?: 0,
        shieldArmor = war?.int("shield_armor") ?: 0,
        magicDodge = war?.int("magic_dodge") ?: 0,
        magicParry = war?.int("magic_parry") ?: 0,
        magicResist = war?.int("magic_resist") ?: 0,
        verb = war?.str("verb")?.takeIf { it.isNotBlank() } ?: "бьёт",
        expValue = war?.int("exp_value") ?: 0,
        ammo = war?.str("ammo") ?: "",
    )

    enum class Outcome { MISS, DODGED, HIT, FIZZLED }

    data class Hit(val outcome: Outcome, val damage: Int = 0, val crit: Boolean = false, val shield: Int = 0, val resisted: Int = 0)

    /**
     * One blow, f_attackf.dat §2.5–2.6, in the same order of dice rolls.
     * Deliberate change: the old «time of day» term (always −rand(0,5)
     * because of a bug) is left out.
     */
    fun attack(a: Stats, d: Stats, dice: Dice): Hit {
        val magic = a.magic
        if (magic && a.hit == 0) return Hit(Outcome.FIZZLED)
        if (dice.roll(0, 100) > a.hit) return Hit(Outcome.MISS)
        var damage = dice.roll(minOf(a.dmgMin, a.dmgMax), maxOf(a.dmgMin, a.dmgMax))
        val dodge = if (magic) d.magicDodge else d.dodge
        if (dice.roll(0, 100) <= dodge) return Hit(Outcome.DODGED)
        val parry = if (magic) d.magicParry else d.parry
        val shield = if (magic) d.magicResist else d.shieldArmor
        var shieldCut = 0
        var resisted = 0
        if (parry > 0 && shield > 0 && dice.roll(0, 100) <= parry) {
            if (!magic) { shieldCut = shield; damage -= shield }
            else {
                val resist = phpRound(damage * shield / 100.0)
                resisted = if (resist > 0) dice.roll(0, resist) else 0
                damage -= resisted
            }
        }
        if (!magic && d.armor > 0) damage -= dice.roll(0, d.armor)
        if (damage < 0) damage = 0
        val critChance = (if (a.ranged) 5 else if (magic) 4 else 2) + a.critBonus
        var crit = false
        if (damage > 0 && dice.roll(0, 100) < critChance) { damage *= 2; crit = true }
        return Hit(Outcome.HIT, damage, crit, shieldCut, resisted)
    }

    /** Experience needed for the next point: over war[13]·g_exp (f_kill.dat:105-112). */
    fun expThreshold(skills: Skills): Int = skills.sumExceptExp() * 10

    /** Regeneration after [seconds] without being hit (g.php:571-579): +round(t/(30−4·skill)). */
    fun regen(seconds: Long, skill: Int = 0): Int =
        if (seconds <= 30) 0 else phpRound(seconds.toDouble() / (30 - 4 * skill).coerceAtLeast(1))
}
