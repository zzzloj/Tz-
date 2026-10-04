package tz.server

import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/**
 * Combat parameters of a character or NPC on the balance of 04.10.2026
 * (claude/balance.md): accuracy and evasion in points (hit chance =
 * 60 + accuracy − evasion), damage per blow, the pause in milliseconds, armour
 * and magic defence that stop a share of damage, block chance with a shield,
 * crit only from items.
 */
@kotlinx.serialization.Serializable
data class Stats(
    /** Accuracy (points); 0 for magic means the spell fizzles (a stance stopped it). */
    val hit: Int,
    val dmgMin: Int,
    val dmgMax: Int,
    /** Pause after a blow, milliseconds. */
    val pauseMs: Long,
    val ranged: Boolean,
    val armor: Int,
    /** Evasion (points) against blows and against magic. */
    val dodge: Int,
    /** Block chance in percent (only with a shield). */
    val parry: Int,
    val magicDodge: Int,
    /** Magic defence (points): stops a share of magic damage. */
    val magicResist: Int,
    val verb: String,
    /** Experience for killing (NPCs). */
    val expValue: Long,
    val ammo: String,
    val level: Int = 1,
    /** Maximum health and mana before spell buffs (characters). */
    val hpMax: Int = 0,
    val manaMax: Int = 0,
    val critChance: Double = 5.0,
    val critMult: Double = 1.5,
    /** Armour the blow meets: 0.75 heavy weapons, 1.15 light ones. */
    val pen: Double = 1.0,
    /** A gem in the weapon: ignite, chill or poison. */
    val ailment: String? = null,
    /** Gems in the necklace: these ailments last half as long on this character. */
    val ailmentGuard: Set<String> = emptySet(),
    /** Extra damage of spells, percent (sets). */
    val spellPct: Int = 0,
    val weaponClass: String = "hand",
) {
    val magic: Boolean get() = verb == "магией" || verb == "молнией"
    /** Pause in whole seconds, for messages and old-style timers. */
    val pauseSeconds: Long get() = (pauseMs + 999) / 1000
}

