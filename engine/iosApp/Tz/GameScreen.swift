import SwiftUI
import Shared

/// The five tabs of the game screen and their sub-tabs (docs/design.md, «Раскладка экрана»).
enum GameTab: Int, CaseIterable {
    case place, hero, bag, social, world

    var title: String {
        switch self {
        case .place: return "Локация"
        case .hero: return "Персонаж"
        case .bag: return "Сумка"
        case .social: return "Общение"
        case .world: return "Мир"
        }
    }
    var icon: String {
        switch self {
        case .place: return "tab-location"
        case .hero: return "tab-hero"
        case .bag: return "tab-bag"
        case .social: return "tab-social"
        case .world: return "tab-world"
        }
    }
    var subs: [String] {
        switch self {
        case .hero: return ["Обзор", "Навыки", "Приёмы и магия"]
        case .social: return ["Здесь", "Почта", "Клан", "Онлайн", "Форум"]
        case .world: return ["Карта", "Замки", "События"]
        default: return []
        }
    }
}

/// Combat buttons and belt, and the site pages reachable from the tabs.
struct LayoutActions {
    let strike: (AbilityView, String) -> Void
    let setSlot: (Int, String) -> Void
    let setBelt: (Int, String) -> Void
    let useBelt: (Int) -> Void
    let openForum: () -> Void
    let openNews: () -> Void
    let openPages: () -> Void
    let openAccount: () -> Void
    let openAdmin: (() -> Void)?
    let openEvents: () -> Void
    let openTopic: (ForumTopic) -> Void
}

/// Plain values the screen needs from the shared Kotlin code, computed on the Swift side.
enum SceneData {
    static func slots(_ g: GameView) -> [AbilityView?] {
        g.slots.map { id in id.isEmpty ? nil : g.abilities.first { $0.id == id } }
    }
    static func belt(_ g: GameView) -> [(String, InventoryItemView?)] {
        g.belt.map { id in (id, id.isEmpty ? nil : g.inventory.first { $0.id == id }) }
    }
    static func journal(_ g: GameView, _ n: Int, hereOnly: Bool = false) -> [(String, String)] {
        let count = hereOnly && g.journalHere >= 0 ? min(n, Int(g.journalHere)) : n
        let lines = Array(g.journal.suffix(count))
        let kinds = Array(g.journalKinds.suffix(count))
        return lines.enumerated().map { i, l in (l, kinds.count == lines.count ? kinds[i] : JournalKind.shared.of(line: l)) }
    }
    static func thief(_ c: CharacterView) -> Bool {
        (c.skills["steal"]?.intValue ?? 0) > 0 || (c.skills["steallook"]?.intValue ?? 0) > 0
    }
}

/// Turns a picture path into a full address.
private struct ArtUrlKey: EnvironmentKey {
    static let defaultValue: ((String) -> String)? = nil
}

/// Seconds since the shown view came: countdowns run on from it.
private struct ElapsedKey: EnvironmentKey {
    static let defaultValue: Int64 = 0
}

/// The same in milliseconds, ticking by tenths while a blow or a pause is near.
private struct ElapsedMsKey: EnvironmentKey {
    static let defaultValue: Int64 = 0
}

extension EnvironmentValues {
    var elapsed: Int64 {
        get { self[ElapsedKey.self] }
        set { self[ElapsedKey.self] = newValue }
    }
    var elapsedMs: Int64 {
        get { self[ElapsedMsKey.self] }
        set { self[ElapsedMsKey.self] = newValue }
    }
    var artUrl: ((String) -> String)? {
        get { self[ArtUrlKey.self] }
        set { self[ArtUrlKey.self] = newValue }
    }
}

/// A picture of the game, bundled in the app (content/art as the «art» folder), with a quiet placeholder.
enum BundledArt {
    static let root = Bundle.main.resourceURL?.appendingPathComponent("art")
    static let items: Set<String> = {
        guard let dir = root?.appendingPathComponent("items"), let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return [] }
        return Set(names)
    }()
    static let cache = NSCache<NSString, UIImage>()

    static func image(_ path: String) -> UIImage? {
        if let hit = cache.object(forKey: path as NSString) { return hit }
        guard let file = GameScene.shared.bundledArt(path: path, hasItem: { KotlinBoolean(bool: items.contains($0)) }),
              let url = root?.appendingPathComponent(file), let img = UIImage(contentsOfFile: url.path) else { return nil }
        cache.setObject(img, forKey: path as NSString)
        return img
    }
}

struct ArtImage: View {
    let path: String?
    var dim = false
    @Environment(\.tz) private var c

    var body: some View {
        ZStack {
            c.surfaceSunken
            if let p = path, let img = BundledArt.image(p) {
                Image(uiImage: img).resizable().scaledToFill().opacity(dim ? 0.45 : 1)
            }
        }
        .clipped()
    }
}

struct TzIcon: View {
    let key: String
    var size: CGFloat = CGFloat(Design.Size.shared.ACTION_ICON)
    var color: Color? = nil
    @Environment(\.tz) private var c

    var body: some View {
        Image(key).renderingMode(.template).resizable().scaledToFit()
            .frame(width: size, height: size)
            .foregroundStyle(color ?? c.text)
            .accessibilityLabel(Design.shared.ICONS[key] ?? key)
    }
}

/// A square button of a row: an icon or a spell's picture; pale while resting.
struct ActionButton: View {
    let label: String
    let enabled: Bool
    let action: () -> Void
    var icon: String? = nil
    var art: String? = nil
    var danger = false
    var badge: String? = nil
    @Environment(\.tz) private var c

    var body: some View {
        let side = CGFloat(Design.Size.shared.TOUCH)
        let shape = RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M))
        Button(action: action) {
            ZStack(alignment: .bottomTrailing) {
                ZStack {
                    if danger { shape.fill(c.dangerFill) } else { shape.fill(c.panel) }
                    if let a = art { ArtImage(path: a).padding(3) } else if let i = icon { TzIcon(key: i, color: danger ? c.onDanger : c.text) }
                }
                .frame(width: side, height: side)
                .clipShape(shape)
                .overlay(shape.stroke(danger ? c.danger : c.border, lineWidth: 1))
                if let b = badge { Text(b).font(TzType.number).foregroundStyle(c.title).padding(2) }
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label)
    }
}

struct GameScreen: View {
    let game: GameView
    let busy: Bool
    let onGo: (ExitView) -> Void
    let onTake: (GroundItemView) -> Void
    let onDrop: (InventoryItemView) -> Void
    let onToggleEquip: (InventoryItemView) -> Void
    let onAttack: (NpcView) -> Void
    let onLoot: (CorpseView, GroundItemView) -> Void
    let onButcher: (CorpseView) -> Void
    let onResurrect: () -> Void
    let onTalk: (NpcView) -> Void
    let onAnswer: (DialogOption) -> Void
    let onCloseDialog: () -> Void
    let pending: InventoryItemView?
    let more: MoreActions
    let pendingAbility: AbilityView?
    let social: SocialActions
    let layout: LayoutActions
    let mail: MessagesView?
    let clanInfo: ClanView?
    let worldInfo: WorldView?
    let mapView: MapView?
    let page: AnyView?
    let onClosePage: () -> Void
    let chronicle: ChronicleView?
    let news: ForumView?
    let info: String?
    let error: String?
    let onRefresh: () -> Void
    let onSignOut: () -> Void
    @State private var tab = GameTab.place
    @State private var sub = 0
    @State private var elapsed: Int64 = 0
    @State private var elapsedMs: Int64 = 0
    @Environment(\.tz) private var c

    /** The nearest pause or blow, ms (up to 10 s): it counts down by tenths. */
    private var nearestMs: Int64 {
        let blows = game.location.npcs.compactMap { GameScene.shared.blowLeftMs(npc: $0, elapsedMs: 0)?.int64Value }.max() ?? 0
        return min(max(GameScene.shared.restLeftMs(game: game, elapsedMs: 0), blows), 10_000)
    }

    /** The longest countdown in the view: rest, an enemy's next blow, a cooldown under 10 minutes. */
    private var longest: Int {
        let blows = game.location.npcs.compactMap { $0.nextBlow?.intValue }.max() ?? 0
        let ready = game.abilities.map { Int($0.readyIn) }.filter { $0 <= 600 }.max() ?? 0
        return max(Int(game.restSeconds), blows, ready)
    }

