import SwiftUI
import Shared

/// Bridges the shared Kotlin Session to SwiftUI: after each call the
/// published copy is refreshed and the view redraws.
@MainActor
final class SessionModel: ObservableObject {
    let session: Session
    @Published private(set) var version = 0

    init() {
        let url = Bundle.main.object(forInfoDictionaryKey: "ServerURL") as? String ?? "http://localhost:8080"
        session = Session(api: GameApi(baseUrl: url, client: nil), tokens: KeychainTokens())
    }

    func run(_ action: @escaping (Session) async throws -> Void) {
        Task {
            version += 1
            try? await action(session)
            version += 1
        }
    }

    /// Blows, deaths and arrivals come from the server over a WebSocket; redraw on each.
    func listen() {
        Task {
            try? await session.listen(onUpdate: { [weak self] in
                Task { @MainActor in self?.version += 1 }
            })
        }
    }
}

struct RootView: View {
    @StateObject private var model = SessionModel()
    @State private var recovering = false

    private func siteActions() -> SiteActions {
        let model = self.model
        let admin: (() -> Void)? = model.session.moderator ? { model.run { s in s.closeAccount(); try await s.openAdmin() } } : nil
        return SiteActions(
            closeAccount: { model.run { $0.closeAccount() } },
            changePassword: { o, n in model.run { try await $0.changePassword(old: o, newPassword: n) } },
            makeRecovery: { p in model.run { try await $0.makeRecoveryCode(password: p) } },
            setAbout: { t in model.run { try await $0.setAbout(text: t) } },
            deleteAccount: { p in model.run { try await $0.deleteAccount(password: p) } },
            openSection: { sec in model.run { try await $0.openSection(section: sec, page: 0) } },
            openTopic: { t in model.run { try await $0.openTopic(topic: t, page: 0) } },
            forumPage: { n in model.run { try await $0.forumPage(page: Int32(n)) } },
            forumBack: { model.run { try await $0.forumBack() } },
            closeForum: { model.run { $0.closeForum() } },
            newTopic: { t, x in model.run { try await $0.startTopic(title: t, text: x) } },
            reply: { x in model.run { try await $0.reply(text: x) } },
            editPost: { p, x in model.run { try await $0.editPost(post: p, text: x) } },
            moderateTopic: { op in model.run { try await $0.moderateTopic(op: op) } },
            renameTopic: { t in model.run { try await $0.renameTopic(title: t) } },
            deletePost: { p in model.run { try await $0.deletePost(post: p) } },
            openPage: { p in model.run { try await $0.openPage(summary: p) } },
            closePage: { model.run { $0.closePage() } },
            closePages: { model.run { $0.closePages() } },
            adminOp: { op, target, text, item, count, minutes in
                model.run { try await $0.adminOp(op: op, target: target, text: text, item: item, count: Int32(count), minutes: Int32(minutes)) }
            },
            closeAdmin: { model.run { $0.closeAdmin() } },
            search: { q in model.run { try await $0.searchForum(query: q) } },
            openHit: { h in model.run { try await $0.openHit(hit: h) } },
            openUnread: { t in model.run { try await $0.openUnread(topic: t) } },
            follow: { on in model.run { try await $0.followTopic(on: on) } },
            openAdmin: admin,
            signOut: { model.run { s in s.closeAccount(); try await s.signOut() } })
    }

    /// Account, moderation, forum and pages, drawn in the game's style.
    private func sitePage(_ s: Session) -> AnyView? {
        if let a = s.account { return AnyView(AccountView_(account: a, info: s.info, busy: s.busy, act: siteActions())) }
        if let ad = s.admin { return AnyView(AdminView_(admin: ad, busy: s.busy, act: siteActions())) }
        if let f = s.forum { return AnyView(ForumView_(forum: f, busy: s.busy, act: siteActions())) }
        if let pg = s.pages { return AnyView(PagesView_(pages: pg, page: s.page, busy: s.busy, act: siteActions())) }
        return nil
    }

