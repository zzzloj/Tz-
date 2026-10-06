package tz.server

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Power (owner 06.10, balance.md §6): how strong a fighter is, from the same
 * formulas as a blow (Formulas.attack), with no dice.
 *
 * - r(A, B) = (A's damage per second to B × A's health) / (B's damage per second to A × B's health):
 *   how many healths of B A takes while B takes one health of A.
 * - The chance A wins a duel = 1 / (1 + e^-(k·ln r + b)), k and b in content/logic/balance.json "power"
 *   (fitted 06.10 on 912 simulated duels; off by about 5 % where it is neither 0 nor 100).
 * - The power number = r against the ordinary monster of one's own level × that monster's health:
 *   an ordinary monster has about its health, a hero of the same level 2–3 times more.
 *
 * The same in tools/balance/model.py (power_ratio, win_chance), which builds every monster to a power target.
 */
object Power {
    /** Expected damage of one blow of [a] at [d]. */
    fun blow(b: Balance, a: Stats, d: Stats): Double {
        if (a.magic && a.hit == 0) return 0.0
        var x = b.hitChance(a.hit.toDouble(), (if (a.magic) d.magicDodge else d.dodge).toDouble()) / 100 * (a.dmgMin + a.dmgMax) / 2.0
        if (a.magic) x *= 1 - b.magicCut(d.magicResist.toDouble(), a.level)
        else {
            if (d.parry > 0) x *= 1 - d.parry / 100.0 * b.blockCut
            x *= 1 - b.armorCut(d.armor.toDouble(), a.level, a.pen)
        }
        return x * (1 + a.critChance / 100 * (a.critMult - 1))
    }

    fun perSecond(b: Balance, a: Stats, d: Stats): Double = blow(b, a, d) * 1000 / a.pauseMs.coerceAtLeast(1)

    /** r(A, B) for [a] with [aHp] health against [d] with [dHp]. */
    fun ratio(b: Balance, a: Stats, aHp: Int, d: Stats, dHp: Int): Double =
        perSecond(b, a, d) * aHp.coerceAtLeast(1) / (perSecond(b, d, a) * dHp.coerceAtLeast(1)).coerceAtLeast(1e-9)

    /** Chance (0..1) that [a] wins against [d]. */
    fun chance(b: Balance, a: Stats, aHp: Int, d: Stats, dHp: Int): Double = chance(b, ratio(b, a, aHp, d, dHp))

    fun chance(b: Balance, r: Double): Double {
        val (k, c) = b.powerChance
        return 1 / (1 + exp(-(k * ln(r.coerceAtLeast(1e-9)) + c)))
    }

    /** The power number of [s] with [hp] health at [level]. */
    fun of(b: Balance, s: Stats, hp: Int, level: Int): Int {
        val (ref, refHp) = b.referenceMonster(level.coerceIn(1, b.maxLevel))
        return (ratio(b, s, hp, ref, refHp) * refHp).roundToInt()
    }
}