    private func select(_ i: Int) {
        sub = i
        switch (tab, i) {
        case (.social, 1) where mail == nil: social.openMail()
        case (.social, 2) where clanInfo == nil: social.openClan()
        case (.social, 3) where worldInfo == nil: more.openWorld()
        case (.world, 0) where mapView == nil: more.openMap()
        case (.world, 1) where worldInfo == nil: more.openWorld()
        case (.world, 2) where chronicle == nil: layout.openEvents()
        case (.social, 4): layout.openForum()
        default: break
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            Header(game: game, onRefresh: onRefresh)
            ZStack(alignment: .bottom) {
                ScrollView {
                    VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.S)) {
                        if let p = page { p }
                        else if !tab.subs.isEmpty { SubTabs(titles: tab.subs, selected: sub, onSelect: select) }
                        if page == nil {
                        switch tab {
                        case .place:
                            PlaceTab(game: game, busy: busy, onTake: onTake, onAttack: onAttack, onLoot: onLoot, onButcher: onButcher,
                                     onResurrect: onResurrect, onTalk: onTalk, more: more, social: social, layout: layout)
                        case .hero:
                            HeroTab(game: game, busy: busy, sub: sub, more: more, layout: layout, onSignOut: onSignOut)
                        case .bag:
                            BagTab(game: game, busy: busy, onDrop: onDrop, onToggleEquip: onToggleEquip, more: more, layout: layout)
                        case .social:
                            SocialTab(game: game, busy: busy, sub: sub, social: social, more: more, layout: layout, mail: mail, clanInfo: clanInfo, worldInfo: worldInfo)
                        case .world:
                            WorldTab(game: game, busy: busy, sub: sub, social: social, more: more, layout: layout, worldInfo: worldInfo, mapView: mapView, chronicle: chronicle, news: news)
                        }
                        }
                        if let i = info { Text(i).foregroundStyle(c.accent) }
                        if let e = error { Text(e).foregroundStyle(c.danger) }
                    }
                    .padding(.horizontal, CGFloat(Design.Space.shared.SCREEN))
                    .padding(.vertical, CGFloat(Design.Space.shared.S))
                }
                Sheets(game: game, busy: busy, onAnswer: onAnswer, onCloseDialog: onCloseDialog, pending: pending,
                       pendingAbility: pendingAbility, more: more, social: social)
            }
            if tab == .place || tab == .bag { Belt(game: game, busy: busy, layout: layout) { tab = .bag; sub = 0 } }
            Exits(exits: game.location.exits, busy: busy, onGo: onGo, onGallop: more.gallop)
            TabBar(tab: tab, unread: Int(game.unread + game.forumReplies)) { t in
                if page != nil { onClosePage() }
                tab = t; sub = 0
                if t == .world && mapView == nil { more.openMap() }
            }
        }
        .grayscale(game.character.ghost ? 0.85 : 0)
        .environment(\.elapsed, elapsed)
        .environment(\.elapsedMs, elapsedMs)
        .background(c.background.ignoresSafeArea())
        .overlay { if busy { ProgressView() } }
        .task(id: ObjectIdentifier(game)) {
            elapsed = 0
            for _ in 0..<longest {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                elapsed += 1
            }
        }
        .task(id: "ms-\(ObjectIdentifier(game).hashValue)") {
            elapsedMs = 0
            let until = nearestMs
            while elapsedMs < until {
                try? await Task.sleep(nanoseconds: 100_000_000)
                if Task.isCancelled { return }
                elapsedMs += 100
            }
        }
    }
}

private struct Header: View {
    let game: GameView
    let onRefresh: () -> Void
    @Environment(\.tz) private var c
    @Environment(\.elapsedMs) private var elapsedMs

    var body: some View {
        let ch = game.character
        let fighting = game.location.npcs.contains { $0.fightingYou } || game.people.contains { $0.attacking == "вас" }
        let restMs = GameScene.shared.restLeftMs(game: game, elapsedMs: elapsedMs)
        let rest = GameScene.shared.tenths(ms: restMs)
        let low = GameScene.shared.lowHealth(game: game) && !ch.ghost
        let state = ch.ghost ? "призрак" : restMs > 0 ? "отдых \(rest) с" : fighting ? "в бою" : game.location.guarded ? "в безопасности" : ""
        VStack(spacing: CGFloat(Design.Space.shared.XS)) {
            HStack {
                Button(action: onRefresh) {
                    Text(String(ch.name.prefix(1))).font(TzType.heading).foregroundStyle(c.onPrimary)
                        .frame(width: CGFloat(Design.Size.shared.MEDAL), height: CGFloat(Design.Size.shared.MEDAL))
                        .background(Circle().fill(c.primary))
                }.buttonStyle(.plain).accessibilityLabel("осмотреться")
                VStack(alignment: .leading) {
                    Text(ch.name).font(TzType.name).foregroundStyle(c.title).lineLimit(1)
                    Text([ch.rank, ch.title].filter { !$0.isEmpty }.joined(separator: " · ")).font(TzType.small).foregroundStyle(c.textMuted)
                }
                Spacer()
                if !state.isEmpty { Text(state).font(TzType.label).foregroundStyle(fighting || ch.ghost || low ? c.danger : c.textMuted) }
            }
            HStack(spacing: CGFloat(Design.Space.shared.S)) {
                TzIcon(key: "health", size: 14, color: c.danger)
                TzBar(value: Int(ch.hp), max: Int(ch.hpMax), fill: c.health)
                Text("\(ch.hp)/\(ch.hpMax)").font(TzType.number)
                TzIcon(key: "mana", size: 14, color: c.link)
                TzBar(value: Int(ch.mana), max: Int(ch.manaMax), fill: c.mana)
                Text("\(ch.mana)/\(ch.manaMax)").font(TzType.number)
            }
        }
        .padding(.horizontal, CGFloat(Design.Space.shared.SCREEN))
        .padding(.vertical, CGFloat(Design.Space.shared.S))
        .background(c.surface)
    }
}

private struct SubTabs: View {
    let titles: [String]
    let selected: Int
    let onSelect: (Int) -> Void
    @Environment(\.tz) private var c

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: CGFloat(Design.Space.shared.XS)) {
                ForEach(Array(titles.enumerated()), id: \.offset) { i, t in
                    let on = i == selected
                    Button { onSelect(i) } label: {
                        Text(t).font(TzType.tab).foregroundStyle(on ? c.title : c.textMuted)
                            .padding(.horizontal, CGFloat(Design.Space.shared.M)).padding(.vertical, CGFloat(Design.Space.shared.S))
                            .background(on ? c.surfaceRaised : Color.clear)
                            .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).stroke(on ? c.border : Color.clear, lineWidth: 1))
                    }.buttonStyle(.plain)
                }
            }
        }
    }
}

private struct TabBar: View {
    let tab: GameTab
    let unread: Int
    let onSelect: (GameTab) -> Void
    @Environment(\.tz) private var c

    var body: some View {
        HStack(spacing: 0) {
            ForEach(GameTab.allCases, id: \.rawValue) { t in
                let on = t == tab
                Button { onSelect(t) } label: {
                    VStack(spacing: 2) {
                        TzIcon(key: t.icon, size: CGFloat(Design.Size.shared.TAB_ICON), color: on ? c.accent : c.textMuted)
                        Text(t.title + (t == .social && unread > 0 ? " ·\(unread)" : "")).font(TzType.tab).foregroundStyle(on ? c.title : c.textMuted).lineLimit(1)
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                }.buttonStyle(.plain).accessibilityLabel(t.title)
            }
        }
        .frame(height: CGFloat(Design.Size.shared.TAB_BAR))
        .background(c.surface)
        .overlay(Rectangle().frame(height: 1).foregroundStyle(c.borderSoft), alignment: .top)
    }
}

/// Exits as arrow buttons only (owner's note 02.10): a tap goes, a long press shows where it leads.
private struct Exits: View {
    let exits: [ExitView]
    let busy: Bool
    let onGo: (ExitView) -> Void
    let onGallop: (ExitView) -> Void
    @State private var hint: String? = nil
    @Environment(\.tz) private var c

    private static let order = ["west", "north", "up", "enter", "down", "south", "east"]

    /// A bold system arrow for a side, a door for the rest.
    static func symbol(_ key: String) -> String {
        switch key {
        case "north": return "arrow.up"
        case "south": return "arrow.down"
        case "east": return "arrow.right"
        case "west": return "arrow.left"
        case "up": return "arrow.up.to.line"
        case "down": return "arrow.down.to.line"
        default: return "door.left.hand.open"
        }
    }

    var body: some View {
        let sorted = exits.sorted { (Exits.order.firstIndex(of: GameScene.shared.exitIcon(label: $0.label)) ?? 9) < (Exits.order.firstIndex(of: GameScene.shared.exitIcon(label: $1.label)) ?? 9) }
        let side: CGFloat = 76
        VStack(spacing: 2) {
            if let h = hint { Text(h).font(TzType.small).foregroundStyle(c.textMuted) }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: side, maximum: side), spacing: 8)], spacing: 4) {
                ForEach(sorted, id: \.target) { e in
                    ZStack(alignment: .topTrailing) {
                        VStack(spacing: 2) {
                            Image(systemName: Exits.symbol(GameScene.shared.exitIcon(label: e.label)))
                                .font(.system(size: 22, weight: .heavy)).foregroundStyle(c.onPrimary)
                            Text(GameScene.shared.exitCaption(label: e.label)).font(.system(size: 11)).foregroundStyle(c.onPrimary)
                                .lineLimit(2).multilineTextAlignment(.center).minimumScaleFactor(0.8)
                        }
                            .padding(.vertical, 4).padding(.horizontal, 2)
                            .frame(width: side, height: side + 12)
                            .background(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).fill(c.primary))
                            .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).stroke(c.title, lineWidth: 1))
                        if e.occupied { Circle().fill(c.danger).frame(width: 7, height: 7).padding(4) }
                    }
                    .opacity(busy ? 0.5 : 1)
                    .contentShape(Rectangle())
                    .onTapGesture { if !busy { onGo(e) } }
                    .onLongPressGesture { hint = e.label + (e.occupied ? " — там кто-то есть" : "") }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(e.label)
                    .accessibilityAddTraits(.isButton)
                    if e.gallop { ActionButton(label: "галопом \(e.label)", enabled: !busy, action: { onGallop(e) }, icon: "gallop") }
                }
            }
        }
        .padding(.horizontal, CGFloat(Design.Space.shared.S)).padding(.vertical, CGFloat(Design.Space.shared.XS))
        .background(c.surfaceSunken)
        .onChange(of: exits.map { $0.target }) { _ in hint = nil }
    }
}

