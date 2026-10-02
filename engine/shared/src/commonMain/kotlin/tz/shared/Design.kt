package tz.shared

/**
 * Design tokens of the game (stage 15, docs/design.md): one source for the
 * Compose theme (androidApp Theme.kt) and the SwiftUI theme (iosApp Theme.swift).
 *
 * Style: dark fantasy — night, bronze and gold, blood and dusk; text first.
 * The dark theme «Ночь» is the main one; the light theme «Пергамент» is the
 * same world by daylight: ink on parchment, bronze frames.
 *
 * Colors are ARGB (0xAARRGGBB). Sizes are dp / pt.
 */
object Design {
    class Palette(
        val name: String,
        val dark: Boolean,
        /** Screen background. */
        val background: Long,
        /** Panels, sheets, cards. */
        val surface: Long,
        /** Top of a panel's gradient, a raised card. */
        val surfaceRaised: Long,
        /** Pressed or selected background. */
        val surfaceSunken: Long,
        /** Frames of panels and slots. */
        val border: Long,
        /** Dividers and empty slots. */
        val borderSoft: Long,
        /** Gold: ornaments, the active tab, selection. */
        val accent: Long,
        /** Bright gold: titles, names of places, gains. */
        val title: Long,
        /** Main button: gradient top and bottom, its text. */
        val primaryTop: Long,
        val primaryBottom: Long,
        val onPrimary: Long,
        /** Secondary button background. */
        val secondary: Long,
        val text: Long,
        /** Second line, captions. */
        val textMuted: Long,
        /** Inactive tabs, hints. */
        val textFaint: Long,
        val link: Long,
        /** Attack, crime, warnings. */
        val danger: Long,
        val dangerTop: Long,
        val dangerBottom: Long,
        val onDanger: Long,
        /** Bars: health, mana, experience (gradient top → bottom) and their track. */
        val healthTop: Long,
        val healthBottom: Long,
        val manaTop: Long,
        val manaBottom: Long,
        val expTop: Long,
        val expBottom: Long,
        val barTrack: Long,
        /** Journal lines by kind. */
        val logFight: Long,
        val logHurt: Long,
        val logSay: Long,
        val logSystem: Long,
        val logGain: Long,
        /** A shadow under panels and the tab bar. */
        val shadow: Long,
    )

    val night = Palette(
        name = "Ночь", dark = true,
        background = 0xFF0B0907, surface = 0xFF15110E, surfaceRaised = 0xFF1C1612, surfaceSunken = 0xFF0F0C09,
        border = 0xFF5A4430, borderSoft = 0xFF3A2C1F,
        accent = 0xFFC9A45C, title = 0xFFE9CF8E,
        primaryTop = 0xFF6E4F22, primaryBottom = 0xFF3F2B12, onPrimary = 0xFFF5E6C0, secondary = 0xFF1F1813,
        text = 0xFFE8DCC2, textMuted = 0xFFA8987C, textFaint = 0xFF8F7F65, link = 0xFFD8B56A,
        danger = 0xFFE0604F, dangerTop = 0xFF7D1C14, dangerBottom = 0xFF4A0F0A, onDanger = 0xFFFFE3D6,
        healthTop = 0xFFD0412F, healthBottom = 0xFF7D1A12, manaTop = 0xFF4F86D6, manaBottom = 0xFF1F4580,
        expTop = 0xFFE0BD6A, expBottom = 0xFF8A6A2A, barTrack = 0xFF0B0907,
        logFight = 0xFFFF9A86, logHurt = 0xFFFF6B57, logSay = 0xFFCFE0FF, logSystem = 0xFFA8987C, logGain = 0xFFE9CF8E,
        shadow = 0x99000000,
    )

    val parchment = Palette(
        name = "Пергамент", dark = false,
        background = 0xFFECE0C6, surface = 0xFFF6EEDB, surfaceRaised = 0xFFFBF5E8, surfaceSunken = 0xFFE2D3B3,
        border = 0xFFA8834A, borderSoft = 0xFFCDB68A,
        accent = 0xFF7D5A1C, title = 0xFF5A3D14,
        primaryTop = 0xFF8A6428, primaryBottom = 0xFF5C3F14, onPrimary = 0xFFFBF1D8, secondary = 0xFFEFE3C8,
        text = 0xFF2A1D12, textMuted = 0xFF5F4C34, textFaint = 0xFF6F5A3E, link = 0xFF7A4F12,
        danger = 0xFF9B2A1E, dangerTop = 0xFFA8321F, dangerBottom = 0xFF6E1A10, onDanger = 0xFFFFEDE4,
        healthTop = 0xFFC23A28, healthBottom = 0xFF7D1A12, manaTop = 0xFF4A7CC8, manaBottom = 0xFF234A86,
        expTop = 0xFFC9A24E, expBottom = 0xFF7E5E20, barTrack = 0xFFD8C7A2,
        logFight = 0xFFA3361F, logHurt = 0xFF8F1D12, logSay = 0xFF24497E, logSystem = 0xFF5F4C34, logGain = 0xFF6E4F12,
        shadow = 0x40503A1CL,
    )

    fun palette(dark: Boolean) = if (dark) night else parchment

    /** Spacing scale. */
    object Space {
        const val XXS = 2
        const val XS = 4
        const val S = 8
        const val M = 12
        const val L = 16
        const val XL = 24
        const val XXL = 32
        /** Side margin of a screen. */
        const val SCREEN = 16
    }

