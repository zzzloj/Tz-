package tz.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Combat formulas of the balance of 04.10.2026 (claude/balance.md §5, §13). */
class FormulasTest {
    private val content by lazy { Content.load(contentDir()) }
    private val b by lazy { content.balance }

    /** Dice that return the given numbers in order and fail if asked for more. */
    private fun dice(vararg values: Int): Dice {
        val queue = ArrayDeque(values.toList())
        return Dice { a, b ->
            val v = queue.removeFirst()
            check(v in a..b) { "scripted $v outside $a..$b" }
            v
        }
    }

    private fun stats(level: Int, equipped: List<String>, str: Int = 2, dex: Int = 2, int: Int = 2) =
        Formulas.player(b, Skills.of(str, dex, int), level, equipped, { content.items[it] }, { content.itemBalance(it) }, content.sets)

    private fun fighter(hit: Int = 50, min: Int = 5, max: Int = 5, verb: String = "мечом") =
        Stats(hit, min, max, 1000, false, 0, 0, 0, 0, 0, verb, 0, "")

    private val target = Stats(0, 0, 0, 1000, false, 0, 0, 0, 0, 0, "", 0, "")

    @Test
    fun newbieWithKnife() {
        val s = stats(1, listOf("i.w.k.begin"))
        // Accuracy 2·dex + 3·weapon skill + level; a knife pauses 1 s less 2 % per dexterity point.
        assertEquals(5, s.hit)
        assertEquals(960L, s.pauseMs)
        assertEquals("ножом", s.verb)
        assertEquals(25, s.hpMax)
        assertEquals(7.0, s.critChance)
        assertTrue(s.pen > 1.0, "a knife meets more armour")
    }

    @Test
    fun barehanded() {
        val s = stats(1, emptyList())
        assertEquals("кулаками", s.verb)
        assertEquals(1 to 3, s.dmgMin to s.dmgMax)
        assertEquals(3.0, s.critChance)
    }

    @Test
    fun blowOutcomes() {
        // Hit chance 60 + 50 − 0, at most 95: a roll of 95 misses, 94 hits.
        assertEquals(Formulas.Outcome.MISS, Formulas.attack(b, fighter(), target, dice(95)).outcome)
        assertEquals(Formulas.Hit(Formulas.Outcome.HIT, 5), Formulas.attack(b, fighter(), target, dice(94, 5, 9999)))
        // Armour 100 against a level-1 blow stops 100 / (100 + 60) of it.
        assertEquals(2, Formulas.attack(b, fighter(), target.copy(armor = 100), dice(10, 5, 9999)).damage)
        // A crit multiplies by 1.5.
        assertEquals(Formulas.Hit(Formulas.Outcome.HIT, 8, crit = true), Formulas.attack(b, fighter(min = 5, max = 5), target, dice(10, 5, 0)))
        // A shield blocks half of the blow.
        assertEquals(Formulas.Hit(Formulas.Outcome.HIT, 2, shield = 3), Formulas.attack(b, fighter(), target.copy(parry = 30), dice(10, 5, 10, 9999)))
        // Magic with zero accuracy does nothing (a stance stopped it).
        assertEquals(Formulas.Outcome.FIZZLED, Formulas.attack(b, fighter(hit = 0, verb = "магией"), target, dice()).outcome)
    }

    @Test
    fun setsAndGems() {
        val plain = stats(46, listOf("i.a.h.ms", "i.a.b.sborn", "i.a.p.ms", "i.a.l.ms"), str = 10, dex = 10)
        val full = stats(46, listOf("i.a.h.ms", "i.a.b.sborn", "i.a.p.ms", "i.a.l.ms", "i.w.s.master"), str = 10, dex = 10)
        assertTrue(full.hpMax > plain.hpMax, "the whole adamant set adds health")
        // Agate: accuracy +5, once however many things carry it.
        val agate = stats(5, listOf("i.w.k.begin_Мастер..ag", "i.a.b.leather_Мастер..ag"))
        assertEquals(stats(5, listOf("i.w.k.begin", "i.a.b.leather")).hit + 5, agate.hit)
        // Rose quartz in the weapon sets fire to the target; in the necklace guards against it.
        assertEquals("ignite", stats(1, listOf("i.w.k.begin_Мастер..fa")).ailment)
    }

    @Test
    fun blueTopazDoesNotSlowTheWeapon() {
        // Owner 04.10.2026: the topaz only helps necromancy; its old data added 10 s to the pause.
        // Gems go only into named items, made by players (id with "_maker").
        assertEquals(stats(3, listOf("i.w.k.begin")).pauseMs, stats(3, listOf("i.w.k.begin_Тест..gt")).pauseMs)
    }
}