private struct Belt: View {
    let game: GameView
    let busy: Bool
    let layout: LayoutActions
    let onBag: () -> Void
    @Environment(\.tz) private var c

    var body: some View {
        let low = GameScene.shared.lowHealth(game: game)
        HStack(spacing: CGFloat(Design.Space.shared.S)) {
            ForEach(Array(SceneData.belt(game).enumerated()), id: \.offset) { i, cell in
                let id = cell.0
                let item = cell.1
                let glow = low && item != nil && (id.hasPrefix("i.f.b.heal") || id.hasPrefix("i.f.b.health"))
                ActionButton(label: item?.name ?? "пустая ячейка пояса", enabled: !busy && item != nil && !game.character.ghost,
                             action: { layout.useBelt(i) }, art: id.isEmpty ? nil : GameScene.shared.itemPath(id: id),
                             badge: item.map { "\($0.count)" })
                    .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).stroke(glow ? c.accent : Color.clear, lineWidth: 2))
            }
            Spacer()
            ActionButton(label: "Сумка", enabled: true, action: onBag, icon: "tab-bag")
        }
        .padding(.horizontal, CGFloat(Design.Space.shared.SCREEN)).padding(.vertical, CGFloat(Design.Space.shared.XS))
        .background(c.surface)
    }
}

// MARK: - Локация

/// One line of the place: portrait, name, status, up to four buttons; a tap on the name shows the rest.
private struct ListRow<Buttons: View, Extra: View>: View {
    let name: String
    let status: String?
    let art: String?
    var hp: (Int, Int)? = nil
    var hurt = false
    var undead = false
    var odds: (String, Int)? = nil
    @ViewBuilder let buttons: () -> Buttons
    @ViewBuilder let extra: () -> Extra
    @State private var open = false
    @Environment(\.tz) private var c

    var body: some View {
        let side = CGFloat(Design.Size.shared.PORTRAIT_SMALL)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: CGFloat(Design.Space.shared.S)) {
                if hurt { Rectangle().fill(c.danger).frame(width: 3, height: side) }
                ZStack {
                    if let a = art { ArtImage(path: a, dim: undead) } else {
                        c.surfaceSunken
                        Text(String(name.prefix(1))).font(TzType.heading).foregroundStyle(c.textMuted)
                    }
                }
                .frame(width: side, height: side)
                .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)))
                Button { open.toggle() } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(TzType.name).foregroundStyle(hurt ? c.danger : c.title).lineLimit(1)
                        if let o = odds { Text(o.0).font(TzType.small).foregroundStyle(o.1 == 0 ? c.logGain : o.1 == 1 ? c.accent : c.danger).lineLimit(1) }
                        if let s = status { Text(s).font(TzType.small).foregroundStyle(c.textMuted).lineLimit(1) }
                        if let h = hp { TzBar(value: h.0, max: h.1, fill: c.health).frame(height: CGFloat(Design.Size.shared.BAR_THIN)) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.buttonStyle(.plain)
                HStack(spacing: CGFloat(Design.Space.shared.XS)) { buttons() }
            }
            .padding(CGFloat(Design.Space.shared.XS))
            if open {
                ScrollView(.horizontal, showsIndicators: false) { HStack { extra() }.buttonStyle(.borderless) }
                    .padding(.horizontal, CGFloat(Design.Space.shared.S)).padding(.bottom, CGFloat(Design.Space.shared.XS))
            }
        }
        .tzPanel(c)
    }
}

/// A heading inside the place list: «бьют вас · 3», with a note on the right.
private struct ListSection: View {
    let title: String
    var note: String? = nil
    @Environment(\.tz) private var c

    var body: some View {
        HStack {
            Text(title).font(TzType.label).foregroundStyle(c.accent)
            Spacer()
            if let n = note { Text(n).font(TzType.small).foregroundStyle(c.textMuted) }
        }
        .padding(.horizontal, 2)
        .contentShape(Rectangle())
    }
}

/// While resting the row buttons are pale; the belt works.
private struct RestBanner: View {
    let seconds: String
    @Environment(\.tz) private var c

    var body: some View {
        Text("Отдых \(seconds) с — удары и приёмы ждут. Зелье можно выпить сейчас.")
            .font(TzType.small).foregroundStyle(c.onDanger)
            .padding(CGFloat(Design.Space.shared.S))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(c.dangerFill)
            .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)))
            .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).stroke(c.danger, lineWidth: 1))
    }
}

private struct Chip: View {
    let text: String
    var danger = false
    @Environment(\.tz) private var c

    var body: some View {
        Text(text).font(TzType.label).foregroundStyle(danger ? c.onDanger : c.text)
            .padding(.horizontal, CGFloat(Design.Space.shared.S)).padding(.vertical, 2)
            .background(danger ? c.danger : c.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.S)))
    }
}

struct JournalLines: View {
    let game: GameView
    let count: Int
    @Environment(\.tz) private var c

    private func color(_ kind: String) -> Color {
        switch kind {
        case JournalKind.shared.FIGHT: return c.logFight
        case JournalKind.shared.HURT: return c.logHurt
        case JournalKind.shared.SAY: return c.logSay
        case JournalKind.shared.GAIN: return c.logGain
        default: return c.logSystem
        }
    }

    var body: some View {
        let rows = SceneData.journal(game, count, hereOnly: true)
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
                    Text(r.0).font(TzType.log).foregroundStyle(color(r.1))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(CGFloat(Design.Space.shared.S))
            .background(c.surfaceSunken)
        }
    }
}

private struct PlaceTab: View {
    let game: GameView
    let busy: Bool
    let onTake: (GroundItemView) -> Void
    let onAttack: (NpcView) -> Void
    let onLoot: (CorpseView, GroundItemView) -> Void
    let onButcher: (CorpseView) -> Void
    let onResurrect: () -> Void
    let onTalk: (NpcView) -> Void
    let more: MoreActions
    let social: SocialActions
    let layout: LayoutActions
    @Environment(\.tz) private var c
    @Environment(\.elapsedMs) private var elapsedMs
    @State private var unawareShown: Bool? = nil

    private var restLeft: Int64 { GameScene.shared.restLeftMs(game: game, elapsedMs: elapsedMs) }

    private func status(_ npc: NpcView) -> String? {
        if npc.fightingYou {
            let blow = GameScene.shared.blowLeftMs(npc: npc, elapsedMs: elapsedMs)
            return "бьёт вас" + (blow != nil ? " · удар через \(GameScene.shared.tenths(ms: blow!.int64Value)) с" : "")
        }
        if let a = npc.attacking { return "бьёт \(a)" }
        if npc.mine { return "ваш" }
        if let o = npc.owner { return "хозяин: \(o)" }
        if npc.hostile { return "бродит, не замечает вас" }
        if npc.canTalk { return "можно поговорить" }
        return nil
    }

    private func canTame(_ npc: NpcView) -> Bool {
        let ch = game.character
        return !ch.ghost && !npc.mine && npc.owner == nil && npc.id.hasPrefix("n.a.") && (ch.skills["animaltaming"]?.intValue ?? 0) > 0
    }

    private func npcRow(_ npc: NpcView, resting: Bool, slots: [AbilityView?]) -> some View {
        let ch = game.character
        let fight = npc.attackable && !ch.ghost && (npc.fightingYou || npc.attacking != nil || !npc.canTalk)
        let title = npc.name + (npc.level > 0 ? " · ур. \(npc.level)" : "") + (npc.attackable && npc.hpMax > 0 ? "  \(npc.hp)/\(npc.hpMax)" : "")
        return ListRow(name: title, status: status(npc), art: npc.art.map { GameScene.shared.artPath(key: $0) },
                hp: npc.attackable && npc.hpMax > 0 ? (Int(npc.hp), Int(npc.hpMax)) : nil, hurt: npc.fightingYou, undead: npc.undead,
                odds: GameScene.shared.oddsLine(npc: npc).map { ($0, Int(GameScene.shared.oddsTone(npc: npc))) }) {
            if fight {
                ActionButton(label: "удар по \(npc.name)", enabled: !busy && !resting, action: { onAttack(npc) }, icon: "attack", danger: npc.fightingYou)
                ForEach(0..<slots.count, id: \.self) { i in SlotButton(ability: slots[i], target: npc.id, blocked: busy || resting, layout: layout) }
            } else {
                if npc.canTalk { ActionButton(label: "говорить с \(npc.name)", enabled: !busy, action: { onTalk(npc) }, icon: "talk") }
                ActionButton(label: "осмотреть \(npc.name)", enabled: !busy, action: { more.look(npc.id) }, icon: "look")
            }
        } extra: {
            Button("осмотреть") { more.look(npc.id) }.disabled(busy)
            if canTame(npc) { Button("приручить") { more.tame(npc) }.disabled(busy) }
            if SceneData.thief(ch) && !ch.ghost { Button("подглядеть") { more.peek(npc.id) }.disabled(busy) }
            if npc.attackable && !ch.ghost && npc.canTalk { Button("атаковать") { onAttack(npc) }.disabled(busy) }
        }
    }

