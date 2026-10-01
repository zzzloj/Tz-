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
