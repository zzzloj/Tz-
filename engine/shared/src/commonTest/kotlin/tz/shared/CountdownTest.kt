package tz.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Countdowns in tenths, crafts and the teacher's card (balance of 04.10.2026). */
class CountdownTest {
    private val ch = CharacterView(1, "Герой", "m", "x", 25, 25, 20, 20, 2, 2, 2, 4)
    private fun game(restMs: Long = 0, restSeconds: Int = 0) =
        GameView(ch, LocationView("x", "Поляна", 0), restSeconds = restSeconds, restMs = restMs)

    @Test
    fun countdownsInTenths() {
        assertEquals("1,3", GameScene.tenths(1250))
        assertEquals("1", GameScene.tenths(1000))
        assertEquals("0,1", GameScene.tenths(1))
        assertEquals("0", GameScene.tenths(0))
        assertEquals(700L, GameScene.restLeftMs(game(restMs = 1200), 500))
        assertEquals(0L, GameScene.restLeftMs(game(restMs = 1200), 1500))
        // An older server sends whole seconds.
        assertEquals(1500L, GameScene.restLeftMs(game(restSeconds = 2), 500))
        val wolf = NpcView("w", "волк", nextBlow = 2, nextBlowMs = 1400)
        assertEquals(1100L, GameScene.blowLeftMs(wolf, 300))
        assertNull(GameScene.blowLeftMs(NpcView("r", "крыса"), 300))
    }

    @Test
    fun craftsAndTeachers() {
        assertEquals("практика 12 из 40", GameScene.craftLine(CraftSkillView("smith", "Кузнец", 0, 10, 12, 40)))
        assertEquals("предел", GameScene.craftLine(CraftSkillView("smith", "Кузнец", 10, 10, 0, 0)))
        assertEquals("Интеллект 2 → 3 (из 10) · бесплатно · 1 очко",
            GameScene.teachLine(TeachOfferView("intnow", "int", "Интеллект", 2, 10, 0, 1, free = true)))
        assertEquals("Интеллект 6 → 7 (из 10) · 735 монет · 1 очко — нет очков обучения",
            GameScene.teachLine(TeachOfferView("intnow", "int", "Интеллект", 6, 10, 735, 1, note = "нет очков обучения")))
        assertEquals("Кузнец 2 (учитель — до 2) · 135 монет — дальше только практикой",
            GameScene.teachLine(TeachOfferView("k2now", "smith", "Кузнец", 2, 2, 135, 0, note = "дальше только практикой")))
        assertEquals(listOf("Атрибуты", "Бой", "Магия", "Выживание", "Воровство", "Звери"), GameScene.SKILL_GROUPS.map { it.first })
    }

    @Test
    fun powerAndChance() {
        val wolf = NpcView("n.c.wolf", "волк", 40, 40, attackable = true, level = 16, power = 180, winChance = 74)
        assertEquals("мощь 180 · шанс 74 %", GameScene.oddsLine(wolf))
        assertEquals(0, GameScene.oddsTone(wolf))
        assertEquals(1, GameScene.oddsTone(wolf.copy(winChance = 45)))
        assertEquals(2, GameScene.oddsTone(wolf.copy(winChance = 12)))
        assertEquals(null, GameScene.oddsLine(wolf.copy(mine = true)))
        assertEquals("мощь 180", GameScene.oddsLine(wolf.copy(winChance = null)))
    }
}