    var body: some View {
        let ch = game.character
        let loc = game.location
        let resting = restLeft > 0
        let groups = GameScene.shared.groups(game: game)
        let fight = !groups.atYou.isEmpty || !groups.atOthers.isEmpty
        let showUnaware = unawareShown ?? !fight
        let slots = SceneData.slots(game)
        let enemies = loc.npcs.filter { $0.fightingYou }.count
        ZStack(alignment: .bottomLeading) {
            ArtImage(path: loc.art.map { GameScene.shared.artPath(key: $0) }, dim: ch.ghost)
            HStack {
                Text(loc.name).font(TzType.heading).foregroundStyle(c.title)
                Spacer()
                if loc.guarded { Chip(text: "охраняется") }
                if enemies > 0 { Chip(text: "бой · \(enemies)", danger: true) }
            }
            .padding(CGFloat(Design.Space.shared.S))
            .background(Color.black.opacity(0.55))
        }
        .aspectRatio(CGFloat(Design.Size.shared.SCENE_RATIO), contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.L)))
        .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.L)).stroke(c.border, lineWidth: 1))

        Notices(game: game, busy: busy, onResurrect: onResurrect, more: more, social: social)
        if let d = loc.description_ { Text(d).font(TzType.body) }
        if let cs = game.castle { CastleBlock(castle: cs, busy: busy, social: social) }

        Group {
        if resting && !groups.atYou.isEmpty { RestBanner(seconds: GameScene.shared.tenths(ms: restLeft)) }
        if !groups.atYou.isEmpty { ListSection(title: "бьют вас · \(groups.atYou.count)", note: groups.atYou.count > 1 ? "ближайший удар — сверху" : nil) }
        ForEach(groups.atYou, id: \.id) { npc in npcRow(npc, resting: resting, slots: slots) }
        if !groups.atOthers.isEmpty { ListSection(title: "бьют других · \(groups.atOthers.count)") }
        ForEach(groups.atOthers, id: \.id) { npc in npcRow(npc, resting: resting, slots: slots) }
        if !groups.unaware.isEmpty {
            if fight {
                Button { unawareShown = !showUnaware } label: {
                    ListSection(title: "не заметили вас · \(groups.unaware.count)", note: showUnaware ? "▾" : "▸")
                }.buttonStyle(.plain)
            }
            if showUnaware { ForEach(groups.unaware, id: \.id) { npc in npcRow(npc, resting: resting, slots: slots) } }
        }
        if fight && !groups.rest.isEmpty { ListSection(title: "рядом") }
        ForEach(groups.rest, id: \.id) { npc in npcRow(npc, resting: resting, slots: slots) }
        }
        ForEach(game.people, id: \.name) { p in PersonRow(person: p, game: game, busy: busy, more: more, social: social) }
        ForEach(loc.items, id: \.id) { item in
            let tool = item.id.hasPrefix("i.s.")
            let work = item.useWith.flatMap { id in game.inventory.first { $0.id == id } }
            let status = work.map { "работать: \($0.name)" } ?? (tool && !item.takeable ? "здесь · нажмите «осмотреть»" : "лежит на земле")
            ListRow(name: item.name + (item.count > 1 ? " ×\(item.count)" : ""), status: status, art: GameScene.shared.itemPath(id: item.id)) {
                if let w = work {
                    ActionButton(label: "работать: \(w.name)", enabled: !busy && !ch.ghost, action: { more.use(w) }, art: GameScene.shared.itemPath(id: w.id))
                }
                if item.takeable { ActionButton(label: (tool ? "использовать " : "взять ") + item.name, enabled: !busy, action: { onTake(item) }, icon: tool ? "use" : "take") }
                ActionButton(label: "осмотреть \(item.name)", enabled: !busy, action: { more.look(item.id) }, icon: "look")
            } extra: {
                if item.takeable && item.count > 1 { Button("взять одну") { more.takeOne(item) }.disabled(busy) }
            }
        }
        ForEach(loc.corpses, id: \.id) { corpse in
            let things = corpse.items.reduce(0) { $0 + Int($1.count) }
            let note = corpse.mine ? "ваши вещи · \(things) шт., пропадут через \(corpse.minutesLeft) мин"
                : corpse.looting && !corpse.items.isEmpty ? "взять отсюда — мародёрство" : "\(corpse.items.count) вещ."
            ListRow(name: corpse.name, status: note, art: nil, hurt: corpse.mine) {
                if corpse.canButcher && !ch.ghost { ActionButton(label: "разделать", enabled: !busy, action: { onButcher(corpse) }, icon: "use") }
            } extra: {
                ForEach(corpse.items, id: \.id) { item in
                    Button("взять: \(item.name)" + (item.count > 1 ? " ×\(item.count)" : "")) { onLoot(corpse, item) }.disabled(busy || ch.ghost)
                }
                if corpse.canRaise && !ch.ghost && (ch.skills["necro"]?.intValue ?? 0) > 0 { Button("поднять") { more.raise(corpse) }.disabled(busy) }
            }
        }
        JournalLines(game: game, count: 3)
    }
}

private struct SlotButton: View {
    let ability: AbilityView?
    let target: String
    let blocked: Bool
    let layout: LayoutActions
    @Environment(\.elapsed) private var elapsed

    var body: some View {
        if let a = ability {
            let left = max(0, a.readyIn - elapsed)
            ActionButton(label: a.name, enabled: !blocked && left == 0 && !a.later, action: { layout.strike(a, target) },
                         art: GameScene.shared.itemPath(id: a.id), badge: left == 0 ? nil : left < 60 ? "\(left)" : "\((left + 59) / 60)м")
        } else {
            ActionButton(label: "пустая ячейка приёма", enabled: false, action: {})
        }
    }
}

private struct PersonRow: View {
    let person: PersonView
    let game: GameView
    let busy: Bool
    let more: MoreActions
    let social: SocialActions
    @Environment(\.elapsedMs) private var elapsedMs

    var body: some View {
        let p = person
        let ch = game.character
        let restLeft = GameScene.shared.restLeftMs(game: game, elapsedMs: elapsedMs)
        var parts: [String] = []
        if let cl = p.clan { parts.append("клан \(cl)") }
        if let cr = p.crime { parts.append(cr) }
        if let f = p.faction { parts.append(f) }
        if let hp = p.hpPercent { parts.append("\(hp.intValue)%") }
        if p.rider { parts.append("всадник") }
        if p.flag { parts.append("с флагом!") }
        if let a = p.attacking { parts.append("бьёт \(a)") }
        if p.ghost { parts.append("призрак") }
        return ListRow(name: p.name, status: parts.isEmpty ? nil : parts.joined(separator: " · "), art: nil, hurt: p.attacking == "вас") {
            ActionButton(label: "осмотреть \(p.name)", enabled: !busy, action: { more.look(p.name) }, icon: "look")
            if !ch.ghost && !p.ghost {
                ActionButton(label: "обмен с \(p.name)", enabled: !busy, action: { social.startExchange(p) }, icon: "give")
                ActionButton(label: "удар по \(p.name)", enabled: !busy && restLeft == 0, action: { social.attackPlayer(p) }, icon: "attack", danger: p.attacking == "вас")
            }
        } extra: {
            if SceneData.thief(ch) && !ch.ghost && !p.ghost { Button("подглядеть") { more.peek(p.name) }.disabled(busy) }
            Button("в контакты") { social.addContact(p.name) }.disabled(busy)
            if game.clan != nil && p.clan == nil { Button("в клан") { social.clanOp("invite", p.name, nil, nil) }.disabled(busy) }
        }
    }
}

private struct Notices: View {
    let game: GameView
    let busy: Bool
    let onResurrect: () -> Void
    let more: MoreActions
    let social: SocialActions
    @Environment(\.tz) private var c

    var body: some View {
        let ch = game.character
        if ch.ghost {
            VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.XS)) {
                Text("Вы призрак").font(TzType.heading).foregroundStyle(c.title)
                Text("Найдите лекаря или камень воскрешения (лекарь Джозеф — двор к северу от Переулка). Призрак не может драться и брать вещи."
                     + (game.corpseAt.map { " Ваши вещи ждут в трупе: \($0)." } ?? "")).font(TzType.small)
                if game.canResurrect { Button("Воскреснуть") { onResurrect() }.disabled(busy).buttonStyle(.borderedProminent) }
            }
            .padding(CGFloat(Design.Space.shared.M))
            .frame(maxWidth: .infinity, alignment: .leading)
            .tzPanel(c)
        }
        if let crime = ch.crime { Text("Вы \(crime) — стража ищет вас ещё \(ch.crimeMinutes) мин").font(TzType.small).foregroundStyle(c.danger) }
        if ch.poisoned { Text("Вы отравлены: здоровье убывает").font(TzType.small).foregroundStyle(c.danger) }
        if ch.flag { HStack { Text("У вас флаг лидерства").font(TzType.small); Spacer(); Button("бросить") { more.dropFlag() }.disabled(busy) } }
        if let place = game.stele {
            HStack { Text("\(ch.spouse ?? "Супруг") ранен(а): \(place)").font(TzType.small).foregroundStyle(c.danger); Spacer(); if !ch.ghost { Button("на помощь") { more.stele() }.disabled(busy) } }
        }
        if let castle = game.alarm {
            HStack { Text("В \(castle) чужие!").font(TzType.small).foregroundStyle(c.danger); Spacer(); if !ch.ghost { Button("в замок") { social.castleOp("tele", nil) }.disabled(busy) } }
        }
        if ch.mounted { HStack { Text("Вы верхом").font(TzType.small); Spacer(); Button("спешиться") { more.dismount() }.disabled(busy) } }
        if let st = game.stance { Text("Стойка: \(st)").font(TzType.small).foregroundStyle(c.textMuted) }
    }
}