/** Damage over time from an ailment (ignite, poison): [perSecond] till [untilMs]. */
class Dot(val kind: String, val untilMs: Long, val perSecond: Double) { var carry = 0.0 }

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
    private fun d(o: JsonObject?, key: String): Double? = (o?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
    private fun ints(o: JsonObject?, key: String): List<Int> =
        (o?.get(key) as? kotlinx.serialization.json.JsonArray)?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0 } ?: emptyList()

    /** Effects of gems and sets in the new terms (content/balance/items.json "effects", sets.json "bonus"). */
    class Bonus {
        var acc = 0.0; var eva = 0.0; var hpPct = 0.0; var manaPct = 0.0; var armorPct = 0.0; var mdefPct = 0.0
        var dmgPct = 0.0; var pausePct = 0.0; var critChance = 0.0; var critMult = 0.0; var block = 0.0; var spellPct = 0.0
        fun add(e: JsonObject) {
            for ((k, v) in e) {
                val x = (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: continue
                when (k) {
                    "acc" -> acc += x; "eva" -> eva += x; "hpPct" -> hpPct += x; "manaPct" -> manaPct += x
                    "armorPct" -> armorPct += x; "mdefPct" -> mdefPct += x; "dmgPct" -> dmgPct += x; "pausePct" -> pausePct += x
                    "critChance" -> critChance += x; "critMult" -> critMult += x; "block" -> block += x; "spellPct" -> spellPct += x
                }
            }
        }
    }

    /** Requirements of a recalculated item: [level, str, dex, int] — what a character must have to put it on. */
    fun requirement(ov: JsonObject?): List<Int> = listOf(d(ov, "level")?.toInt() ?: 1) + ints(ov, "req").let { it + List(3 - it.size.coerceAtMost(3)) { 0 } }.take(3)

    /**
     * A character's combat parameters (balance.md §5, §13): from attributes,
     * skills, level and the things worn. [item] gives the content JSON, [ov]
     * the new balance of an item (content/balance/items.json).
     */
    fun player(
        b: Balance, skills: Skills, level: Int, equipped: List<String>,
        item: (String) -> JsonObject?, ov: (String) -> JsonObject?, sets: List<JsonObject> = emptyList(), mounted: Boolean = false,
    ): Stats {
        val str = skills[Skills.STR]; val dex = skills[Skills.DEX]; val int = skills[Skills.INT]
        val bonus = Bonus()
        var armor = 0.0
        var weaponId: String? = null
        var shield = false
        var ailment: String? = null
        val guard = HashSet<String>()
        val gemsUsed = HashSet<String>()
        for (id in equipped) {
            val base = baseId(id)
            val o = item(base) ?: continue
            val bo = ov(base) ?: ov(base.substringBefore(".."))
            if (id.startsWith("i.a.")) {
                armor += d(bo, "armor") ?: 0.0
                if (id.startsWith("i.a.s.")) shield = true
            }
            if (id.startsWith("i.w.")) weaponId = id
            for (m in GEM.findAll(id)) {
                val gem = m.groupValues[1]
                val e = ov("i.i.$gem")?.get("effects") as? JsonObject ?: continue
                val a = (e["ailment"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (a != null) {
                    // A gem of an ailment works in the weapon, and guards against it in the necklace.
                    if (id.startsWith("i.w.")) ailment = a else if (id.startsWith("i.a.m.")) guard += a
                    continue
                }
                if (gemsUsed.add(gem)) bonus.add(e)
            }
            if (o.isEmpty()) continue
        }
        // Set bonuses: groups of alternatives, a bonus by how many groups are worn.
        val bases = equipped.map { baseId(it).substringBefore("..") }.toSet()
        for (set in sets) {
            val groups = set["groups"] as? kotlinx.serialization.json.JsonArray ?: continue
            val worn = groups.count { g -> (g as? kotlinx.serialization.json.JsonArray)?.any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content in bases } == true }
            val by = set["bonus"] as? JsonObject ?: continue
            for ((n, e) in by) if (worn >= (n.toIntOrNull() ?: 99)) (e as? JsonObject)?.let { bonus.add(it) }
        }
        // Sharpening "-N-" (f_calcparam.dat:68-70): +5 % damage a point, at most +30 %.
        weaponId?.let { w -> SHARP.find(w)?.groupValues?.get(1)?.toIntOrNull()?.let { bonus.dmgPct += 5.0 * it.coerceAtMost(6) } }
        val wo = weaponId?.let { item(baseId(it)) }
        val wov = weaponId?.let { ov(baseId(it)) ?: ov(baseId(it).substringBefore("..")) }
        val cls = if (weaponId == null) "hand" else Balance.weaponClass(baseId(weaponId), wo?.str("name") ?: "", wov?.str("class"))
        val ranged = weaponId?.startsWith("i.w.r.") == true
        val verb = if (weaponId == null) "кулаками" else wo?.str("verb") ?: ""
        val magicWeapon = verb == "магией" || verb == "молнией"
        val wskill = when { weaponId == null -> skills[Skills.HAND]; ranged -> skills[Skills.RANGED]; else -> skills[Skills.COLD] }
        val pause = b.pause(cls, dex) * (1 + bonus.pausePct / 100)
        val dmg = ints(wov, "dmg").takeIf { it.size == 2 } ?: if (weaponId == null) listOf(1, 3) else listOf(
            wo?.int("dmg_min") ?: 0, wo?.int("dmg_max") ?: 1)
        val flat = if (cls == "crossbow" || magicWeapon) 0.0 else b.strengthBonus(str, pause)
        val mult = b.weaponSkillMultiplier(wskill) * (1 + bonus.dmgPct / 100)
        var acc = b.accuracy(dex, wskill, level) + bonus.acc
        if (mounted) acc -= if (ranged) 10 else 20
        val totalArmor = armor * (1 + bonus.armorPct / 100)
        return Stats(
            hit = acc.toInt().coerceAtLeast(1),
            dmgMin = ((dmg[0] + flat) * mult).toInt().coerceAtLeast(0),
            dmgMax = Math.round((dmg[1] + flat) * mult).toInt().coerceAtLeast(1),
            pauseMs = (pause * 1000).toLong().coerceAtLeast(500),
            ranged = ranged,
            armor = Math.round(totalArmor).toInt(),
            dodge = (b.evasion(dex, skills[Skills.DODGE], level) + bonus.eva).toInt().coerceAtLeast(0),
            parry = if (shield) (b.blockChance(skills[Skills.PARRY], dex) + bonus.block).toInt() else 0,
            magicDodge = b.evasion(dex, skills[Skills.MDODGE], level).toInt(),
            magicResist = (b.magicDefence(int, skills[Skills.MRES], totalArmor) * (1 + bonus.mdefPct / 100)).toInt(),
            verb = verb,
            expValue = 0,
            ammo = wo?.str("ammo") ?: "",
            level = level,
            hpMax = Math.round(b.hpMax(str, level) * (1 + bonus.hpPct / 100)).toInt().coerceAtLeast(1),
            manaMax = Math.round(b.manaMax(int, level) * (1 + bonus.manaPct / 100)).toInt().coerceAtLeast(0),
            critChance = b.critBase(cls) + bonus.critChance,
            critMult = b.critMultiplier + bonus.critMult,
            pen = b.penetration(cls),
            ailment = ailment,
            ailmentGuard = guard,
            spellPct = bonus.spellPct.toInt(),
            weaponClass = cls,
        )
    }

    private val GEM = Regex("""\.\.([A-Za-z0-9]+)""")
    private val SHARP = Regex("""-(\d+)-""")

    /** Base item id without maker, sharpening and gem suffixes (data-fields §0). */
    fun baseId(id: String): String = when {
        '_' in id -> id.substringBefore('_')
        '-' in id -> id.substringBefore('-')
        else -> id
    }

    /**
     * NPC parameters: the new balance of its template ([ov], content/balance/npcs.json)
     * with the verb and range of its old `war` fields.
     */
    fun npc(war: JsonObject?, ov: JsonObject?): Stats {
        val dmg = ints(ov, "dmg").takeIf { it.size == 2 } ?: listOf(war?.int("dmg_min") ?: 0, war?.int("dmg_max") ?: 0)
        val verb = war?.str("verb")?.takeIf { it.isNotBlank() } ?: "бьёт"
        val eva = d(ov, "evasion") ?: (war?.int("dodge") ?: 0).toDouble()
        return Stats(
            hit = (d(ov, "accuracy") ?: (war?.int("hit_chance") ?: 50).toDouble()).toInt(),
            dmgMin = dmg[0], dmgMax = dmg[1],
            pauseMs = ((d(ov, "pause") ?: (war?.int("attack_delay") ?: 4).toDouble()) * 1000).toLong().coerceAtLeast(500),
            ranged = (war?.int("is_ranged") ?: 0) != 0,
            armor = (d(ov, "armor") ?: (war?.int("armor") ?: 0).toDouble()).toInt(),
            dodge = eva.toInt(),
            parry = 0,
            magicDodge = eva.toInt(),
            magicResist = (d(ov, "magicDefence") ?: (war?.int("magic_resist") ?: 0).toDouble()).toInt(),
            verb = verb,
            expValue = d(ov, "exp")?.toLong() ?: (war?.int("exp_value") ?: 0).toLong(),
            ammo = war?.str("ammo") ?: "",
            level = d(ov, "level")?.toInt() ?: 1,
            critChance = 3.0,
        )
    }

    enum class Outcome { MISS, DODGED, HIT, FIZZLED }

    data class Hit(val outcome: Outcome, val damage: Int = 0, val crit: Boolean = false, val shield: Int = 0, val resisted: Int = 0)

    /**
     * One blow (balance.md §5): hit chance = 60 + accuracy − evasion (15–95 %);
     * a shield may block half of it; armour stops a share of a physical blow
     * (heavy weapons meet less of it), magic defence of a magic one; crit
     * multiplies what is left.
     */
    fun attack(b: Balance, a: Stats, d: Stats, dice: Dice): Hit {
        val magic = a.magic
        if (magic && a.hit == 0) return Hit(Outcome.FIZZLED)
        val chance = b.hitChance(a.hit.toDouble(), (if (magic) d.magicDodge else d.dodge).toDouble())
        if (dice.roll(0, 99) >= chance) return Hit(Outcome.MISS)
        var damage = dice.roll(minOf(a.dmgMin, a.dmgMax), maxOf(a.dmgMin, a.dmgMax)).toDouble()
        var shield = 0
        var resisted = 0
        if (!magic && d.parry > 0 && dice.roll(0, 99) < d.parry) {
            shield = Math.round(damage * b.blockCut).toInt()
            damage -= shield
        }
        if (magic) {
            resisted = Math.round(damage * b.magicCut(d.magicResist.toDouble(), a.level)).toInt()
            damage -= resisted
        } else damage *= 1 - b.armorCut(d.armor.toDouble(), a.level, a.pen)
        var crit = false
        if (damage > 0 && dice.roll(0, 9999) < a.critChance * 100) { damage *= a.critMult; crit = true }
        val dealt = if (damage <= 0) 0 else Math.round(damage).toInt().coerceAtLeast(1)
        return Hit(Outcome.HIT, dealt, crit, shield, resisted)
    }
}
