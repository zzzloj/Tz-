package tz.shared

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** Readability of both palettes: WCAG contrast of text against the background and panels. */
class DesignTest {
    private fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val v = ((argb shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test
    fun textIsReadableInBothThemes() {
        for (p in listOf(Design.night, Design.parchment)) {
            for (bg in listOf(p.background, p.surface, p.surfaceRaised)) {
                // Body text and the journal: AA for normal text (4.5).
                for ((name, fg) in listOf(
                    "text" to p.text, "muted" to p.textMuted, "faint" to p.textFaint, "link" to p.link, "danger" to p.danger,
                    "fight" to p.logFight, "hurt" to p.logHurt, "say" to p.logSay, "system" to p.logSystem, "gain" to p.logGain,
                    "title" to p.title,
                )) {
                    val c = contrast(fg, bg)
                    assertTrue(c >= 4.5, "${p.name}: $name on ${bg.toString(16)} = $c")
                }
            }
            assertTrue(contrast(p.onPrimary, p.primaryBottom) >= 4.5, "${p.name}: button text")
            assertTrue(contrast(p.onDanger, p.dangerBottom) >= 4.5, "${p.name}: danger button text")
        }
    }

    @Test
    fun everyFamilyHasItsFonts() {
        for (f in Design.Family.entries) {
            val file = Design.Fonts.file(f, 700)
            assertTrue(Design.Fonts.postScript.containsKey(file), file)
        }
        assertTrue(Design.Fonts.postScript.containsKey(Design.Fonts.file(Design.Family.SERIF, 400, italic = true)))
    }
}