    var body: some View {
        let _ = model.version   // redraw after every session call
        let s = model.session
        Group {
            if s.screen == Screen.playing, let game = s.game {
                playing(s, game)
            } else if let page = sitePage(s) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        page
                        if let e = s.error { Text(e).foregroundStyle(.red) }
                    }.padding()
                }
                .overlay { if s.busy { ProgressView() } }
            } else {
                forms(s)
            }
        }
        .task {
            model.run { try await $0.resume() }
            model.listen()
        }
    }

    private func playing(_ s: Session, _ game: GameView) -> some View {
        let model = self.model
        let api = s.api
        let admin: (() -> Void)? = s.moderator ? { model.run { try await $0.openAdmin() } } : nil
        return GameScreen(game: game, busy: s.busy,
                                onGo: { exit in model.run { try await $0.go(exit: exit) } },
                                onTake: { item in model.run { try await $0.take(item: item, count: nil) } },
                                onDrop: { item in model.run { try await $0.drop(item: item, count: nil) } },
                                onToggleEquip: { item in model.run { try await $0.toggleEquip(item: item) } },
                                onAttack: { npc in model.run { try await $0.attack(npc: npc) } },
                                onLoot: { corpse, item in model.run { try await $0.loot(corpse: corpse, item: item) } },
                                onButcher: { corpse in model.run { try await $0.butcher(corpse: corpse) } },
                                onResurrect: { model.run { try await $0.resurrect() } },
                                onTalk: { npc in model.run { try await $0.talk(npc: npc) } },
                                onAnswer: { option in model.run { try await $0.answer(option: option) } },
                                onCloseDialog: { model.run { $0.closeDialog() } },
                                pending: s.pendingUse,
                                more: MoreActions(
                                    trade: { item, n in model.run { try await $0.trade(item: item, count: Int32(n)) } },
                                    bankPut: { item, n in model.run { try await $0.bankPut(item: item, count: Int32(n)) } },
                                    bankTake: { item, n in model.run { try await $0.bankTake(item: item, count: Int32(n)) } },
                                    use: { item in model.run { try await $0.use(item: item) } },
                                    useOn: { target in model.run { try await $0.useOn(target: target) } },
                                    cancelUse: { model.run { $0.cancelUse() } },
                                    craft: { option in model.run { try await $0.craft(option: option) } },
                                    useAbility: { a in model.run { try await $0.useAbility(ability: a) } },
                                    aimAbility: { t in model.run { try await $0.aimAbility(target: t) } },
                                    cancelAbility: { model.run { $0.cancelAbility() } },
                                    meditate: { model.run { try await $0.meditate() } },
                                    peek: { t in model.run { try await $0.peek(target: t) } },
                                    steal: { i in model.run { try await $0.steal(item: i) } },
                                    closePeek: { model.run { $0.closePeek() } },
                                    look: { t in model.run { try await $0.look(target: t) } },
                                    closeLook: { model.run { $0.closeLook() } },
                                    dropOne: { i in model.run { try await $0.drop(item: i, count: KotlinInt(int: 1)) } },
                                    takeOne: { i in model.run { try await $0.take(item: i, count: KotlinInt(int: 1)) } },
                                    dismount: { model.run { try await $0.dismount() } },
                                    tame: { n in model.run { try await $0.tame(npc: n) } },
                                    raise: { c in model.run { try await $0.raise(corpse: c) } },
                                    gallop: { e in model.run { try await $0.gallop(exit: e) } },
                                    openWorld: { model.run { try await $0.openWorld() } },
                                    closeWorld: { model.run { $0.closeWorld() } },
                                    openMap: { model.run { try await $0.openMap() } },
                                    closeMap: { model.run { $0.closeMap() } },
                                    stele: { model.run { try await $0.stele() } },
                                    dropFlag: { model.run { try await $0.dropFlag() } },
                                    openSite: { page in model.run { session in if page == "news" { try await session.openNews() } else { try await session.openPages() } } }),
                                pendingAbility: s.pendingAbility,
                                social: SocialActions(
                                    answerText: { t in model.run { try await $0.answerText(text: t) } },
                                    say: { t, clan in model.run { try await $0.say(text: t, clan: clan) } },
                                    openMail: { model.run { try await $0.openMail() } },
                                    closeMail: { model.run { $0.closeMail() } },
                                    write: { to, t in model.run { try await $0.write(to: to, text: t) } },
                                    addContact: { n in model.run { try await $0.addContact(name: n) } },
                                    removeContact: { n in model.run { try await $0.removeContact(name: n) } },
                                    startExchange: { p in model.run { try await $0.startExchange(person: p) } },
                                    attackPlayer: { p in model.run { try await $0.attackPlayer(person: p) } },
                                    offer: { item, n in model.run { try await $0.offer(item: item, count: Int32(n)) } },
                                    withdraw: { item in model.run { try await $0.withdraw(item: item) } },
                                    agree: { model.run { try await $0.agree() } },
                                    cancelExchange: { model.run { try await $0.cancelExchange() } },
                                    openClan: { model.run { try await $0.openClan() } },
                                    closeClan: { model.run { $0.closeClan() } },
                                    clanOp: { op, name, rank, clan in model.run { try await $0.clanOp(op: op, name: name, rank: rank, clan: clan, text: nil) } },
                                    castleOp: { op, text in model.run { try await $0.castleOp(op: op, text: text) } },
                                    choose: { o in model.run { try await $0.choose(option: o) } },
                                    closeChoice: { model.run { $0.closeChoice() } },
                                    writeAll: { t in model.run { try await $0.writeAll(text: t) } }),
                                layout: LayoutActions(
                                    strike: { a, t in model.run { try await $0.strike(ability: a, target: t) } },
                                    setSlot: { i, id in model.run { try await $0.setSlot(index: Int32(i), abilityId: id) } },
                                    setBelt: { i, id in model.run { try await $0.setBelt(index: Int32(i), itemId: id) } },
                                    useBelt: { i in model.run { try await $0.useBelt(index: Int32(i)) } },
                                    openForum: { model.run { try await $0.openForum() } },
                                    openNews: { model.run { try await $0.openNews() } },
                                    openPages: { model.run { try await $0.openPages() } },
                                    openAccount: { model.run { try await $0.openAccount() } },
                                    openAdmin: admin,
                                    openEvents: { model.run { try await $0.openEvents() } },
                                    openTopic: { t in model.run { try await $0.openTopic(topic: t, page: 0) } }),
                                mail: s.mail,
                                clanInfo: s.clanInfo,
                                worldInfo: s.world,
                                mapView: s.mapOpen ? s.map : nil,
                                page: sitePage(s),
                                onClosePage: { model.run { s in s.closeAccount(); s.closeAdmin(); s.closeForum(); s.closePages() } },
                                chronicle: s.chronicle,
                                news: s.news,
                                info: s.info,
                                error: s.error,
                                onRefresh: { model.run { try await $0.refresh() } },
                                onSignOut: { model.run { try await $0.signOut() } })
            .environment(\.artUrl, { path in api.artUrl(path: path) })
    }

    private func forms(_ s: Session) -> some View {
        NavigationStack {
            Form {
                if s.screen == Screen.signIn && recovering {
                    RecoverView(busy: s.busy,
                                onRecover: { l, c, p in
                                    model.run { session in
                                        try await session.recover(login: l, code: c, newPassword: p)
                                        if session.error == nil { await MainActor.run { recovering = false } }
                                    }
                                },
                                onCancel: { recovering = false })
                } else if s.screen == Screen.signIn {
                    SignInView(busy: s.busy,
                               onSignIn: { l, p in model.run { try await $0.signIn(login: l, password: p) } },
                               onRegister: { l, p in model.run { try await $0.register(login: l, password: p) } })
                    Section {
                        Button("Забыли пароль?") { recovering = true }
                        Button("Форум") { model.run { try await $0.openForum() } }.disabled(s.busy)
                        Button("Об игре") { model.run { try await $0.openPages() } }.disabled(s.busy)
                    }
                } else if s.screen == Screen.createCharacter {
                    CreateCharacterView(busy: s.busy) { name, female in
                        model.run { try await $0.createCharacter(name: name, female: female) }
                    }
                } else if s.screen == Screen.playing {
                    ProgressView()
                } else if s.error != nil {
                    Button("Повторить") { model.run { try await $0.refresh() } }
                }
                if s.account == nil, let info = s.info {
                    Text(info).foregroundStyle(Color.accentColor)
                }
                if let error = s.error {
                    Text(error).foregroundStyle(.red)
                }
            }
            .navigationTitle(s.screen == Screen.signIn || s.screen == Screen.createCharacter ? "" : "Территория Зла")
            .overlay { if s.busy { ProgressView() } }
        }
        .environment(\.artUrl, { [api = s.api] path in api.artUrl(path: path) })
    }
}