private struct CastleBlock: View {
    let castle: CastleView
    let busy: Bool
    let social: SocialActions
    @State private var sign = ""
    @Environment(\.tz) private var c

    var body: some View {
        let cs = castle
        VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.XS)) {
            Text((cs.owner.map { "Замок принадлежит клану \($0)" } ?? "Замок никому не принадлежит: первый член клана, вошедший в ворота, захватит его")
                 + (cs.lockedMinutes > 0 ? " · ворота заперты ещё \(cs.lockedMinutes) мин." : "") + (cs.guest ? " · вы гость" : "")).font(TzType.small)
            if !cs.sign.isEmpty { Text("Надпись на воротах: \(cs.sign)").font(TzType.small).foregroundStyle(c.textMuted) }
            HStack {
                if cs.canKnock { Button("Постучать") { social.castleOp("knock", nil) }.disabled(busy) }
                if cs.canOpen { Button("Открыть ворота") { social.castleOp("open", nil) }.disabled(busy) }
                if cs.vault { Button("Хранилище клана") { social.vaultOpen() }.disabled(busy) }
            }
            if cs.member {
                HStack {
                    TextField("Вывеска", text: $sign).textFieldStyle(.roundedBorder)
                    Button("сохранить") { social.castleOp("sign", sign) }.disabled(busy)
                }
            }
        }
        .padding(CGFloat(Design.Space.shared.S))
        .frame(maxWidth: .infinity, alignment: .leading)
        .tzPanel(c)
        .onAppear { sign = cs.sign }
    }
}

private struct SectionTitle: View {
    let text: String
    @Environment(\.tz) private var c
    var body: some View { Text(text).font(TzType.label).foregroundStyle(c.accent).padding(.top, CGFloat(Design.Space.shared.S)) }
}

// MARK: - Персонаж

private struct HeroTab: View {
    let game: GameView
    let busy: Bool
    let sub: Int
    let more: MoreActions
    let layout: LayoutActions
    let onSignOut: () -> Void
    @Environment(\.tz) private var c

    private func verb(_ a: AbilityView) -> String {
        switch a.kind {
        case "spell": return "читать"
        case "stance": return "встать"
        default: return "ударить"
        }
    }

    var body: some View {
        let ch = game.character
        switch sub {
        case 0:
            HStack {
                Text("\(ch.rank) \(ch.title)").font(TzType.heading).foregroundStyle(c.title)
                Spacer()
                ActionButton(label: "Аккаунт", enabled: !busy, action: layout.openAccount, icon: "settings")
            }
            Text("сила \(ch.str) · ловкость \(ch.dex) · интеллект \(ch.int_)" + (ch.skillPoints > 0 ? " · свободных очков \(ch.skillPoints)" : ""))
            Text("уровень \(ch.level) · опыт \(ch.exp)/\(ch.expNext) · мощь \(ch.power)").font(TzType.small).foregroundStyle(c.textMuted)
            TzBar(value: Int(ch.exp), max: Int(ch.expNext), fill: c.exp)
            Text("удар \(ch.hit)% · урон \(ch.dmgMin)–\(ch.dmgMax) · броня \(ch.armor) · уклон \(ch.dodge)").font(TzType.small)
            Text("парирование \(ch.parry) · уклон от магии \(ch.magicDodge) · защита от магии \(ch.magicParry) · сопр. магии \(ch.magicResist)").font(TzType.small)
            SectionTitle(text: "В строке врага")
            HStack(spacing: CGFloat(Design.Space.shared.XS)) {
                ActionButton(label: "удар", enabled: false, action: {}, icon: "attack")
                ForEach(Array(SceneData.slots(game).enumerated()), id: \.offset) { i, a in
                    ActionButton(label: a?.name ?? "ячейка \(i + 1)", enabled: a != nil && !busy, action: { layout.setSlot(i, "") },
                                 art: a.map { GameScene.shared.itemPath(id: $0.id) }, badge: "\(i + 1)")
                }
            }
            Text("Нажмите на ячейку, чтобы освободить её; приёмы и заклинания кладутся в ячейки на вкладке «Приёмы и магия».").font(TzType.small).foregroundStyle(c.textMuted)
            SectionTitle(text: "Пояс")
            HStack(spacing: CGFloat(Design.Space.shared.XS)) {
                ForEach(Array(SceneData.belt(game).enumerated()), id: \.offset) { i, cell in
                    ActionButton(label: cell.1?.name ?? "ячейка \(i + 1)", enabled: !cell.0.isEmpty && !busy, action: { layout.setBelt(i, "") },
                                 art: cell.0.isEmpty ? nil : GameScene.shared.itemPath(id: cell.0), badge: cell.1.map { "\($0.count)" })
                }
            }
            if let admin = layout.openAdmin { Button("Модерация") { admin() }.disabled(busy) }
            Button("Выйти", role: .destructive) { onSignOut() }
        case 1:
            if ch.skillPoints > 0 { Text("Свободных очков: \(ch.skillPoints). Тратятся у учителей.").font(TzType.small).foregroundStyle(c.accent) }
            ForEach(Array(GameScene.shared.SKILL_GROUPS.enumerated()), id: \.offset) { _, group in
                SectionTitle(text: (group.first as String?) ?? "")
                ForEach((group.second as? [String]) ?? [], id: \.self) { k in
                    let v = Int(GameScene.shared.skillLevel(c: ch, key: k))
                    HStack {
                        Button { more.look("skill." + k) } label: {
                            Text(Rules.shared.skillTitle(key: k)).foregroundStyle(v > 0 ? c.text : c.textFaint).frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).disabled(busy)
                        if k == "meditation" && v > 0 && !ch.ghost { Button("медитировать") { more.meditate() }.disabled(busy) }
                        Pips(value: v, max: Int(Rules.shared.SKILL_MAX))
                    }
                }
            }
            if !ch.crafts.isEmpty {
                SectionTitle(text: "Ремёсла · \(ch.craftSum) из \(ch.craftSumMax)")
                Text("Растут от работы; учитель даёт только первые шаги.").font(TzType.small).foregroundStyle(c.textMuted)
                ForEach(ch.crafts, id: \.key) { cr in
                    HStack {
                        Button { more.look("skill." + cr.key) } label: {
                            VStack(alignment: .leading) {
                                Text(cr.title).foregroundStyle(cr.level > 0 ? c.text : c.textFaint)
                                Text(GameScene.shared.craftLine(c: cr)).font(TzType.small).foregroundStyle(c.textMuted)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).disabled(busy)
                        Pips(value: Int(cr.level), max: Int(cr.max))
                    }
                }
            }
        default:
            if game.abilities.isEmpty { Text("Вы ещё не знаете ни приёмов, ни заклинаний. Их учат учителя и книги.").foregroundStyle(c.textMuted) }
            ForEach(game.abilities, id: \.id) { a in
                VStack(alignment: .leading) {
                    HStack {
                        ArtImage(path: GameScene.shared.itemPath(id: a.id))
                            .frame(width: CGFloat(Design.Size.shared.ITEM_ICON), height: CGFloat(Design.Size.shared.ITEM_ICON))
                            .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)))
                        VStack(alignment: .leading) {
                            Text(a.name).font(TzType.name).foregroundStyle(c.title)
                            Text((a.manaCost > 0 ? "мана \(a.manaCost)" : "") + (a.readyIn > 0 ? " · через \((a.readyIn + 59) / 60) мин" : "") + (a.later ? " · позже" : ""))
                                .font(TzType.small).foregroundStyle(c.textMuted)
                        }
                        Spacer()
                        Button(verb(a)) { more.useAbility(a) }.disabled(busy || a.readyIn > 0 || a.later || ch.ghost)
                        Button("?") { more.look(a.id) }.disabled(busy)
                    }
                    if a.kind != "stance" {
                        HStack {
                            Text("в ячейку:").font(TzType.small).foregroundStyle(c.textMuted)
                            ForEach(0..<3, id: \.self) { i in
                                let here = i < game.slots.count && game.slots[i] == a.id
                                Button(here ? "\(i + 1) ✓" : "\(i + 1)") { layout.setSlot(i, here ? "" : a.id) }.disabled(busy)
                            }
                        }
                    }
                }
                .padding(CGFloat(Design.Space.shared.XS))
                .tzPanel(c)
            }
        }
    }
}

// MARK: - Сумка

private struct BagTab: View {
    let game: GameView
    let busy: Bool
    let onDrop: (InventoryItemView) -> Void
    let onToggleEquip: (InventoryItemView) -> Void
    let more: MoreActions
    let layout: LayoutActions
    @Environment(\.tz) private var c

