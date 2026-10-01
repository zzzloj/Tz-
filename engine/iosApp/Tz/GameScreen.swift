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
}

/// Plain values the screen needs from the shared Kotlin code, computed on the Swift side.
enum SceneData {
    static func slots(_ g: GameView) -> [AbilityView?] {
        g.slots.map { id in id.isEmpty ? nil : g.abilities.first { $0.id == id } }
    }
    static func belt(_ g: GameView) -> [(String, InventoryItemView?)] {
        g.belt.map { id in (id, id.isEmpty ? nil : g.inventory.first { $0.id == id }) }
    }
    static func journal(_ g: GameView, _ n: Int) -> [(String, String)] {
        let lines = Array(g.journal.suffix(n))
        let kinds = Array(g.journalKinds.suffix(n))
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

extension EnvironmentValues {
    var elapsed: Int64 {
        get { self[ElapsedKey.self] }
        set { self[ElapsedKey.self] = newValue }
    }
    var artUrl: ((String) -> String)? {
        get { self[ArtUrlKey.self] }
        set { self[ArtUrlKey.self] = newValue }
    }
}

/// A picture from the server with a quiet placeholder.
struct ArtImage: View {
    let path: String?
    var dim = false
    @Environment(\.artUrl) private var artUrl
    @Environment(\.tz) private var c

    var body: some View {
        ZStack {
            c.surfaceSunken
            if let p = path, let make = artUrl, let url = URL(string: make(p)) {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill().opacity(dim ? 0.45 : 1)
                    }
                }
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
    let info: String?
    let error: String?
    let onRefresh: () -> Void
    let onSignOut: () -> Void
    @State private var tab = GameTab.place
    @State private var sub = 0
    @State private var elapsed: Int64 = 0
    @Environment(\.tz) private var c

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
        default: break
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            Header(game: game, onRefresh: onRefresh)
            ZStack(alignment: .bottom) {
                ScrollView {
                    VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.S)) {
                        if !tab.subs.isEmpty { SubTabs(titles: tab.subs, selected: sub, onSelect: select) }
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
                            WorldTab(game: game, busy: busy, sub: sub, social: social, more: more, layout: layout, worldInfo: worldInfo, mapView: mapView)
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
            TabBar(tab: tab, unread: Int(game.unread)) { t in tab = t; sub = 0 }
        }
        .grayscale(game.character.ghost ? 0.85 : 0)
        .environment(\.elapsed, elapsed)
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
    }
}

private struct Header: View {
    let game: GameView
    let onRefresh: () -> Void
    @Environment(\.tz) private var c
    @Environment(\.elapsed) private var elapsed

    var body: some View {
        let ch = game.character
        let fighting = game.location.npcs.contains { $0.fightingYou } || game.people.contains { $0.attacking == "вас" }
        let rest = Int(GameScene.shared.left(seconds: KotlinInt(int: game.restSeconds), elapsed: elapsed))
        let low = GameScene.shared.lowHealth(game: game) && !ch.ghost
        let state = ch.ghost ? "призрак" : rest > 0 ? "отдых \(rest) с" : fighting ? "в бою" : game.location.guarded ? "в безопасности" : ""
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

private struct Exits: View {
    let exits: [ExitView]
    let busy: Bool
    let onGo: (ExitView) -> Void
    let onGallop: (ExitView) -> Void
    @Environment(\.tz) private var c

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: CGFloat(Design.Space.shared.XS)) {
                ForEach(exits, id: \.target) { e in
                    Button { onGo(e) } label: {
                        HStack(spacing: CGFloat(Design.Space.shared.XS)) {
                            TzIcon(key: GameScene.shared.exitIcon(label: e.label), size: 16, color: c.accent)
                            Text(e.label + (e.occupied ? " !" : "")).font(TzType.button)
                        }
                        .padding(.horizontal, CGFloat(Design.Space.shared.M))
                        .frame(minHeight: CGFloat(Design.Size.shared.TOUCH))
                        .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).stroke(c.border, lineWidth: 1))
                    }.buttonStyle(.plain).disabled(busy)
                    if e.gallop { ActionButton(label: "галопом \(e.label)", enabled: !busy, action: { onGallop(e) }, icon: "gallop") }
                }
            }
            .padding(.horizontal, CGFloat(Design.Space.shared.S)).padding(.vertical, CGFloat(Design.Space.shared.XS))
        }
        .background(c.surfaceSunken)
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
    let seconds: Int
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
        let rows = SceneData.journal(game, count)
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
    @Environment(\.elapsed) private var elapsed
    @State private var unawareShown: Bool? = nil

    private func left(_ v: KotlinInt?) -> Int { Int(GameScene.shared.left(seconds: v, elapsed: elapsed)) }

    private func status(_ npc: NpcView) -> String? {
        if npc.fightingYou { return "бьёт вас" + (npc.nextBlow != nil ? " · удар через \(left(npc.nextBlow)) с" : "") }
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
        let title = npc.name + (npc.attackable && npc.hpMax > 0 ? "  \(npc.hp)/\(npc.hpMax)" : "")
        return ListRow(name: title, status: status(npc), art: npc.art.map { GameScene.shared.artPath(key: $0) },
                hp: npc.attackable && npc.hpMax > 0 ? (Int(npc.hp), Int(npc.hpMax)) : nil, hurt: npc.fightingYou, undead: npc.undead) {
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
        let resting = left(KotlinInt(int: game.restSeconds)) > 0
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
        if resting && !groups.atYou.isEmpty { RestBanner(seconds: left(KotlinInt(int: game.restSeconds))) }
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
            ListRow(name: item.name + (item.count > 1 ? " ×\(item.count)" : ""), status: "лежит на земле", art: GameScene.shared.itemPath(id: item.id)) {
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

    var body: some View {
        let p = person
        let ch = game.character
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
                ActionButton(label: "удар по \(p.name)", enabled: !busy && game.restSeconds == 0, action: { social.attackPlayer(p) }, icon: "attack", danger: p.attacking == "вас")
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
            Text("опыт \(ch.exp)/\(ch.expNext)").font(TzType.small).foregroundStyle(c.textMuted)
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
            ForEach(ch.skills.keys.sorted(), id: \.self) { k in
                HStack {
                    Text("\(Rules.shared.skillTitle(key: k)) \(ch.skills[k]?.intValue ?? 0)")
                    Spacer()
                    if k == "meditation" && !ch.ghost { Button("медитировать") { more.meditate() }.disabled(busy) }
                    Button("?") { more.look("skill." + k) }.disabled(busy)
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
    @Environment(\.tz) private var c

    var body: some View {
        switch sub {
        case 0:
            if let m = mapView {
                MapCanvas(points: m.points, here: game.character.location, flagAt: worldInfo?.flagLocationId).frame(height: 260)
                Text("красное — вы, жёлтое — флаг лидерства, бордовое — замки").font(TzType.small).foregroundStyle(c.textMuted)
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
            Button("Новости администрации") { layout.openNews() }.disabled(busy).buttonStyle(.borderedProminent)
            SectionTitle(text: "Летопись мира")
            Text("Здесь будет летопись последних дней: захваты замков, флаг, громкие убийства.").font(TzType.small).foregroundStyle(c.textMuted)
            Button("Об игре и правила") { layout.openPages() }.disabled(busy)
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
    @Environment(\.tz) private var c

    private var any: Bool {
        game.dialog != nil || game.shop != nil || game.bank != nil || game.craft != nil || game.exchange != nil ||
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
            Button("закрыть") { onCloseDialog() }
        }
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