/// The splash picture over the sign-in forms.
struct SplashHeader: View {
    var height: CGFloat = 280
    @Environment(\.tz) private var c

    var body: some View {
        ZStack(alignment: .bottom) {
            ArtImage(path: "/art/brand/splash-portrait.webp")
            LinearGradient(colors: [.clear, c.background], startPoint: .top, endPoint: .bottom)
            Text("Территория Зла").font(TzType.title).foregroundStyle(c.title).padding(.bottom, 8)
        }
        .frame(height: height)
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
    }
}

struct SignInView: View {
    let busy: Bool
    let onSignIn: (String, String) -> Void
    let onRegister: (String, String) -> Void
    @State private var registering = false
    @State private var login = ""
    @State private var password = ""
    @State private var again = ""
    @Environment(\.tz) private var c

    var body: some View {
        Section { SplashHeader() }
        Section(registering ? "Регистрация" : "Вход") {
            TextField("Логин", text: $login)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("Пароль", text: $password)
            if registering {
                SecureField("Пароль ещё раз", text: $again)
                if !again.isEmpty && again != password { Text("Пароли не совпадают").font(TzType.small).foregroundStyle(c.danger) }
            }
        }
        Section {
            if registering {
                Text("После входа сохраните код восстановления (Персонаж → Аккаунт): по нему вернёте пароль, почту игра не спрашивает.").font(TzType.small).foregroundStyle(c.textMuted)
                Button("Создать аккаунт") { onRegister(login, password) }.disabled(busy || login.isEmpty || password.isEmpty || again != password)
                Button("Уже есть аккаунт? Войти") { registering = false }
            } else {
                Button("Войти") { onSignIn(login, password) }.disabled(busy)
                Button("Регистрация") { registering = true }
            }
        }
    }
}