    var body: some View {
        let ch = game.character
        let money = game.inventory.first { $0.id == Rules.shared.MONEY }?.count ?? 0
        let things = game.inventory.filter { $0.id != Rules.shared.MONEY }
        let worn = things.filter { $0.equipped }
        let carried = things.filter { !$0.equipped }
        HStack { TzIcon(key: "gold", size: 16, color: c.accent); Text("\(money)").font(TzType.number) }
        SectionTitle(text: "Экипировка")
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 4)], spacing: 4) {
            ForEach(Array(GameScene.shared.equipment(game: game).enumerated()), id: \.offset) { _, cell in
                let slot = (cell.first as String?) ?? ""
                let item = cell.second
                Button { if let i = item { more.look(i.id) } } label: {
                    VStack(spacing: 2) {
                        ZStack {
                            if let i = item { ArtImage(path: GameScene.shared.itemPath(id: i.id)) } else { c.surfaceSunken }
                        }
                        .frame(width: CGFloat(Design.Size.shared.ITEM_ICON) + 8, height: CGFloat(Design.Size.shared.ITEM_ICON) + 8)
                        .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)))
                        .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).stroke(item != nil ? c.border : c.borderSoft, lineWidth: 1))
                        Text(item?.name ?? slot).font(TzType.small).foregroundStyle(item != nil ? c.text : c.textFaint).lineLimit(1)
                    }
                }.buttonStyle(.plain).disabled(item == nil || busy)
            }
        }
        if game.inventory.isEmpty { Text("пусто").foregroundStyle(c.textMuted) }
        if !worn.isEmpty { SectionTitle(text: "Надето") }
        ForEach(worn, id: \.id) { item in BagRow(item: item, game: game, busy: busy, ghost: ch.ghost, onDrop: onDrop, onToggleEquip: onToggleEquip, more: more, layout: layout) }
        if !carried.isEmpty { SectionTitle(text: "В сумке") }
        ForEach(carried, id: \.id) { item in BagRow(item: item, game: game, busy: busy, ghost: ch.ghost, onDrop: onDrop, onToggleEquip: onToggleEquip, more: more, layout: layout) }
    }
}

private struct BagRow: View {
    let item: InventoryItemView
    let game: GameView
    let busy: Bool
    let ghost: Bool
    let onDrop: (InventoryItemView) -> Void
    let onToggleEquip: (InventoryItemView) -> Void
    let more: MoreActions
    let layout: LayoutActions
    @State private var open = false
    @Environment(\.tz) private var c

    var body: some View {
        let onBelt = game.belt.contains(item.id)
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                ArtImage(path: GameScene.shared.itemPath(id: item.id))
                    .frame(width: CGFloat(Design.Size.shared.ITEM_ICON), height: CGFloat(Design.Size.shared.ITEM_ICON))
                    .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)))
                Button { open.toggle() } label: {
                    VStack(alignment: .leading) {
                        Text(item.name + (item.count > 1 ? " ×\(item.count)" : "") + (item.equipped ? " (надето)" : "")).font(TzType.name).foregroundStyle(c.title)
                        if onBelt { Text("на поясе").font(TzType.small).foregroundStyle(c.accent) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.buttonStyle(.plain)
                if item.equippable { Button(item.equipped ? "снять" : "надеть") { onToggleEquip(item) }.disabled(busy).buttonStyle(.borderless) }
                if item.usable && !ghost { Button("исп.") { more.use(item) }.disabled(busy).buttonStyle(.borderless) }
            }
            .padding(CGFloat(Design.Space.shared.XS))
            if open {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        Button("осмотреть") { more.look(item.id) }.disabled(busy)
                        if item.usable && item.id.hasPrefix("i.f.") && !onBelt {
                            Button("на пояс") {
                                let free = game.belt.firstIndex(of: "") ?? max(game.belt.count - 1, 0)
                                layout.setBelt(free, item.id)
                            }.disabled(busy)
                        }
                        if item.count > 1 { Button("бросить одну") { more.dropOne(item) }.disabled(busy) }
                        Button("бросить") { onDrop(item) }.disabled(busy)
                    }.buttonStyle(.borderless)
                }.padding(.horizontal, CGFloat(Design.Space.shared.S)).padding(.bottom, CGFloat(Design.Space.shared.XS))
            }
        }
        .tzPanel(c)
    }
}

// MARK: - Общение

private struct SocialTab: View {
    let game: GameView
    let busy: Bool
    let sub: Int
    let social: SocialActions
    let more: MoreActions
    let layout: LayoutActions
    let mail: MessagesView?
    let clanInfo: ClanView?
    let worldInfo: WorldView?
    @State private var speech = ""
    @State private var mailTo: String?
    @State private var mailText = ""
    @Environment(\.tz) private var c

    var body: some View {
        switch sub {
        case 0:
            if game.people.isEmpty { Text("Рядом никого нет.").foregroundStyle(c.textMuted) }
            ForEach(game.people, id: \.name) { p in PersonRow(person: p, game: game, busy: busy, more: more, social: social) }
            let said = SceneData.journal(game, 30).filter { $0.1 == JournalKind.shared.SAY }.suffix(8)
            VStack(alignment: .leading, spacing: 2) {
                if said.isEmpty { Text("Здесь пока молчат.").font(TzType.log).foregroundStyle(c.textFaint) }
                ForEach(Array(said.enumerated()), id: \.offset) { _, r in Text(r.0).font(TzType.log).foregroundStyle(c.logSay) }
            }
            .frame(maxWidth: .infinity, alignment: .leading).padding(CGFloat(Design.Space.shared.S)).background(c.surfaceSunken)
            HStack {
                TextField("Сказать", text: $speech).textFieldStyle(.roundedBorder)
                Button("всем") { social.say(speech, false); speech = "" }.disabled(busy || speech.isEmpty)
                if game.clan != nil { Button("клану") { social.say(speech, true); speech = "" }.disabled(busy || speech.isEmpty) }
            }
        case 1:
            if let m = mail {
                Text("Контакты (добавить можно того, кто рядом; писать — тем, у кого вы в контактах):").font(TzType.label)
                if m.contacts.isEmpty { Text("пока никого").font(TzType.small) }
                ForEach(m.contacts, id: \.name) { ct in
                    HStack {
                        Text(ct.name + (ct.online ? " • в игре" : "") + (ct.mutual ? "" : " (вы не у него в контактах)")).font(TzType.small)
                        Spacer()
                        Button("написать") { mailTo = ct.name }
                        Button("убрать") { social.removeContact(ct.name) }.disabled(busy)
                    }
                }
                if m.contacts.contains(where: { $0.mutual }) { Button("написать всем") { mailTo = "*" } }
                if let to = mailTo {
                    TextField(to == "*" ? "Сообщение всем контактам" : "Сообщение для \(to)", text: $mailText).textFieldStyle(.roundedBorder)
                    Button("Отправить") {
                        if to == "*" { social.writeAll(mailText) } else { social.write(to, mailText) }
                        mailText = ""; mailTo = nil
                    }.disabled(busy || mailText.isEmpty)
                }
                SectionTitle(text: "Сообщения")
                if m.messages.isEmpty { Text("нет сообщений").font(TzType.small) }
                ForEach(Array(m.messages.enumerated()), id: \.offset) { _, msg in
                    Text((msg.clan ? "[клан] " : "") + "\(msg.from): \(msg.text)").font(TzType.small).foregroundStyle(msg.read ? c.text : c.accent)
                }
            } else {
                Button(game.unread > 0 ? "Почта (\(game.unread))" : "Открыть почту") { social.openMail() }.disabled(busy)
            }
        case 2:
            if let cl = clanInfo {
                if let msg = cl.message { Text(msg).font(TzType.small) }
                if cl.name == nil {
                    Text("Вы не в клане. Создать клан можно у Мирандера на центральной площади.").font(TzType.small)
                } else {
                    Text("Клан \(cl.name ?? "") · ваш ранг: \(Rules.shared.CLAN_RANKS[cl.rank ?? ""] ?? cl.rank ?? "")").font(TzType.name).foregroundStyle(c.title)
                    if !cl.info.isEmpty { Text(cl.info).font(TzType.small) }
                    ForEach(cl.members, id: \.name) { mem in
                        HStack {
                            Text("\(mem.name) — \(Rules.shared.CLAN_RANKS[mem.rank] ?? mem.rank)" + (mem.online ? " • в игре" : "")).font(TzType.small)
                            Spacer()
                            if cl.canManage && cl.rank == "head" && mem.rank != "head" {
                                let next = mem.rank == "neophyte" ? "vassal" : (mem.rank == "vassal" ? "seneschal" : "neophyte")
                                Button("→ \(Rules.shared.CLAN_RANKS[next] ?? next)") { social.clanOp("rank", mem.name, next, nil) }.disabled(busy)
                                Button("выгнать") { social.clanOp("kick", mem.name, nil, nil) }.disabled(busy)
                            }
                        }
                    }
                    Button(cl.rank == "head" ? "Распустить клан" : "Выйти из клана") { social.clanOp("leave", nil, nil, nil) }.disabled(busy)
                }
                ForEach(cl.invites, id: \.self) { inv in
                    HStack {
                        Text("Приглашение в клан \(inv)").font(TzType.small)
                        Spacer()
                        Button("вступить") { social.clanOp("accept", nil, nil, inv) }.disabled(busy)
                        Button("отказать") { social.clanOp("decline", nil, nil, inv) }.disabled(busy)
                    }
                }
            } else {
                Button((game.clan.map { "Клан \($0)" } ?? "Клан") + (game.clanInvites.isEmpty ? "" : " (приглашение)")) { social.openClan() }.disabled(busy)
            }
        case 3:
            if let w = worldInfo {
                Text("Сейчас в игре \(w.online.count)").font(TzType.label).foregroundStyle(c.accent)
                ForEach(w.online, id: \.name) { o in
                    Text("\(o.name) [\(o.level)]" + (o.clan.map { " *\($0)*" } ?? "") + (o.crime.map { " \($0)" } ?? "")).foregroundStyle(o.crime != nil ? c.danger : c.text)
                }
                Button("обновить") { more.openWorld() }.disabled(busy)
            } else {
                Button("Кто в игре") { more.openWorld() }.disabled(busy)
            }
        default:
            Text("Форум игры: торговля, кланы, вопросы новичков, новости.")
            Button("Открыть форум") { layout.openForum() }.disabled(busy).buttonStyle(.borderedProminent)
        }
    }
}

