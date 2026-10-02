package tz.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class JournalKindTest {
    @Test
    fun linesAreSortedByWhatTheySay() {
        assertEquals(JournalKind.SAY, JournalKind.of("Велемир говорит: есть кто в лес?"))
        assertEquals(JournalKind.SAY, JournalKind.of("[клан] Велемир: сбор"))
        assertEquals(JournalKind.SAY, JournalKind.of("Стражник: Стой!"))
        assertEquals(JournalKind.GAIN, JournalKind.of("Опыт +12"))
        assertEquals(JournalKind.GAIN, JournalKind.of("Велемир: жизнь +5"))
        assertEquals(JournalKind.GAIN, JournalKind.of("Вы получили: эльфийский лук"))
        assertEquals(JournalKind.HURT, JournalKind.of("Вас убил волк. Вы призрак; ваши вещи остались в трупе на 10 минут."))
        assertEquals(JournalKind.FIGHT, JournalKind.of("волк погибает."))
        assertEquals(JournalKind.SYS, JournalKind.of("Вы на охраняемой территории"))
        assertEquals(JournalKind.SYS, JournalKind.of("Недостаточно маны"))
    }
}

class GameSceneTest {
    private val me = CharacterView(1, "Анна", "f", "x", 20, 20, 0, 0, 1, 1, 1, 0)

    @Test
    fun combatListSections() {
        val npcs = listOf(
            NpcView("w1", "волк", 10, 26, fightingYou = true, attackable = true, attacking = "вас", nextBlow = 3, hostile = true),
            NpcView("w2", "волк", 10, 26, fightingYou = true, attackable = true, attacking = "вас", nextBlow = 1, hostile = true),
            NpcView("w3", "белый волк", 30, 30, attackable = true, attacking = "Велемир", hostile = true),
            NpcView("w4", "тёмный волк", 30, 30, attackable = true, hostile = true),
            NpcView("n1", "Привратник Уин", canTalk = true),
        )
        val g = GameScene.groups(GameView(me, LocationView("x", "Лес", 0, npcs = npcs)))
        assertEquals(listOf("w2", "w1"), g.atYou.map { it.id })
        assertEquals(listOf("w3"), g.atOthers.map { it.id })
        assertEquals(listOf("w4"), g.unaware.map { it.id })
        assertEquals(listOf("n1"), g.rest.map { it.id })
        assertEquals(2, GameScene.left(5, 3))
        assertEquals(0, GameScene.left(2, 3))
    }

    @Test
    fun exitCaptions() {
        assertEquals("юг", GameScene.exitCaption("на юг по дороге"))
        assertEquals("север", GameScene.exitCaption("на север "))
        assertEquals("на улицу", GameScene.exitCaption("выйти на улицу"))
        assertEquals("дом на севере", GameScene.exitCaption("дом на севере"))
        assertEquals("на второй этаж", GameScene.exitCaption("на второй этаж"))
        assertEquals("в магазин", GameScene.exitCaption("войти в магазин"))
    }
}