struct CreateCharacterView: View {
    let busy: Bool
    let onCreate: (String, Bool) -> Void
    @State private var name = ""
    @State private var female = false
    @Environment(\.tz) private var c

    var body: some View {
        Section { SplashHeader(height: 200) }
        Section("Новый персонаж") {
            TextField("Имя", text: $name)
            Text("Имя — русскими буквами, его увидят все. Сменить его потом нельзя.").font(TzType.small).foregroundStyle(c.textMuted)
            Picker("Пол", selection: $female) {
                Text("Мужской").tag(false)
                Text("Женский").tag(true)
            }
            .pickerStyle(.segmented)
            Text("Вы начнёте в Переулке у городских ворот. Привратник Уин расскажет, с чего начать, а Эдвард вручит подарок новичку.")
            Button("Создать") { onCreate(name, female) }.disabled(busy || name.isEmpty)
        }
    }
}

/// Trade, bank, crafting and item use, grouped to keep GameScreen readable.
struct MoreActions {
    let trade: (ShopItemView, Int) -> Void
    let bankPut: (InventoryItemView, Int) -> Void
    let bankTake: (InventoryItemView, Int) -> Void
    let use: (InventoryItemView) -> Void
    let useOn: (String) -> Void
    let cancelUse: () -> Void
    let craft: (CraftOptionView) -> Void
    let useAbility: (AbilityView) -> Void
    let aimAbility: (String) -> Void
    let cancelAbility: () -> Void
    let meditate: () -> Void
    let peek: (String) -> Void
    let steal: (PeekItem) -> Void
    let closePeek: () -> Void
    let look: (String) -> Void
    let closeLook: () -> Void
    let dropOne: (InventoryItemView) -> Void
    let takeOne: (GroundItemView) -> Void
    let dismount: () -> Void
    let tame: (NpcView) -> Void
    let raise: (CorpseView) -> Void
    let gallop: (ExitView) -> Void
    let openWorld: () -> Void
    let closeWorld: () -> Void
    let openMap: () -> Void
    let closeMap: () -> Void
    let stele: () -> Void
    let dropFlag: () -> Void
    /// A notice board or a bookshelf: "news" or "pages".
    let openSite: (String) -> Void
}