// MARK: - Мир

private struct WorldTab: View {
    let game: GameView
    let busy: Bool
    let sub: Int
    let social: SocialActions
    let more: MoreActions
    let layout: LayoutActions
    let worldInfo: WorldView?
    let mapView: MapView?
    let chronicle: ChronicleView?
    let news: ForumView?
    @Environment(\.tz) private var c

    var body: some View {
        switch sub {
        case 0:
            if let m = mapView {
                ZoomMap(points: m.points, here: game.character.location, flagAt: worldInfo?.flagLocationId)
            } else {
                Button("Показать карту") { more.openMap() }.disabled(busy)
            }
        case 1:
            if let cs = game.castle { CastleBlock(castle: cs, busy: busy, social: social) }
            if let w = worldInfo {
                ForEach(w.castles, id: \.id) { cs in
                    HStack {
                        Text(cs.name).font(TzType.name).foregroundStyle(c.title)
                        Spacer()
                        Text(cs.owner ?? "ничей").font(TzType.small).foregroundStyle(cs.owner != nil && cs.owner == game.clan ? c.accent : c.textMuted)
                    }
                    .padding(CGFloat(Design.Space.shared.S)).tzPanel(c)
                }
                Text("Флаг лидерства: " + (w.flagHolder.map { "у \($0) (\(w.flagLocation ?? "?"))" } ?? "лежит: \(w.flagLocation ?? "неизвестно где")")).font(TzType.small)
                SectionTitle(text: "Кланы")
                if w.clans.isEmpty { Text("пока нет").font(TzType.small).foregroundStyle(c.textMuted) }
                ForEach(w.clans, id: \.name) { cl in Text("\(cl.name) — \(cl.members)").font(TzType.small) }
            } else {
                Button("Замки и кланы") { more.openWorld() }.disabled(busy)
            }
        default:
            SectionTitle(text: "Новости")
            let topics = Array((news?.topics ?? []).prefix(5))
            if news == nil { Text("…").font(TzType.small).foregroundStyle(c.textMuted) }
            else if topics.isEmpty { Text("Новостей пока нет.").font(TzType.small).foregroundStyle(c.textMuted) }
            ForEach(topics, id: \.id) { t in
                Button { layout.openTopic(t) } label: {
                    VStack(alignment: .leading) {
                        Text(t.title).font(TzType.name).foregroundStyle(c.title)
                        Text("\(SiteFormat.date(t.updated)) · \(t.author)").font(TzType.small).foregroundStyle(c.textMuted)
                    }
                    .padding(CGFloat(Design.Space.shared.S)).frame(maxWidth: .infinity, alignment: .leading).tzPanel(c)
                }.buttonStyle(.plain).disabled(busy)
            }
            Button("Все новости") { layout.openNews() }.disabled(busy)
            SectionTitle(text: "Летопись мира · 7 дней")
            ChronicleList(entries: chronicle?.entries ?? [], loaded: chronicle != nil)
            Button("обновить") { layout.openEvents() }.disabled(busy)
            Button("Об игре и правила") { layout.openPages() }.disabled(busy)
        }
    }
}

private struct ChronicleList: View {
    let entries: [ChronicleEntry]
    let loaded: Bool
    @Environment(\.tz) private var c

    var body: some View {
        if loaded && entries.isEmpty { Text("Пока тихо: ни захватов, ни свадеб.").font(TzType.small).foregroundStyle(c.textMuted) }
        ForEach(Array(entries.enumerated()), id: \.offset) { i, e in
            let full = SiteFormat.date(e.at)
            let day = String(full.split(separator: " ").first ?? "")
            let prev = i > 0 ? String(SiteFormat.date(entries[i - 1].at).split(separator: " ").first ?? "") : ""
            VStack(alignment: .leading, spacing: 2) {
                if day != prev { Text(day).font(TzType.label).foregroundStyle(c.textMuted).padding(.top, 4) }
                HStack(alignment: .firstTextBaseline) {
                    Text(String(full.split(separator: " ").last ?? "")).font(TzType.number).foregroundStyle(c.textFaint).frame(width: 48, alignment: .leading)
                    Text(e.text).font(TzType.log).foregroundStyle(e.clan ? c.accent : c.text)
                }
            }
        }
    }
}

/// The map of this part of the world: pinch to zoom, drag to move; starts on you.
private struct ZoomMap: View {
    let points: [MapPoint]
    let here: String
    let flagAt: String?
    @State private var scale: CGFloat = 3
    @State private var lastScale: CGFloat = 3
    @State private var pan: CGSize = .zero
    @State private var lastPan: CGSize = .zero
    @Environment(\.tz) private var c

    var body: some View {
        let me = MapCanvas.point(here)
        let region = me?.2 ?? 0
        var dots: [(CGFloat, CGFloat, Bool)] = []
        for p in points {
            let px = CGFloat(Double(p.mapX)), py = CGFloat(Double(p.mapY))
            let r = py > 1101 ? 2 : (px > 1650 ? 1 : 0)
            if r == region { dots.append((px, py, p.guarded)) }
        }
        let castles = ["c.1.gate", "c.2.gate", "c.3.gate", "c.4.gate"].compactMap { MapCanvas.point($0) }.filter { $0.2 == region }
        let flag = flagAt.flatMap { MapCanvas.point($0) }.flatMap { $0.2 == region ? $0 : nil }
        let colors = c
        let s = scale, off = pan
        return VStack(alignment: .leading, spacing: 4) {
            Text(region == 1 ? "Ансалон" : region == 2 ? "Волчий остров" : "Основная территория").font(TzType.heading).foregroundStyle(c.title)
            Canvas { ctx, size in
                guard let minX = dots.map({ $0.0 }).min(), let maxX = dots.map({ $0.0 }).max(),
                      let minY = dots.map({ $0.1 }).min(), let maxY = dots.map({ $0.1 }).max() else { return }
                let k = min(size.width / max(maxX - minX + 1, 1), size.height / max(maxY - minY + 1, 1)) * s
                let cx = ((me?.0 ?? (minX + maxX) / 2) - minX) * k, cy = ((me?.1 ?? (minY + maxY) / 2) - minY) * k
                let ox = size.width / 2 - cx + off.width, oy = size.height / 2 - cy + off.height
                let d = min(max(k * 0.8, 2), 14)
                func dot(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ col: Color) {
                    let px = (x - minX) * k + ox, py = (y - minY) * k + oy
                    ctx.fill(Path(ellipseIn: CGRect(x: px - r, y: py - r, width: r * 2, height: r * 2)), with: .color(col))
                }
                for p in dots { dot(p.0, p.1, d / 2, p.2 ? colors.link : colors.textFaint) }
                for cs in castles { dot(cs.0, cs.1, d * 1.4, colors.danger) }
                if let f = flag { dot(f.0, f.1, d * 1.4, colors.title) }
                if let m = me { dot(m.0, m.1, d * 1.8, colors.accent); dot(m.0, m.1, d * 0.8, colors.background) }
            }
            .frame(height: 360)
            .background(c.surfaceSunken)
            .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.L)))
            .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.L)).stroke(c.border, lineWidth: 1))
            .gesture(SimultaneousGesture(
                MagnificationGesture().onChanged { v in scale = min(12, max(1, lastScale * v)) }.onEnded { _ in lastScale = scale },
                DragGesture().onChanged { v in pan = CGSize(width: lastPan.width + v.translation.width, height: lastPan.height + v.translation.height) }
                    .onEnded { _ in lastPan = pan }))
            Text("золото — вы, красное — замки, светлое — флаг лидерства, голубые точки — охраняемые улицы. Два пальца — масштаб.").font(TzType.small).foregroundStyle(c.textMuted)
        }
    }
}

/// A skill level as five marks.
struct Pips: View {
    let value: Int
    let max: Int
    @Environment(\.tz) private var c

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<max, id: \.self) { i in
                RoundedRectangle(cornerRadius: 2).fill(i < value ? c.accent : c.barTrack).frame(width: 10, height: 10)
                    .overlay(RoundedRectangle(cornerRadius: 2).stroke(c.borderSoft, lineWidth: 1))
            }
        }
    }
}

// MARK: - Шторки

/// Dialog, trade, bank, crafting, exchange, a look, a peek, a target choice: over the list; belt, exits and tabs stay.
private struct Sheets: View {
    let game: GameView
    let busy: Bool
    let onAnswer: (DialogOption) -> Void
    let onCloseDialog: () -> Void
    let pending: InventoryItemView?
    let pendingAbility: AbilityView?
    let more: MoreActions
    let social: SocialActions
    @State private var typed = ""
    @State private var vaultAccess = "neophyte"
    @Environment(\.tz) private var c

    private var any: Bool {
        game.dialog != nil || game.shop != nil || game.bank != nil || game.vault != nil || game.craft != nil || game.exchange != nil ||
            game.look != nil || game.peek != nil || game.choice != nil || pending != nil || pendingAbility != nil
    }

    private func count(_ name: String, _ n: Int32) -> String { n > 1 ? "\(name) ×\(n)" : name }

