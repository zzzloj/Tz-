package tz.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The new balance (owner 04.10.2026, claude/balance.md): levels 1–50 with
 * training points, attributes 1–10, skills 0–10, crafts grown by practice,
 * pauses of 1–1.5 s by weapon class, crit only from items, armour as a share.
 * Every number comes from content/logic/balance.json; these are pure formulas,
 * mirrored by the simulator (bal/model.py) that calibrated them.
 */
class Balance(private val o: JsonObject) {

    private fun obj(vararg path: String): JsonObject = path.fold(o) { acc, k -> acc[k]?.jsonObject ?: JsonObject(emptyMap()) }
    private fun num(o: JsonObject, key: String, default: Double): Double = (o[key] as? JsonPrimitive)?.doubleOrNull ?: default
    private fun list(o: JsonObject, key: String): List<Double> = (o[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()
    private fun map(o: JsonObject): Map<String, Double> = o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap()

    // ---- levels and experience -----------------------------------------------------------
    private val lv = obj("level")
    val maxLevel = num(lv, "max", 50.0).toInt()
    private val expBase = num(lv, "expBase", 25.0)
    private val expPower = num(lv, "expPower", 2.7)
    val creationPoints = num(lv, "creationPoints", 4.0).toInt()
    private val pointsPerLevel = num(lv, "pointsPerLevel", 2.0).toInt()
    private val bonusEvery = num(lv, "bonusEvery", 10.0).toInt()
    private val bonusPoints = num(lv, "bonusPoints", 2.0).toInt()

    /** Experience from level [l] to l + 1. */
    fun expToNext(l: Int): Long = (expBase * l.toDouble().pow(expPower)).roundToLong()

    /** Total experience needed to stand on level [l] (level 1 needs 0). */
    fun expForLevel(l: Int): Long { var s = 0L; for (i in 1 until l) s += expToNext(i); return s }

    /**
     * Level reached with [totalExp]; experience keeps counting past the top
     * level (owner 04.10), so raising "max" later grants the levels at once.
     */
    fun level(totalExp: Long): Int {
        var l = 1
        var need = 0L
        while (l < maxLevel) { need += expToNext(l); if (totalExp < need) break; l++ }
        return l
    }

    /** Training points granted on reaching [l] (l ≥ 2). */
    fun pointsForLevel(l: Int): Int = pointsPerLevel + if (bonusEvery > 0 && l % bonusEvery == 0) bonusPoints else 0

    /** All training points a character on level [l] has earned, spent or not. */
    fun totalPoints(l: Int): Int = creationPoints + (2..l).sumOf { pointsForLevel(it) }

    // ---- attributes, skills, crafts --------------------------------------------------------
    private val at = obj("attributes")
    val attrStart = num(at, "start", 2.0).toInt()
    val attrMax = num(at, "max", 10.0).toInt()
    val attrSumMax = num(at, "sumMax", 24.0).toInt()
    val skillMax = num(obj("skills"), "max", 10.0).toInt()
    private val cr = obj("crafts")
    val crafts: Set<String> = (cr["list"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()
    val craftMax = num(cr, "max", 10.0).toInt()
    val craftSumMax = num(cr, "sumMax", 30.0).toInt()
    val craftTeacherMax = num(cr, "teacherMax", 2.0).toInt()
    private val craftSteps = list(cr, "expToNext")

    /** Practice needed to raise a craft from [step] to step + 1. */
    fun craftExpToNext(step: Int): Int = craftSteps.getOrNull(step)?.roundToInt() ?: Int.MAX_VALUE

    /** A teacher's price for the [n]-th step of anything: 15·n² coins. */
    fun teacherPrice(n: Int): Int = (num(o, "teacherPrice", 15.0) * n * n).roundToInt()

    // ---- hero ------------------------------------------------------------------------------
    private val h = obj("hero")
    fun hpMax(str: Int, l: Int): Int = (num(h, "hpBase", 15.0) + num(h, "hpPerStr", 5.0) * str + num(h, "hpPerLevel", 3.0) * (l - 1)).roundToInt()
    fun manaMax(int: Int, l: Int): Int = (num(h, "manaBase", 10.0) + num(h, "manaPerInt", 5.0) * int + num(h, "manaPerLevel", 2.0) * (l - 1)).roundToInt()
    fun accuracy(dex: Int, weaponSkill: Int, l: Int): Double = num(h, "accPerDex", 2.0) * dex + num(h, "accPerWeaponSkill", 3.0) * weaponSkill + l
    fun evasion(dex: Int, dodge: Int, l: Int): Double = num(h, "evaPerDex", 1.0) * dex + num(h, "evaPerDodge", 3.0) * dodge + l
    fun hitChance(acc: Double, eva: Double): Double = (num(h, "hitBase", 60.0) + acc - eva).coerceIn(num(h, "hitMin", 15.0), num(h, "hitMax", 95.0))

    /** Flat strength bonus per blow (not crossbows, not magic) — str/2 per 4 s of pause, so damage per second stays fair. */
    fun strengthBonus(str: Int, pause: Double): Double = num(h, "strDamagePer4s", 0.5) * str * pause / 4
    fun weaponSkillMultiplier(skill: Int): Double = 1 + num(h, "weaponSkillDamage", 0.03) * skill

    /** Pause between blows for a weapon class, shortened by dexterity. */
    fun pause(weaponClass: String, dex: Int): Double {
        val base = map(obj("pause"))[weaponClass] ?: 1.2
        return maxOf(num(h, "pauseMin", 0.7), base * (1 - num(h, "pauseDexCut", 0.02) * dex))
    }
    fun pauseMillis(weaponClass: String, dex: Int): Long = (pause(weaponClass, dex) * 1000).roundToLong()

    /** Regeneration every few seconds out of combat: a share of maximum HP. */
    fun regenPerTick(maxHp: Int, regenSkill: Int, safe: Boolean): Double =
        maxHp * (num(h, "regenBase", 0.015) + num(h, "regenPerSkill", 0.003) * regenSkill) * (if (safe) num(h, "regenSafeMult", 3.0) else 1.0)
    val regenAfter get() = num(h, "regenAfter", 10.0).toLong()
    val regenEvery get() = num(h, "regenEvery", 5.0).toLong()

    /** Damage per second of a tier-[t] weapon (items, spells and bottles scale by it). */
    fun weaponDps(t: Int): Double { val w = obj("items"); return num(w, "dpsBase", 1.2) + num(w, "dpsPerTier", 0.36) * (t - 1) }

    // ---- crit, armour, magic, block -----------------------------------------------------------
    private val cr2 = obj("crit")
    /** Base crit chance of a weapon class, in percent; items add to it (no caps). */
    fun critBase(weaponClass: String): Double = map(obj("crit", "base"))[weaponClass] ?: num(cr2, "default", 5.0)
    val critMultiplier get() = num(cr2, "mult", 1.5)

    private val ar = obj("armor")
    /** Heavy weapons meet 25 % less armour, light ones 15 % more. */
    fun penetration(weaponClass: String): Double = when (weaponClass) {
        "heavy", "crossbow" -> num(ar, "heavyPen", 0.75)
        "knife", "rapier", "thrown" -> num(ar, "lightPen", 1.15)
        else -> 1.0
    }
    private fun k(attackerLevel: Int) = num(ar, "k", 50.0) + num(ar, "kPerLevel", 10.0) * attackerLevel
    /** Share of physical damage the armour stops (0..1). */
    fun armorCut(armor: Double, attackerLevel: Int, pen: Double = 1.0): Double {
        val a = armor * pen
        return if (a <= 0) 0.0 else a / (a + k(attackerLevel))
    }
    private val mg = obj("magic")
    fun magicDefence(int: Int, magicResist: Int, armor: Double): Double =
        num(mg, "perInt", 5.0) * int + num(mg, "perResist", 8.0) * magicResist + num(mg, "armorShare", 0.25) * armor
    fun magicCut(defence: Double, attackerLevel: Int): Double = if (defence <= 0) 0.0 else defence / (defence + k(attackerLevel))

    private val bl = obj("block")
    /** Shield block chance in percent; a blocked blow loses [blockCut] of its damage. */
    fun blockChance(parry: Int, dex: Int): Double =
        minOf(num(bl, "max", 30.0), num(bl, "base", 5.0) + num(bl, "perParry", 2.0) * parry + num(bl, "perDex", 0.5) * dex)
    val blockCut get() = num(bl, "cut", 0.5)

    // ---- ailments from gems -------------------------------------------------------------------
    data class Ailment(val chance: Double, val share: Double, val seconds: Double, val slow: Double, val stacks: Int)
    fun ailment(name: String): Ailment {
        val a = obj("ailments", name)
        return Ailment(num(a, "chance", 0.0), num(a, "share", 0.0), num(a, "seconds", 0.0), num(a, "slow", 0.0), num(a, "stacks", 1.0).toInt())
    }
    val amuletCut get() = num(obj("ailments"), "amuletCut", 0.5)

    // ---- monsters -----------------------------------------------------------------------------
    private val mo = obj("monsters")
    private fun poly(c: List<Double>, l: Int) = c.withIndex().sumOf { (i, a) -> a * l.toDouble().pow(i) }
    /** Ordinary monster of level [l]: health, damage per 4 s (a blow is this × pause / 4). */
    fun monsterHp(l: Int): Double = maxOf(num(mo, "hpMin", 18.0), poly(list(mo, "hp"), l))
    fun monsterDamagePer4s(l: Int): Double = poly(list(mo, "dmgPer4s"), l)
    fun monsterExp(l: Int): Long { val e = list(mo, "exp"); return (e.getOrElse(0) { 8.0 } * l.toDouble().pow(e.getOrElse(1) { 1.45 })).roundToLong() }
    fun monsterGold(l: Int): Int { val g = list(mo, "gold"); return (g.getOrElse(0) { 2.0 } + g.getOrElse(1) { .9 } * l.toDouble().pow(g.getOrElse(2) { 1.6 })).roundToInt() }
    /** Kind multipliers: health, damage, experience (and gold). */
    fun kind(kind: String): Triple<Double, Double, Double> {
        val k = (obj("monsters", "kinds")[kind] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: listOf(1.0, 1.0, 1.0)
        return Triple(k[0], k[1], k[2])
    }

    /** Experience share for a hero of [heroLevel] killing a monster of [mobLevel]. */
    fun expByGap(heroLevel: Int, mobLevel: Int): Double {
        val g = obj("expByLevelGap"); val d = mobLevel - heroLevel
        return when {
            d >= 0 -> minOf(num(g, "upMax", 1.5), 1 + num(g, "upPerLevel", .08) * d)
            d >= -num(g, "freeBelow", 4.0) -> 1.0
            else -> maxOf(num(g, "downMin", .1), 1 - num(g, "downPerLevel", .15) * (-d - num(g, "freeBelow", 4.0)))
        }
    }
    val groupBonusPerMember get() = num(o, "groupBonusPerMember", 0.10)

    // ---- necromancy (owner 04.10: not a magic skill) -----------------------------------------
    private val ne = obj("necro")
    fun raiseChance(necro: Int, topaz: Boolean): Double = num(ne, "perSkill", 10.0) * necro + if (topaz) num(ne, "topaz", 10.0) else 0.0
    val raiseMana get() = num(ne, "mana", 6.0).toInt()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String) = Balance(json.parseToJsonElement(text).jsonObject)
        fun load(file: File): Balance = if (file.isFile) parse(file.readText()) else Balance(JsonObject(emptyMap()))

        /**
         * Weapon class of an item (balance.md §13): what sets its pause, crit
         * and armour penetration. Recalculated items carry it in "class".
         */
        fun weaponClass(id: String, name: String, explicit: String? = null): String {
            if (!explicit.isNullOrBlank()) return explicit
            val n = name.lowercase()
            return when {
                !id.startsWith("i.w.") -> "hand"
                id.startsWith("i.w.r.c.") -> "crossbow"
                id.startsWith("i.w.r.b.") -> "bow"
                listOf("двуручн", "алебард", "секир", "фламберг", "двухсторонн", "глеф").any { it in n } -> "heavy"
                id.startsWith("i.w.r.") -> "thrown"
                id.startsWith("i.w.k.") -> "knife"
                id.startsWith("i.w.spear") -> "spear"
                "шпага" in n -> "rapier"
                id.startsWith("i.w.t.") -> "axe"
                id.startsWith("i.w.u.") -> "staff"
                else -> "sword"
            }
        }
    }
}

