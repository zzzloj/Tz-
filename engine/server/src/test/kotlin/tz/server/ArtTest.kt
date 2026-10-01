package tz.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which picture goes with what (content/art rules) and that nothing outside content/art is served. No database. */
class ArtTest {
    private val art = Art(contentDir())

    @Test
    fun picturesByName() {
        assertEquals("npcs/npc-beginner" to false, art.creature("Привратник Уин"))
        assertEquals("npcs/npc-beginner" to true, art.creature("Привратник Уин-зомби"))
        assertEquals("mobs/mob-wolf", art.creature("волк")?.first)
        assertNull(art.creature("Никто Такой Неизвестный"))
        assertEquals("locations/loc-dungeon", art.location("Подвал*1"))
        assertEquals("locations/loc-road", art.location("Совсем незнакомое место"))
    }

    @Test
    fun itemsFindTheirBasePicture() {
        assertEquals("i.w.k.begin.webp", art.itemFile("i.w.k.begin")?.name)
        assertEquals("i.w.k.begin.webp", art.itemFile("i.w.k.begin..5")?.name)
        assertEquals("i.w.k.begin.webp", art.itemFile("i.w.k.begin_12")?.name)
        assertNull(art.itemFile("../../secret"))
        assertNull(art.itemFile("i.nothing.at.all"))
    }

    @Test
    fun onlyPicturesUnderArt() {
        assertNotNull(art.file("npcs", "npc-beginner.webp"))
        assertTrue(art.file("brand", "icon-1024.png") != null || !art.root.resolve("brand/icon-1024.png").isFile)
        assertNull(art.file("npcs", "../npcs.json"))
        assertNull(art.file("..", "README.md"))
        assertNull(art.file("logic", "gifts.json"))
        assertNull(art.file("npcs", "npc-beginner.txt"))
    }
}