    var body: some View {
        if any {
            GeometryReader { g in
                VStack {
                    Spacer(minLength: 0)
                    ScrollView {
                        VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.S)) { content }
                            .padding(CGFloat(Design.Space.shared.M))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: g.size.height * 0.85)
                    .fixedSize(horizontal: false, vertical: true)
                    .background(c.surface)
                    .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.L)))
                    .shadow(color: .black.opacity(0.4), radius: 8)
                }
            }
        }
    }

    /// The bank cell and the clan vault (kept apart: a view builder takes at most ten children).
    @ViewBuilder private var bankAndVault: some View {
        if let bank = game.bank {
            SectionTitle(text: "Банк · \(bank.npcName)" + (bank.fee > 0 ? " (плата \(bank.fee))" : ""))
            if let m = bank.message { Text(m).font(TzType.small) }
            if bank.items.isEmpty { Text("в ячейке пусто").font(TzType.small).foregroundStyle(c.textMuted) }
            ForEach(bank.items, id: \.id) { item in
                HStack {
                    Text(count(item.name, item.count)).font(TzType.small)
                    Spacer()
                    Button("забрать") { more.bankTake(item, 1) }.disabled(busy)
                    if item.count > 1 { Button("все") { more.bankTake(item, Int(item.count)) }.disabled(busy) }
                }
            }
            Text("Положить из рюкзака:").font(TzType.label)
            ForEach(game.inventory.filter { !$0.equipped }, id: \.id) { item in
                HStack {
                    Text(count(item.name, item.count)).font(TzType.small).foregroundStyle(c.textMuted)
                    Spacer()
                    Button("в банк") { more.bankPut(item, Int(item.count)) }.disabled(busy)
                }
            }
            if bank.clanVault { Button("Хранилище клана") { social.vaultOpen() }.disabled(busy) }
            Button("закрыть") { onCloseDialog() }
        }
        if let vault = game.vault {
            SectionTitle(text: "Хранилище клана \(vault.clan) · \(vault.items.count)/\(vault.capacity)")
            if let m = vault.message { Text(m).font(TzType.small) }
            if vault.items.isEmpty { Text("в хранилище пусто").font(TzType.small).foregroundStyle(c.textMuted) }
            ForEach(vault.items, id: \.slot) { item in
                HStack {
                    VStack(alignment: .leading) {
                        Text(count(item.name, item.count)).font(TzType.small)
                        Text("\(item.owner) · \(Rules.shared.VAULT_ACCESS[item.access] ?? item.access)").font(TzType.small).foregroundStyle(c.textMuted)
                    }
                    Spacer()
                    if item.canTake {
                        Button("забрать") { more.vaultTake(item, 1) }.disabled(busy)
                        if item.count > 1 { Button("все") { more.vaultTake(item, Int(item.count)) }.disabled(busy) }
                    }
                }
            }
            Text("Кто сможет забрать:").font(TzType.label)
            Picker("", selection: $vaultAccess) {
                ForEach(Rules.shared.CLAN_RANK_ORDER, id: \.self) { r in Text(Rules.shared.VAULT_ACCESS[r] ?? r).tag(r) }
            }
            .pickerStyle(.segmented)
            Text("Положить из рюкзака:").font(TzType.label)
            ForEach(game.inventory.filter { !$0.equipped && Rules.shared.tradeable(id: $0.id) }, id: \.id) { item in
                HStack {
                    Text(count(item.name, item.count)).font(TzType.small).foregroundStyle(c.textMuted)
                    Spacer()
                    Button("положить") { more.vaultPut(item, Int(item.count), vaultAccess) }.disabled(busy)
                }
            }
            if !vault.log.isEmpty {
                Text("Журнал:").font(TzType.label)
                ForEach(vault.log, id: \.self) { Text($0).font(TzType.small).foregroundStyle(c.textMuted) }
            }
            Button("закрыть") { onCloseDialog() }
        }
    }

    @ViewBuilder private var content: some View {
        if let d = game.dialog {
            HStack {
                if let art = game.location.npcs.first(where: { $0.id == d.npc })?.art {
                    ArtImage(path: GameScene.shared.artPath(key: art))
                        .frame(width: CGFloat(Design.Size.shared.PORTRAIT_SMALL), height: CGFloat(Design.Size.shared.PORTRAIT_SMALL))
                        .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)))
                }
                Text(d.npcName).font(TzType.heading).foregroundStyle(c.title)
            }
            Text(d.text)
            if d.inputTopic != nil {
                TextField("Ответ", text: $typed).textFieldStyle(.roundedBorder)
                Button("ответить") { social.answerText(typed); typed = "" }.disabled(busy || typed.isEmpty)
            }
            if !d.teach.isEmpty {
                VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.XS)) {
                    Text("Обучение · свободных очков: \(game.character.skillPoints)").font(TzType.label).foregroundStyle(c.title)
                    ForEach(Array(d.teach.enumerated()), id: \.offset) { _, o in
                        Text(GameScene.shared.teachLine(o: o)).font(TzType.small).foregroundStyle(o.note != nil ? c.textMuted : c.text)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(CGFloat(Design.Space.shared.S))
                .tzPanel(c)
            }
            ForEach(Array(d.options.enumerated()), id: \.offset) { _, o in
                Button("› " + o.label) { onAnswer(o) }.disabled(busy)
            }
            if game.shop == nil && game.bank == nil && game.craft == nil {
                Button(d.options.isEmpty ? "[Конец диалога]" : "закончить разговор") { onCloseDialog() }
            }
        }
        if let shop = game.shop {
            SectionTitle(text: shop.npcName + (shop.mode == "sell" ? " покупает" : " продаёт"))
            if let m = shop.message { Text(m).font(TzType.small) }
            ForEach(shop.items, id: \.id) { item in
                HStack {
                    ArtImage(path: GameScene.shared.itemPath(id: item.id)).frame(width: 32, height: 32)
                    Text("\(item.name)\(item.count > 1 ? " (\(item.count))" : "") — \(item.price) \(shop.currency)").font(TzType.small)
                    Spacer()
                    Button(shop.mode == "sell" ? "продать" : "купить") { more.trade(item, 1) }.disabled(busy)
                    if item.count > 1 { Button("все") { more.trade(item, Int(item.count)) }.disabled(busy) }
                }
            }
            Button("закрыть") { onCloseDialog() }
        }
        bankAndVault
        if let craft = game.craft {
            SectionTitle(text: craft.title)
            ForEach(Array(craft.options.enumerated()), id: \.offset) { _, o in
                Button("\(o.name) — \(o.chance)% (\(o.needs))") { more.craft(o) }.disabled(busy)
            }
            Button("закрыть") { onCloseDialog() }
        }
        if let ex = game.exchange {
            SectionTitle(text: "Обмен с \(ex.partner)" + (ex.waiting ? " (ждём его)" : ""))
            Text("Вы отдаёте:" + (ex.iAgree ? " ✓ согласны" : "")).font(TzType.small)
            ForEach(ex.mine, id: \.id) { item in
                HStack { Text("\(item.name) ×\(item.count)"); Spacer(); Button("убрать") { social.withdraw(item) }.disabled(busy) }
            }
            Text("\(ex.partner) отдаёт:" + (ex.theyAgree ? " ✓ согласен" : "")).font(TzType.small)
            ForEach(ex.theirs, id: \.id) { item in Text("\(item.name) ×\(item.count)") }
            ForEach(game.inventory.filter { inv in !inv.equipped && !ex.mine.contains { $0.id == inv.id } }, id: \.id) { item in
                Button("+ \(item.name) ×\(item.count)") { social.offer(item, Int(item.count)) }.disabled(busy)
            }
            Button("Согласен") { social.agree() }.disabled(busy || ex.iAgree || ex.waiting)
            Button("Отменить обмен", role: .destructive) { social.cancelExchange() }
        }
        if let ch = game.choice {
            SectionTitle(text: ch.title)
            ForEach(ch.options, id: \.value) { o in Button(o.label) { social.choose(o) }.disabled(busy) }
            Button("отмена") { social.closeChoice() }
        }
        if let p = pending {
            SectionTitle(text: "Применить «\(p.name)» к…")
            ForEach(Targets.shared.choices(kind: p.target, game: game, exceptItem: p.id), id: \.value) { t in
                Button(t.label) { more.useOn(t.value) }.disabled(busy)
            }
            Button("отмена") { more.cancelUse() }
        }
        if let a = pendingAbility {
            SectionTitle(text: "«\(a.name)» — на кого?")
            ForEach(Targets.shared.choices(kind: a.target, game: game, exceptItem: nil), id: \.value) { t in
                Button(t.label) { more.aimAbility(t.value) }.disabled(busy)
            }
            Button("отмена") { more.cancelAbility() }
        }
        if let l = game.look {
            SectionTitle(text: l.title)
            Text(l.text).font(TzType.small)
            if let pg = l.page { Button(pg == "news" ? "Все новости" : "Выбрать книгу") { more.openSite(pg) }.disabled(busy) }
            Button("закрыть") { more.closeLook() }
        }
        if let pk = game.peek {
            SectionTitle(text: "Рюкзак: \(pk.targetName)")
            ForEach(pk.items, id: \.id) { it in
                HStack {
                    Text(it.name + (it.count > 1 ? " ×\(it.count)" : "") + (it.equipped ? " (надето)" : "")).font(TzType.small)
                    Spacer()
                    Button("украсть") { more.steal(it) }.disabled(busy)
                }
            }
            Button("закрыть") { more.closePeek() }
        }
    }
}