    /** Corners: frames of old weapons are almost square. */
    object Radius {
        const val S = 2
        const val M = 3
        const val L = 4
    }

    object Size {
        /** The smallest thing a finger presses (Apple HIG 44 pt, Material 48 dp — 44 with spacing around). */
        const val TOUCH = 44
        const val TAB_BAR = 62
        const val BAR = 10
        const val BAR_THIN = 6
        const val MEDAL = 40
        const val PORTRAIT = 96
        const val PORTRAIT_SMALL = 48
        const val ITEM_ICON = 44
        const val ACTION_ICON = 22
        const val TAB_ICON = 24
        const val BORDER = 1
        /** Gold corners of a panel. */
        const val ORNAMENT = 10
        /** A location picture is 16:9. */
        const val SCENE_RATIO = 16f / 9f
    }

    enum class Family {
        /** Cormorant SC: names of places, people, titles. */
        DISPLAY,
        /** Alegreya: descriptions, dialogs, the journal. */
        SERIF,
        /** Alegreya Sans SC: buttons, tabs, captions. */
        CAPS,
        /** Alegreya Sans: numbers. */
        SANS,
    }

    class TextStyle(val family: Family, val size: Int, val weight: Int, val lineHeight: Int, val tracking: Float = 0f, val italic: Boolean = false)

    object TypeTokens {
        val title = TextStyle(Family.DISPLAY, 30, 700, 34, 0.04f)
        val heading = TextStyle(Family.DISPLAY, 22, 700, 26, 0.04f)
        val name = TextStyle(Family.DISPLAY, 18, 700, 22, 0.03f)
        val body = TextStyle(Family.SERIF, 16, 400, 22)
        val bodyItalic = TextStyle(Family.SERIF, 16, 400, 22, italic = true)
        val log = TextStyle(Family.SERIF, 14, 400, 19)
        val small = TextStyle(Family.SERIF, 13, 400, 17)
        val button = TextStyle(Family.CAPS, 15, 700, 18, 0.08f)
        val label = TextStyle(Family.CAPS, 12, 700, 15, 0.08f)
        val tab = TextStyle(Family.CAPS, 11, 700, 13, 0.06f)
        val number = TextStyle(Family.SANS, 13, 500, 16)
    }

    /** Font files bundled with the apps (SIL Open Font License), by family and weight. */
    object Fonts {
        val files: Map<Family, Map<Int, String>> = mapOf(
            Family.DISPLAY to mapOf(600 to "cormorant_sc_semibold", 700 to "cormorant_sc_bold"),
            Family.SERIF to mapOf(400 to "alegreya_regular", 500 to "alegreya_medium", 700 to "alegreya_bold"),
            Family.CAPS to mapOf(500 to "alegreya_sans_sc_medium", 700 to "alegreya_sans_sc_bold"),
            Family.SANS to mapOf(400 to "alegreya_sans_regular", 500 to "alegreya_sans_medium", 700 to "alegreya_sans_bold"),
        )
        const val SERIF_ITALIC = "alegreya_italic"

        /** PostScript names, how iOS finds a bundled font. */
        val postScript: Map<String, String> = mapOf(
            "cormorant_sc_semibold" to "CormorantSC-SemiBold", "cormorant_sc_bold" to "CormorantSC-Bold",
            "alegreya_regular" to "AlegreyaRoman-Regular", "alegreya_medium" to "AlegreyaRoman-Medium",
            "alegreya_bold" to "AlegreyaRoman-Bold", "alegreya_italic" to "AlegreyaItalic-Italic",
            "alegreya_sans_sc_medium" to "AlegreyaSansSC-Medium", "alegreya_sans_sc_bold" to "AlegreyaSansSC-Bold",
            "alegreya_sans_regular" to "AlegreyaSans-Regular", "alegreya_sans_medium" to "AlegreyaSans-Medium",
            "alegreya_sans_bold" to "AlegreyaSans-Bold",
        )

        /** The bundled file closest to a weight. */
        fun file(family: Family, weight: Int, italic: Boolean = false): String {
            if (italic && family == Family.SERIF) return SERIF_ITALIC
            val byWeight = files.getValue(family)
            return byWeight[weight] ?: byWeight.minBy { kotlin.math.abs(it.key - weight) }.value
        }
    }

    /** Action icons (content/art/icons/ui, docs/design.md): key → what it means. */
    val ICONS: Map<String, String> = linkedMapOf(
        "north" to "на север", "east" to "на восток", "south" to "на юг", "west" to "на запад",
        "up" to "вверх", "down" to "вниз", "enter" to "войти", "gallop" to "галопом",
        "attack" to "атаковать", "flee" to "бежать", "talk" to "говорить", "look" to "осмотреть",
        "take" to "взять", "drop" to "бросить", "use" to "использовать", "equip" to "надеть",
        "trade" to "торговать", "give" to "передать", "mail" to "почта", "clan" to "клан",
        "tab-location" to "вкладка «Локация»", "tab-hero" to "вкладка «Персонаж»", "tab-bag" to "вкладка «Сумка»",
        "tab-social" to "вкладка «Общение»", "tab-world" to "вкладка «Мир»",
        "health" to "жизнь", "mana" to "мана", "rest" to "отдых", "gold" to "монеты", "settings" to "настройки",
    )
}
