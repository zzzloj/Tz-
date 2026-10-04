package tz.server

import kotlin.test.Test
import kotlin.test.assertEquals

class FormulasTest {
    private val content by lazy { Content.load(contentDir()) }

    /** Dice that return the given numbers in order and fail if asked for more. */
    private fun dice(vararg values: Int): Dice {
        val queue = ArrayDeque(values.toList())
        return Dice { a, b ->
            val v = queue.removeFirst()
            check(v in a..b) { "scripted $v outside $a..$b" }
            v
        }
    }

    private fun fighter(hit: Int = 50, min: Int = 5, max: Int = 5, verb: String = "мечом", ranged: Boolean = false) =
        Stats(hit, min, max, 5, ranged, 0, 0, 0, 0, 0, 0, 0, verb, 0, "")

    private val target = Stats(0, 0, 0, 5, false, 0, 0, 0, 0, 0, 0, 0, "", 0, "")

    @Test
    fun newbieWithKnife() {
        val s = Formulas.player(Skills.of(1, 1, 1), listOf("i.w.k.begin"), { content.items[it] })
        // Same as the old engine prints for a new character: 10|1|3|3|0|0|1|…|ножом
        assertEquals(listOf(10, 1, 3, 3, 0, 1), listOf(s.hit, s.dmgMin, s.dmgMax, s.delay, s.armor, s.dodge))
        assertEquals("ножом", s.verb)
    }

    @Test
    fun barehanded() {
        val s = Formulas.player(Skills.of(1, 1, 1), emptyList(), { content.items[it] })
        assertEquals("кулаками", s.verb)
        assertEquals(30, s.hit)
        assertEquals(0 to 2, s.dmgMin to s.dmgMax)
    }

    @Test
    fun blowOutcomes() {
        assertEquals(Formulas.Outcome.MISS, Formulas.attack(fighter(), target, dice(51)).outcome)
        assertEquals(Formulas.Hit(Formulas.Outcome.HIT, 5), Formulas.attack(fighter(), target, dice(50, 5, 1, 50)))
        // Dodge: the defender's dodge roll is at or under its dodge value.
        assertEquals(Formulas.Outcome.DODGED, Formulas.attack(fighter(), target.copy(dodge = 20), dice(10, 5, 20)).outcome)
        // Armour takes rand(0, armor) off, crit doubles what is left.
        assertEquals(Formulas.Hit(Formulas.Outcome.HIT, 4, crit = true), Formulas.attack(fighter(), target.copy(armor = 3), dice(10, 5, 50, 3, 1)))
        // Shield: parry roll succeeds, shield armour is subtracted.
        val shielded = target.copy(parry = 30, shieldArmor = 2)
        assertEquals(Formulas.Hit(Formulas.Outcome.HIT, 3, shield = 2), Formulas.attack(fighter(), shielded, dice(10, 5, 50, 30, 50)))
        // Magic with zero hit chance does nothing.
        assertEquals(Formulas.Outcome.FIZZLED, Formulas.attack(fighter(hit = 0, verb = "магией"), target, dice()).outcome)
    }

    @Test
    fun regenerationAndExperience() {
        assertEquals(0, Formulas.regen(30))
        assertEquals(1, Formulas.regen(31))
        assertEquals(2, Formulas.regen(60))
        // Attributes 1/1/1 and 2 free points: over 50 experience gives a point.
        assertEquals(50, Formulas.expThreshold(Skills.of(1, 1, 1, exp = 40, points = 2)))
    }

    @Test
    fun blueTopazDoesNotSlowTheWeapon() {
        // Owner 04.10.2026: the topaz only helps necromancy (Pets.raise); its old data added 10 s to the pause.
        val plain = Formulas.player(Skills.of(3, 3, 3), listOf("i.w.k.begin"), { content.items[it] })
        val withGem = Formulas.player(Skills.of(3, 3, 3), listOf("i.w.k.begin..gt"), { content.items[it] })
        assertEquals(plain.delay, withGem.delay)
    }
}
