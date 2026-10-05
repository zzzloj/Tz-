package tz.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The balance simulator on the server's own formulas (tools/balance/check.py does
 * the same on the Python model): a warrior of each monster's level, with the
 * points spent as a warrior would and the best things two levels below, fights
 * every ordinary monster (balance.md §13: ~18 s and about a third of health at
 * one's own level). It fails when a monster of one's level becomes a wall or a
 * walkover, so a change of the formulas or of content/balance shows at once.
 */
class BalanceFightTest {
    private val content by lazy { Content.load(contentDir()) }
    private val b by lazy { content.balance }

    /** Points as tools/balance/sim.py spends them for a warrior. */
    private fun warrior(level: Int): Skills {
        val s = Skills.of(b.attrStart, b.attrStart, b.attrStart)
        // str, cold weapon, dex, dodge, parry, regeneration, hand to 4, awareness (sim.py PLANS["warrior"])
        val plan = listOf(Skills.STR to 10, Skills.COLD to 10, Skills.DEX to 10, Skills.DODGE to 10, Skills.PARRY to 10, 16 to 10, Skills.HAND to 4, 18 to 10)
        var points = b.totalPoints(level)
        val attrs = setOf(Skills.STR, Skills.DEX, Skills.INT)
        while (points > 0) {
            var moved = false
            for ((k, cap) in plan) {
                if (points == 0) break
                val max = minOf(cap, if (k in attrs) b.attrMax else b.skillMax)
                if (s[k] >= max) continue
                if (k in attrs && attrs.sumOf { s[it] } >= b.attrSumMax) continue
                s[k] = s[k] + 1; points--; moved = true
            }
            if (!moved) break
        }
        return s
    }

    private fun num(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0

    /** The best sword, shield and armour in every slot of item level ≤ [tier] that the hero may wear. */
    private fun gear(s: Skills, level: Int, tier: Int): List<String> {
        fun fits(id: String): Boolean {
            val r = Formulas.requirement(content.itemBalance(id))
            return r[0] <= minOf(tier, level) && s[Skills.STR] >= r[1] && s[Skills.DEX] >= r[2] && s[Skills.INT] >= r[3]
        }
        val all = content.balanceItems.filterKeys { !it.startsWith("_") && it in content.items }
        // The best melee weapon by damage per second of its class's pause (tools/balance/sim.py real_gear).
        val melee = setOf("knife", "sword", "axe", "spear", "rapier", "heavy")
        val sword = all.filter { (id, o) -> o["type"]?.jsonPrimitive?.content == "weapon" && o["class"]?.jsonPrimitive?.content in melee && fits(id) }
            .maxByOrNull { (_, o) ->
                val d = (o["dmg"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content.toDouble() } ?: listOf(0.0, 0.0)
                (d[0] + d[1]) / 2 / b.pause(o["class"]!!.jsonPrimitive.content, 0)
            }?.key
        val armour = all.filter { (id, o) -> o["type"]?.jsonPrimitive?.content == "armor" && fits(id) && num(o, "armor") > 0 }
            .entries.groupBy { tz.shared.Rules.equipSlot(it.key) }
            .mapNotNull { (_, list) -> list.maxByOrNull { num(it.value, "armor") }?.key }
        return listOfNotNull(sword) + armour
    }

    private class Result(val win: Double, val seconds: Double, val lost: Double)

    private fun fights(hero: Stats, mob: Stats, mobHp: Int, n: Int, random: Random): Result {
        val dice = Dice.of(random)
        var wins = 0; var time = 0.0; var lost = 0.0
        repeat(n) {
            var hp = hero.hpMax; var enemy = mobHp
            var th = 0L; var tm = 300L
            while (true) {
                if (th <= tm) {
                    enemy -= Formulas.attack(b, hero, mob, dice).damage
                    if (enemy <= 0) { wins++; time += th / 1000.0; lost += 1 - hp.toDouble() / hero.hpMax; break }
                    th += hero.pauseMs
                } else {
                    hp -= Formulas.attack(b, mob, hero, dice).damage
                    if (hp <= 0) break
                    tm += mob.pauseMs
                }
                if (th > 600_000) break
            }
        }
        return Result(wins.toDouble() / n, if (wins > 0) time / wins else 0.0, if (wins > 0) lost / wins else 1.0)
    }

    @Test
    fun aWarriorAgainstMonstersOfHisLevel() {
        val random = Random(1)
        val mobs = content.balanceNpcs.filter { (k, o) ->
            !k.startsWith("_") && o["kind"]?.jsonPrimitive?.content in setOf("mob", "animal") && content.npcs.containsKey(k)
        }
        assertTrue(mobs.size > 50, "ordinary monsters in content/balance/npcs.json: ${mobs.size}")
        val bands = HashMap<Int, MutableList<Result>>()
        val walls = ArrayList<String>()
        for ((key, o) in mobs.entries.sortedBy { num(it.value, "level") }) {
            val level = num(o, "level").toInt().coerceIn(1, 50)
            val skills = warrior(level)
            val hero = Formulas.player(b, skills, level, gear(skills, level, level - 2), { content.items[it] }, { content.itemBalance(it) }, content.sets)
            val mob = Formulas.npc(content.npcs[key]?.get("war") as? JsonObject, o)
            val r = fights(hero, mob, num(o, "hp").toInt(), 100, random)
            bands.getOrPut((level - 1) / 10) { ArrayList() } += r
            if (r.win < 0.8 || r.seconds > 40) walls += "$key L$level: ${(r.win * 100).toInt()}% ${r.seconds.toInt()} s"
        }
        for ((band, list) in bands.toSortedMap()) println(
            "L${band * 10 + 1}-${band * 10 + 10}: win ${(list.map { it.win }.average() * 100).toInt()}%, " +
                "${"%.1f".format(list.map { it.seconds }.average())} s, health lost ${(list.map { it.lost }.average() * 100).toInt()}%"
        )
        assertTrue(walls.isEmpty(), "monsters a warrior of their level cannot beat in time: $walls")
        for ((band, list) in bands) {
            val seconds = list.map { it.seconds }.average()
            val lost = list.map { it.lost }.average()
            assertTrue(seconds in 6.0..35.0, "levels ${band * 10 + 1}-${band * 10 + 10}: a fight lasts $seconds s")
            assertTrue(lost in 0.10..0.65, "levels ${band * 10 + 1}-${band * 10 + 10}: a fight costs ${lost * 100} % of health")
        }
    }
}
