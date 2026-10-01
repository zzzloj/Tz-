package tz.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tz.shared.Design

/** The game's palette as Compose colors (tokens: tz.shared.Design). */
class TzColors(val p: Design.Palette) {
    val dark = p.dark
    val background = c(p.background)
    val surface = c(p.surface)
    val surfaceRaised = c(p.surfaceRaised)
    val surfaceSunken = c(p.surfaceSunken)
    val border = c(p.border)
    val borderSoft = c(p.borderSoft)
    val accent = c(p.accent)
    val title = c(p.title)
    val onPrimary = c(p.onPrimary)
    val secondary = c(p.secondary)
    val text = c(p.text)
    val textMuted = c(p.textMuted)
    val textFaint = c(p.textFaint)
    val link = c(p.link)
    val danger = c(p.danger)
    val onDanger = c(p.onDanger)
    val barTrack = c(p.barTrack)
    val logFight = c(p.logFight)
    val logHurt = c(p.logHurt)
    val logSay = c(p.logSay)
    val logSystem = c(p.logSystem)
    val logGain = c(p.logGain)
    val shadow = c(p.shadow)
    val primary = Brush.verticalGradient(listOf(c(p.primaryTop), c(p.primaryBottom)))
    val dangerFill = Brush.verticalGradient(listOf(c(p.dangerTop), c(p.dangerBottom)))
    val health = Brush.verticalGradient(listOf(c(p.healthTop), c(p.healthBottom)))
    val mana = Brush.verticalGradient(listOf(c(p.manaTop), c(p.manaBottom)))
    val exp = Brush.verticalGradient(listOf(c(p.expTop), c(p.expBottom)))
    val panel = Brush.verticalGradient(listOf(c(p.surfaceRaised), c(p.surface)))

    companion object {
        fun c(argb: Long) = Color(argb.toInt())
    }
}

/** Text styles of the game (Design.Type) with the bundled fonts. */
class TzType {
    val title = style(Design.TypeTokens.title)
    val heading = style(Design.TypeTokens.heading)
    val name = style(Design.TypeTokens.name)
    val body = style(Design.TypeTokens.body)
    val bodyItalic = style(Design.TypeTokens.bodyItalic)
    val log = style(Design.TypeTokens.log)
    val small = style(Design.TypeTokens.small)
    val button = style(Design.TypeTokens.button)
    val label = style(Design.TypeTokens.label)
    val tab = style(Design.TypeTokens.tab)
    val number = style(Design.TypeTokens.number)

    companion object {
        private val fontRes = mapOf(
            "cormorant_sc_semibold" to R.font.cormorant_sc_semibold, "cormorant_sc_bold" to R.font.cormorant_sc_bold,
            "alegreya_regular" to R.font.alegreya_regular, "alegreya_medium" to R.font.alegreya_medium,
            "alegreya_bold" to R.font.alegreya_bold, "alegreya_italic" to R.font.alegreya_italic,
            "alegreya_sans_sc_medium" to R.font.alegreya_sans_sc_medium, "alegreya_sans_sc_bold" to R.font.alegreya_sans_sc_bold,
            "alegreya_sans_regular" to R.font.alegreya_sans_regular, "alegreya_sans_medium" to R.font.alegreya_sans_medium,
            "alegreya_sans_bold" to R.font.alegreya_sans_bold,
        )

        val families: Map<Design.Family, FontFamily> = Design.Family.entries.associateWith { f ->
            val fonts = Design.Fonts.files.getValue(f).map { (w, file) -> Font(fontRes.getValue(file), FontWeight(w)) }
            val italic = if (f == Design.Family.SERIF) listOf(Font(R.font.alegreya_italic, FontWeight.Normal, FontStyle.Italic)) else emptyList()
            FontFamily(fonts + italic)
        }

        fun style(s: Design.TextStyle) = TextStyle(
            fontFamily = families.getValue(s.family),
            fontWeight = FontWeight(s.weight),
            fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
            fontSize = s.size.sp,
            lineHeight = s.lineHeight.sp,
            letterSpacing = s.tracking.em,
        )
    }
}

val LocalTzColors = staticCompositionLocalOf { TzColors(Design.night) }
val LocalTzType = staticCompositionLocalOf { TzType() }

/** Shortcuts: Tz.colors, Tz.type inside a TzTheme. */
object Tz {
    val colors: TzColors @Composable get() = LocalTzColors.current
    val type: TzType @Composable get() = LocalTzType.current
}

/**
 * The game theme: «Ночь» in the dark system theme, «Пергамент» in the light one.
 * Material components get colors and fonts from the same tokens, so the
 * existing screens take the style before stage 16 redraws them.
 */
@Composable
fun TzTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = TzColors(Design.palette(dark))
    val type = TzType()
    val scheme = if (dark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.background, secondary = colors.link, onSecondary = colors.background,
        background = colors.background, onBackground = colors.text, surface = colors.background, onSurface = colors.text,
        surfaceVariant = colors.surface, onSurfaceVariant = colors.textMuted, surfaceContainer = colors.surface,
        surfaceContainerLow = colors.surface, surfaceContainerHigh = colors.surfaceRaised, surfaceContainerHighest = colors.surfaceRaised,
        outline = colors.border, outlineVariant = colors.borderSoft, error = colors.danger, onError = colors.onDanger,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onPrimary, secondary = colors.link, onSecondary = colors.onPrimary,
        background = colors.background, onBackground = colors.text, surface = colors.background, onSurface = colors.text,
        surfaceVariant = colors.surface, onSurfaceVariant = colors.textMuted, surfaceContainer = colors.surface,
        surfaceContainerLow = colors.surface, surfaceContainerHigh = colors.surfaceRaised, surfaceContainerHighest = colors.surfaceRaised,
        outline = colors.border, outlineVariant = colors.borderSoft, error = colors.danger, onError = colors.onDanger,
    )
    val typography = Typography(
        displaySmall = type.title, headlineMedium = type.title, headlineSmall = type.heading,
        titleLarge = type.heading, titleMedium = type.name, titleSmall = type.name,
        bodyLarge = type.body, bodyMedium = type.log, bodySmall = type.small,
        labelLarge = type.button, labelMedium = type.label, labelSmall = type.tab,
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(Design.Radius.S.dp), small = RoundedCornerShape(Design.Radius.M.dp),
        medium = RoundedCornerShape(Design.Radius.L.dp), large = RoundedCornerShape(Design.Radius.L.dp),
        extraLarge = RoundedCornerShape(Design.Radius.L.dp),
    )
    CompositionLocalProvider(LocalTzColors provides colors, LocalTzType provides type) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}

/** A panel in a bronze frame (the base of cards, sheets and slots). */
fun Modifier.tzPanel(colors: TzColors): Modifier = this
    .clip(RoundedCornerShape(Design.Radius.L.dp))
    .background(colors.panel)
    .border(Design.Size.BORDER.dp, colors.border, RoundedCornerShape(Design.Radius.L.dp))

/** A bar: health, mana or experience. */
@Composable
fun TzBar(value: Int, max: Int, fill: Brush, modifier: Modifier = Modifier) {
    val c = Tz.colors
    val share = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(Design.Size.BAR.dp).clip(RoundedCornerShape(Design.Radius.S.dp))
            .background(c.barTrack).border(Design.Size.BORDER.dp, c.borderSoft, RoundedCornerShape(Design.Radius.S.dp))
    ) {
        Box(Modifier.fillMaxWidth(share).height(Design.Size.BAR.dp).background(fill))
    }
}
