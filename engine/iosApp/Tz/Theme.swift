import SwiftUI
import Shared

/// The game theme from the shared tokens (tz.shared.Design, docs/design.md):
/// «Ночь» in the dark system theme, «Пергамент» in the light one.

extension Color {
    /// A color from an ARGB token (0xAARRGGBB).
    init(argb: Int64) {
        let v = UInt32(truncatingIfNeeded: argb)
        self.init(.sRGB,
                  red: Double((v >> 16) & 0xFF) / 255,
                  green: Double((v >> 8) & 0xFF) / 255,
                  blue: Double(v & 0xFF) / 255,
                  opacity: Double((v >> 24) & 0xFF) / 255)
    }
}

struct TzColors {
    let p: Design.Palette
    var dark: Bool { p.dark }
    var background: Color { Color(argb: p.background) }
    var surface: Color { Color(argb: p.surface) }
    var surfaceRaised: Color { Color(argb: p.surfaceRaised) }
    var surfaceSunken: Color { Color(argb: p.surfaceSunken) }
    var border: Color { Color(argb: p.border) }
    var borderSoft: Color { Color(argb: p.borderSoft) }
    var accent: Color { Color(argb: p.accent) }
    var title: Color { Color(argb: p.title) }
    var onPrimary: Color { Color(argb: p.onPrimary) }
    var secondary: Color { Color(argb: p.secondary) }
    var text: Color { Color(argb: p.text) }
    var textMuted: Color { Color(argb: p.textMuted) }
    var textFaint: Color { Color(argb: p.textFaint) }
    var link: Color { Color(argb: p.link) }
    var danger: Color { Color(argb: p.danger) }
    var onDanger: Color { Color(argb: p.onDanger) }
    var barTrack: Color { Color(argb: p.barTrack) }
    var logFight: Color { Color(argb: p.logFight) }
    var logHurt: Color { Color(argb: p.logHurt) }
    var logSay: Color { Color(argb: p.logSay) }
    var logSystem: Color { Color(argb: p.logSystem) }
    var logGain: Color { Color(argb: p.logGain) }
    var primary: LinearGradient { gradient(p.primaryTop, p.primaryBottom) }
    var dangerFill: LinearGradient { gradient(p.dangerTop, p.dangerBottom) }
    var health: LinearGradient { gradient(p.healthTop, p.healthBottom) }
    var mana: LinearGradient { gradient(p.manaTop, p.manaBottom) }
    var exp: LinearGradient { gradient(p.expTop, p.expBottom) }
    var panel: LinearGradient { gradient(p.surfaceRaised, p.surface) }

    private func gradient(_ top: Int64, _ bottom: Int64) -> LinearGradient {
        LinearGradient(colors: [Color(argb: top), Color(argb: bottom)], startPoint: .top, endPoint: .bottom)
    }

    static func of(_ scheme: ColorScheme) -> TzColors {
        TzColors(p: Design.shared.palette(dark: scheme == .dark))
    }
}

/// Text styles with the bundled fonts (Info.plist UIAppFonts); they grow with Dynamic Type.
enum TzType {
    static func font(_ s: Design.TextStyle, relativeTo base: Font.TextStyle = .body) -> Font {
        let file = Design.Fonts.shared.file(family: s.family, weight: s.weight, italic: s.italic)
        let name = Design.Fonts.shared.postScript[file] ?? "Georgia"
        return .custom(name, size: CGFloat(s.size), relativeTo: base)
    }
    static var title: Font { font(Design.TypeTokens.shared.title, relativeTo: .largeTitle) }
    static var heading: Font { font(Design.TypeTokens.shared.heading, relativeTo: .title2) }
    static var name: Font { font(Design.TypeTokens.shared.name, relativeTo: .headline) }
    static var body: Font { font(Design.TypeTokens.shared.body) }
    static var bodyItalic: Font { font(Design.TypeTokens.shared.bodyItalic) }
    static var log: Font { font(Design.TypeTokens.shared.log, relativeTo: .callout) }
    static var small: Font { font(Design.TypeTokens.shared.small, relativeTo: .footnote) }
    static var button: Font { font(Design.TypeTokens.shared.button, relativeTo: .headline) }
    static var label: Font { font(Design.TypeTokens.shared.label, relativeTo: .caption) }
    static var tab: Font { font(Design.TypeTokens.shared.tab, relativeTo: .caption2) }
    static var number: Font { font(Design.TypeTokens.shared.number, relativeTo: .footnote) }
}

private struct TzColorsKey: EnvironmentKey {
    static let defaultValue = TzColors(p: Design.shared.night)
}

extension EnvironmentValues {
    var tz: TzColors {
        get { self[TzColorsKey.self] }
        set { self[TzColorsKey.self] = newValue }
    }
}

/// Puts the palette into the environment and styles the system controls of the current screens.
struct TzTheme: ViewModifier {
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        let c = TzColors.of(scheme)
        content
            .environment(\.tz, c)
            .tint(c.accent)
            .font(TzType.body)
            .foregroundStyle(c.text)
            .scrollContentBackground(.hidden)
            .background(c.background.ignoresSafeArea())
    }
}

extension View {
    func tzTheme() -> some View { modifier(TzTheme()) }

    /// A panel in a bronze frame.
    func tzPanel(_ c: TzColors) -> some View {
        self.background(c.panel)
            .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.L)))
            .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.L)).stroke(c.border, lineWidth: 1))
    }
}

/// A bar: health, mana or experience.
struct TzBar: View {
    let value: Int
    let max: Int
    let fill: LinearGradient
    @Environment(\.tz) private var c

    var body: some View {
        GeometryReader { g in
            let share = max <= 0 ? 0 : min(1, Swift.max(0, CGFloat(value) / CGFloat(max)))
            ZStack(alignment: .leading) {
                c.barTrack
                Rectangle().fill(fill).frame(width: g.size.width * share)
            }
        }
        .frame(height: CGFloat(Design.Size.shared.BAR))
        .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.S)))
        .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.S)).stroke(c.borderSoft, lineWidth: 1))
    }
}