struct SocialActions {
    let answerText: (String) -> Void
    let say: (String, Bool) -> Void
    let openMail: () -> Void
    let closeMail: () -> Void
    let write: (String, String) -> Void
    let addContact: (String) -> Void
    let removeContact: (String) -> Void
    let startExchange: (PersonView) -> Void
    let attackPlayer: (PersonView) -> Void
    let offer: (InventoryItemView, Int) -> Void
    let withdraw: (ShopItemView) -> Void
    let agree: () -> Void
    let cancelExchange: () -> Void
    let openClan: () -> Void
    let closeClan: () -> Void
    let clanOp: (String, String?, String?, String?) -> Void
    let castleOp: (String, String?) -> Void
    let choose: (ChoiceOption) -> Void
    let closeChoice: () -> Void
    let writeAll: (String) -> Void
}

struct MapCanvas: View {
    let points: [MapPoint]
    let here: String
    let flagAt: String?

    /// (x, y, map) of a location: m.php's pins and regions, from the shared rules.
    static func point(_ loc: String) -> (CGFloat, CGFloat, Int)? {
        guard let t = Rules.shared.mapPoint(loc: loc),
              let x = t.first as? KotlinInt, let y = t.second as? KotlinInt, let r = t.third as? KotlinInt else { return nil }
        return (CGFloat(x.doubleValue), CGFloat(y.doubleValue), Int(r.int32Value))
    }

    var body: some View {
        let me = MapCanvas.point(here)
        let region = me?.2 ?? 0
        var dots: [(CGFloat, CGFloat, Bool)] = []
        for point in points {
            let px = CGFloat(Double(point.mapX))
            let py = CGFloat(Double(point.mapY))
            let r = py > 1101 ? 2 : (px > 1650 ? 1 : 0)
            if r == region { dots.append((px, py, point.guarded)) }
        }
        let castles = ["c.1.gate", "c.2.gate", "c.3.gate", "c.4.gate"].compactMap { MapCanvas.point($0) }.filter { $0.2 == region }
        let flag = flagAt.flatMap { MapCanvas.point($0) }.flatMap { $0.2 == region ? $0 : nil }
        return Canvas { ctx, size in
            guard let minX = dots.map({ $0.0 }).min(), let maxX = dots.map({ $0.0 }).max(),
                  let minY = dots.map({ $0.1 }).min(), let maxY = dots.map({ $0.1 }).max() else { return }
            let k = min(size.width / max(maxX - minX + 1, 1), size.height / max(maxY - minY + 1, 1))
            let d = min(max(k * 6, 2), 6)
            func dot(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ c: Color) {
                let cx = (x - minX) * k, cy = (y - minY) * k
                ctx.fill(Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)), with: .color(c))
            }
            for p in dots { dot(p.0, p.1, d / 2, p.2 ? Color.accentColor : Color.gray.opacity(0.4)) }
            for c in castles { dot(c.0, c.1, d * 1.5, Color(red: 0.7, green: 0.2, blue: 0.2)) }
            if let f = flag { dot(f.0, f.1, d * 1.5, .yellow) }
            if let m = me { dot(m.0, m.1, d * 2, .red) }
        }
    }
}
