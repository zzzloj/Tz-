package tz.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The balance formulas against the simulator that calibrated them (bal/model.py, 04.10.2026). */
class BalanceTest {
    private val b = Content.load(contentDir()).balance

    @Test
    fun levelsAndPoints() {
        assertEquals(25L, b.expToNext(1))
        assertEquals(915_102L, b.expToNext(49))
        assertEquals(12_580_668L, b.expForLevel(50))
        assertEquals(1, b.level(0))
        assertEquals(2, b.level(25))
        assertEquals(49, b.level(b.expForLevel(50) - 1))
        assertEquals(50, b.level(b.expForLevel(50)))
        // Experience keeps counting past the top level, the level does not.
        assertEquals(50, b.level(b.expForLevel(50) * 3))
        assertEquals(6, b.totalPoints(2))
        assertEquals(24, b.totalPoints(10))
        assertEquals(112, b.totalPoints(50))
        assertEquals(15 * 9, b.teacherPrice(3))
    }

    @Test
    fun heroFormulas() {
        assertEquals(212, b.hpMax(10, 50))
        assertEquals(31, b.hpMax(2, 3))
        assertEquals(1.1 * 0.8, b.pause("sword", 10), 1e-9)
        assertEquals(1.0, b.pause("knife", 0), 1e-9)
        assertEquals(1500L, b.pauseMillis("heavy", 0))
        assertEquals(0.339, b.armorCut(154.0, 25), 1e-3)
        assertTrue(b.armorCut(154.0, 25, b.penetration("heavy")) < b.armorCut(154.0, 25) && b.armorCut(154.0, 25) < b.armorCut(154.0, 25, b.penetration("knife")))
        assertEquals(95.0, b.hitChance(200.0, 0.0))
        assertEquals(15.0, b.hitChance(0.0, 200.0))
        assertEquals(7.0, b.critBase("knife"))
        assertEquals(5.0, b.critBase("sword"))
        assertEquals(1.5, b.critMultiplier)
    }

    @Test
    fun monstersAndExperience() {
        // Calibrated 05.10 on the real items a warrior of each level may wear (tools/balance/calib_all.py).
        assertEquals(25.159, b.monsterHp(1), 1e-3)
        assertEquals(262.05, b.monsterHp(25), 1e-2)
        assertEquals(1109L, b.monsterExp(30))
        assertEquals(210, b.monsterGold(30))
        assertEquals(1.5, b.expByGap(20, 30), 1e-9)
        assertEquals(1.0, b.expByGap(30, 26), 1e-9)
        assertEquals(0.1, b.expByGap(30, 20), 1e-9)
    }

    @Test
    fun weaponClasses() {
        assertEquals("knife", Balance.weaponClass("i.w.k.begin", "нож"))
        assertEquals("heavy", Balance.weaponClass("i.w.s.dvuruch", "двуручный меч"))
        assertEquals("crossbow", Balance.weaponClass("i.w.r.c.gazov2", "газовый самострел"))
        assertEquals("bow", Balance.weaponClass("i.w.r.b.master", "лук мастера"))
        assertEquals("rapier", Balance.weaponClass("i.w.s.shpaga", "шпага"))
        assertEquals("thrown", Balance.weaponClass("i.w.r.drotik", "дротик"))
        assertEquals("hand", Balance.weaponClass("", "кулаками"))
        assertTrue(b.crafts.containsAll(setOf("smith", "mine", "alchemy")) && "necro" !in b.crafts)
    }
}
