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

    var body: some View {
        let _ = model.version   // redraw after every session call
        let s = model.session
        NavigationStack {
            Form {
                if s.screen == Screen.signIn {
                    SignInView(busy: s.busy,
                               onSignIn: { l, p in model.run { try await $0.signIn(login: l, password: p) } },
                               onRegister: { l, p in model.run { try await $0.register(login: l, password: p) } })
                } else if s.screen == Screen.createCharacter {
                    CreateCharacterView(busy: s.busy) { name, female in
                        model.run { try await $0.createCharacter(name: name, female: female) }
                    }
                } else if s.screen == Screen.playing, let game = s.game {
                    PlayingView(game: game, busy: s.busy,
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
                                    gallop: { e in model.run { try await $0.gallop(exit: e) } }),
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
                                    closeChoice: { model.run { $0.closeChoice() } }),
                                mail: s.mail,
                                clanInfo: s.clanInfo,
                                onRefresh: { model.run { try await $0.refresh() } },
                                onSignOut: { model.run { try await $0.signOut() } })
                } else if s.error != nil {
                    Button("Повторить") { model.run { try await $0.refresh() } }
                }
                if let error = s.error {
                    Text(error).foregroundStyle(.red)
                }
            }
            .navigationTitle(s.game?.location.name ?? "Территория Зла")
            .overlay { if s.busy { ProgressView() } }
        }
        .task {
            model.run { try await $0.resume() }
            model.listen()
        }
    }
}

struct SignInView: View {
    let busy: Bool
    let onSignIn: (String, String) -> Void
    let onRegister: (String, String) -> Void
    @State private var login = ""
    @State private var password = ""

    var body: some View {
        Section {
            TextField("Логин", text: $login)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("Пароль", text: $password)
        }
        Section {
            Button("Войти") { onSignIn(login, password) }.disabled(busy)
            Button("Регистрация") { onRegister(login, password) }.disabled(busy)
        }
    }
}

struct CreateCharacterView: View {
    let busy: Bool
    let onCreate: (String, Bool) -> Void
    @State private var name = ""
    @State private var female = false

    var body: some View {
        Section("Новый персонаж") {
            TextField("Имя", text: $name)
            Picker("Пол", selection: $female) {
                Text("Мужской").tag(false)
                Text("Женский").tag(true)
            }
            .pickerStyle(.segmented)
            Button("Создать") { onCreate(name, female) }.disabled(busy)
        }
    }
}

struct PlayingView: View {
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
    let mail: MessagesView?
    let clanInfo: ClanView?
    @State private var typed = ""
    @State private var speech = ""
    @State private var mailTo: String?
    @State private var mailText = ""
    let onRefresh: () -> Void
    let onSignOut: () -> Void

    private func stats(_ c: CharacterView) -> String {
        var s = "удар \(c.hit)% · урон \(c.dmgMin)–\(c.dmgMax) · броня \(c.armor) · уклон \(c.dodge) · опыт \(c.exp)/\(c.expNext)"
        if c.skillPoints > 0 { s += " · очков \(c.skillPoints)" }
        if game.restSeconds > 0 { s += " · отдых \(game.restSeconds) с" }
        return s
    }

    private func personLine(_ p: PersonView) -> String {
        var s = p.name
        if let clan = p.clan { s += " *\(clan)*" }
        if let crime = p.crime { s += " [\(crime)]" }
        if let f = p.faction { s += " \(f)" }
        if let hp = p.hpPercent { s += " \(hp.intValue)%" }
        if let a = p.attacking { s += " · атакует \(a)" }
        if p.rider { s += " (всадник)" }
        if p.ghost { s += " (призрак)" }
        return s
    }

    private func crimeLine(_ c: CharacterView) -> String? {
        guard let crime = c.crime else { return nil }
        return "Вы \(crime) — стража ищет вас ещё \(c.crimeMinutes) мин"
    }

    private func canTame(_ npc: NpcView) -> Bool {
        let c = game.character
        return !c.ghost && !npc.mine && npc.owner == nil && npc.id.hasPrefix("n.a.") && (c.skills["animaltaming"]?.intValue ?? 0) > 0
    }

    private func npcLine(_ npc: NpcView) -> String {
        var s = npc.name
        if let o = npc.owner { s += npc.mine ? " (ваш)" : " (\(o))" }
        if npc.attackable { s += " · HP \(npc.hp)/\(npc.hpMax)" }
        if let a = npc.attacking { s += " · атакует \(a)" }
        return s
    }

    private func isThief(_ c: CharacterView) -> Bool {
        !c.ghost && ((c.skills["steal"]?.intValue ?? 0) > 0 || (c.skills["steallook"]?.intValue ?? 0) > 0)
    }

    private func label(_ name: String, _ count: Int32) -> String {
        count > 1 ? "\(name) ×\(count)" : name
    }

    var body: some View {
        let c = game.character
        let loc = game.location
        if let d = game.dialog {
            Section(d.npcName) {
                Text(d.text)
                if d.inputTopic != nil {
                    TextField("Ответ", text: $typed)
                    Button("ответить") { social.answerText(typed); typed = "" }.disabled(busy || typed.isEmpty)
                }
                ForEach(Array(d.options.enumerated()), id: \.offset) { _, o in
                    Button(o.label) { onAnswer(o) }.disabled(busy)
                }
                Button(d.options.isEmpty ? "[Конец диалога]" : "закончить разговор") { onCloseDialog() }
            }
        }
        Section {
            Text("\(c.name) · HP \(c.hp)/\(c.hpMax) · мана \(c.mana)/\(c.manaMax)").font(.footnote)
            Text(stats(c)).font(.caption).foregroundStyle(.secondary)
            if let line = crimeLine(c) {
                Text(line).font(.caption).foregroundStyle(.red)
            }
            if c.poisoned { Text("Вы отравлены: здоровье убывает").font(.caption).foregroundStyle(.red) }
            if c.mounted { Button("Вы верхом — спешиться") { more.dismount() }.disabled(busy) }
            if !c.skills.isEmpty {
                Text("навыки: " + c.skills.keys.sorted().map { "\(Rules.shared.skillTitle(key: $0)) \(c.skills[$0]?.intValue ?? 0)" }.joined(separator: ", "))
                    .font(.caption).foregroundStyle(.secondary)
            }
            if c.ghost {
                Text("Вы призрак. Воскреснуть можно у камня воскрешения или у лекаря Джозефа (двор к северу от Переулка).")
                    .foregroundStyle(.red)
                if game.canResurrect {
                    Button("Воскреснуть") { onResurrect() }.disabled(busy)
                }
            }
            if let d = loc.description_ { Text(d) }
            if let cs = game.castle {
                Text((cs.owner.map { "Замок принадлежит клану \($0)" } ?? "Замок никому не принадлежит: первый член клана, вошедший в ворота, захватит его")
                     + (cs.lockedMinutes > 0 ? " · ворота заперты ещё \(cs.lockedMinutes) мин." : "")
                     + (cs.guest ? " · вы гость" : "")).font(.footnote)
                if !cs.sign.isEmpty { Text("Надпись на воротах: \(cs.sign)").font(.footnote) }
                if cs.canKnock { Button("Постучать") { social.castleOp("knock", nil) }.disabled(busy) }
                if cs.canOpen { Button("Открыть ворота") { social.castleOp("open", nil) }.disabled(busy) }
                if cs.member {
                    TextField("Вывеска", text: $speech)
                    Button("Сохранить вывеску") { social.castleOp("sign", speech); speech = "" }.disabled(busy || speech.isEmpty)
                }
            }
            ForEach(loc.npcs, id: \.id) { npc in
                HStack {
                    Text(npcLine(npc))
                        .foregroundStyle(npc.fightingYou ? .red : .primary)
                    Spacer()
                    if npc.canTalk {
                        Button("говорить") { onTalk(npc) }.disabled(busy).buttonStyle(.borderless)
                    }
                    Button("?") { more.look(npc.id) }.disabled(busy).buttonStyle(.borderless)
                    if canTame(npc) { Button("приручить") { more.tame(npc) }.disabled(busy).buttonStyle(.borderless) }
                    if isThief(c) {
                        Button("подглядеть") { more.peek(npc.id) }.disabled(busy).buttonStyle(.borderless)
                    }
                    if npc.attackable && !c.ghost {
                        Button("атаковать") { onAttack(npc) }.disabled(busy).buttonStyle(.borderless)
                    }
                }
            }
            ForEach(game.people, id: \.name) { person in
                HStack {
                    Text(personLine(person))
                        .foregroundStyle(person.crime != nil ? Color.red : Color.primary)
                    Spacer()
                    if !c.ghost && !person.ghost {
                        Button("атаковать") { social.attackPlayer(person) }.disabled(busy).buttonStyle(.borderless)
                        if isThief(c) { Button("подглядеть") { more.peek(person.name) }.disabled(busy).buttonStyle(.borderless) }
                        Button("обмен") { social.startExchange(person) }.disabled(busy).buttonStyle(.borderless)
                    }
                    Button("в контакты") { social.addContact(person.name) }.disabled(busy).buttonStyle(.borderless)
                    if game.clan != nil && person.clan == nil {
                        Button("в клан") { social.clanOp("invite", person.name, nil, nil) }.disabled(busy).buttonStyle(.borderless)
                    }
                }
            }
            ForEach(loc.items, id: \.id) { item in
                HStack {
                    Text(label(item.name, item.count))
                    Spacer()
                    if item.takeable {
                        Button(item.id.hasPrefix("i.s.") ? "использовать" : "взять") { onTake(item) }.disabled(busy).buttonStyle(.borderless)
                        if item.count > 1 { Button("1") { more.takeOne(item) }.disabled(busy).buttonStyle(.borderless) }
                    }
                }
            }
        }
        ForEach(loc.corpses, id: \.id) { corpse in
            Section(corpse.name) {
                if corpse.looting && !corpse.items.isEmpty {
                    Text("взять отсюда — мародёрство").font(.caption).foregroundStyle(.red)
                }
                ForEach(corpse.items, id: \.id) { item in
                    HStack {
                        Text(label(item.name, item.count))
                        Spacer()
                        if !c.ghost {
                            Button("взять") { onLoot(corpse, item) }.disabled(busy).buttonStyle(.borderless)
                        }
                    }
                }
                if corpse.canRaise && !c.ghost && (c.skills["necro"]?.intValue ?? 0) > 0 {
                    Button("поднять") { more.raise(corpse) }.disabled(busy)
                }
                if corpse.canButcher && !c.ghost {
                    Button("разделать") { onButcher(corpse) }.disabled(busy)
                }
            }
        }
        Section("Выходы") {
            ForEach(loc.exits, id: \.target) { exit in
                HStack {
                    Button(exit.label + (exit.occupied ? " !" : "")) { onGo(exit) }.disabled(busy).buttonStyle(.borderless)
                    if exit.gallop { Spacer(); Button("галопом") { more.gallop(exit) }.disabled(busy).buttonStyle(.borderless) }
                }
            }
            Button("осмотреться") { onRefresh() }.disabled(busy)
        }
        if !game.journal.isEmpty {
            Section("Журнал") {
                ForEach(Array(game.journal.suffix(10).enumerated()), id: \.offset) { _, line in
                    Text(line).font(.footnote)
                }
            }
        }
        Section {
            HStack {
                Button(game.unread > 0 ? "Почта (\(game.unread))" : "Почта") { social.openMail() }.disabled(busy).buttonStyle(.borderless)
                Spacer()
                Button((game.clan.map { "Клан \($0)" } ?? "Клан") + (game.clanInvites.isEmpty ? "" : " (приглашение)")) { social.openClan() }
                    .disabled(busy).buttonStyle(.borderless)
            }
        }
        if let m = mail {
            Section("Почта") {
                ForEach(m.contacts, id: \.name) { ct in
                    HStack {
                        Text(ct.name + (ct.online ? " • в игре" : "") + (ct.mutual ? "" : " (вы не у него в контактах)"))
                        Spacer()
                        Button("написать") { mailTo = ct.name }.buttonStyle(.borderless)
                        Button("убрать") { social.removeContact(ct.name) }.disabled(busy).buttonStyle(.borderless)
                    }
                }
                if let to = mailTo {
                    TextField("Сообщение для \(to)", text: $mailText)
                    Button("Отправить") { social.write(to, mailText); mailText = ""; mailTo = nil }.disabled(busy || mailText.isEmpty)
                }
                ForEach(Array(m.messages.enumerated()), id: \.offset) { _, msg in
                    Text((msg.clan ? "[клан] " : "") + "\(msg.from): \(msg.text)").font(.footnote)
                        .foregroundStyle(msg.read ? .primary : Color.accentColor)
                }
                Button("закрыть") { social.closeMail() }
            }
        }
        if let cl = clanInfo {
            Section(cl.name.map { "Клан \($0)" } ?? "Клан") {
                if let msg = cl.message { Text(msg).font(.footnote) }
                if cl.name == nil {
                    Text("Вы не в клане. Создать клан можно у Мирандера на центральной площади.").font(.footnote)
                } else {
                    Text("Ваш ранг: \(Rules.shared.CLAN_RANKS[cl.rank ?? ""] ?? cl.rank ?? "")").font(.footnote)
                    if !cl.info.isEmpty { Text(cl.info).font(.footnote) }
                    ForEach(cl.members, id: \.name) { mem in
                        HStack {
                            Text("\(mem.name) — \(Rules.shared.CLAN_RANKS[mem.rank] ?? mem.rank)" + (mem.online ? " • в игре" : "")).font(.footnote)
                            Spacer()
                            if cl.canManage && cl.rank == "head" && mem.rank != "head" {
                                let next = mem.rank == "neophyte" ? "vassal" : (mem.rank == "vassal" ? "seneschal" : "neophyte")
                                Button("→ \(Rules.shared.CLAN_RANKS[next] ?? next)") { social.clanOp("rank", mem.name, next, nil) }.disabled(busy).buttonStyle(.borderless)
                                Button("выгнать") { social.clanOp("kick", mem.name, nil, nil) }.disabled(busy).buttonStyle(.borderless)
                            }
                        }
                    }
                    Button(cl.rank == "head" ? "Распустить клан" : "Выйти из клана") { social.clanOp("leave", nil, nil, nil) }.disabled(busy)
                }
                ForEach(cl.invites, id: \.self) { inv in
                    HStack {
                        Text("Приглашение в клан \(inv)").font(.footnote)
                        Spacer()
                        Button("вступить") { social.clanOp("accept", nil, nil, inv) }.disabled(busy).buttonStyle(.borderless)
                        Button("отказать") { social.clanOp("decline", nil, nil, inv) }.disabled(busy).buttonStyle(.borderless)
                    }
                }
                Button("закрыть") { social.closeClan() }
            }
        }
        if let ex = game.exchange {
            Section("Обмен с \(ex.partner)" + (ex.waiting ? " (ждём его)" : "")) {
                Text("Вы отдаёте:" + (ex.iAgree ? " ✓ согласны" : "")).font(.footnote)
                ForEach(ex.mine, id: \.id) { item in
                    HStack {
                        Text("\(item.name) ×\(item.count)")
                        Spacer()
                        Button("убрать") { social.withdraw(item) }.disabled(busy).buttonStyle(.borderless)
                    }
                }
                Text("\(ex.partner) отдаёт:" + (ex.theyAgree ? " ✓ согласен" : "")).font(.footnote)
                ForEach(ex.theirs, id: \.id) { item in Text("\(item.name) ×\(item.count)") }
                ForEach(game.inventory.filter { inv in !inv.equipped && !ex.mine.contains { $0.id == inv.id } }, id: \.id) { item in
                    Button("+ \(item.name) ×\(item.count)") { social.offer(item, Int(item.count)) }.disabled(busy)
                }
                Button("Согласен") { social.agree() }.disabled(busy || ex.iAgree || ex.waiting)
                Button("Отменить обмен", role: .destructive) { social.cancelExchange() }
            }
        }
        if let shop = game.shop {
            Section(shop.npcName + (shop.mode == "sell" ? " покупает" : " продаёт")) {
                if let m = shop.message { Text(m) }
                ForEach(shop.items, id: \.id) { item in
                    HStack {
                        Text("\(item.name)\(item.count > 1 ? " (\(item.count))" : "") — \(item.price) \(shop.currency)")
                        Spacer()
                        Button(shop.mode == "sell" ? "продать" : "купить") { more.trade(item, 1) }.disabled(busy).buttonStyle(.borderless)
                        if item.count > 1 {
                            Button("все") { more.trade(item, Int(item.count)) }.disabled(busy).buttonStyle(.borderless)
                        }
                    }
                }
                Button("закрыть") { onCloseDialog() }
            }
        }
        if let bank = game.bank {
            Section("Банк · \(bank.npcName)" + (bank.fee > 0 ? " (плата \(bank.fee))" : "")) {
                if let m = bank.message { Text(m) }
                if bank.items.isEmpty { Text("в ячейке пусто").foregroundStyle(.secondary) }
                ForEach(bank.items, id: \.id) { item in
                    HStack {
                        Text(label(item.name, item.count))
                        Spacer()
                        Button("забрать") { more.bankTake(item, 1) }.disabled(busy).buttonStyle(.borderless)
                        if item.count > 1 {
                            Button("все") { more.bankTake(item, Int(item.count)) }.disabled(busy).buttonStyle(.borderless)
                        }
                    }
                }
                ForEach(game.inventory.filter { !$0.equipped }, id: \.id) { item in
                    HStack {
                        Text(label(item.name, item.count)).foregroundStyle(.secondary)
                        Spacer()
                        Button("в банк") { more.bankPut(item, Int(item.count)) }.disabled(busy).buttonStyle(.borderless)
                    }
                }
                Button("закрыть") { onCloseDialog() }
            }
        }
        if let craft = game.craft {
            Section(craft.title) {
                ForEach(Array(craft.options.enumerated()), id: \.offset) { _, o in
                    Button("\(o.name) — \(o.chance)% (\(o.needs))") { more.craft(o) }.disabled(busy)
                }
                Button("закрыть") { onCloseDialog() }
            }
        }
        if let p = pending {
            Section("Применить «\(p.name)» к…") {
                ForEach(Targets.shared.choices(kind: p.target, game: game, exceptItem: p.id), id: \.value) { t in
                    Button(t.label) { more.useOn(t.value) }.disabled(busy)
                }
                Button("отмена") { more.cancelUse() }
            }
        }
        MagicSection(game: game, busy: busy, pending: pendingAbility, more: more)
        CharacterSection(game: game, busy: busy, more: more)
        if let ch = game.choice {
            Section(ch.title) {
                ForEach(ch.options, id: \.value) { o in Button(o.label) { social.choose(o) }.disabled(busy) }
                Button("отмена") { social.closeChoice() }
            }
        }
        Section("Сказать") {
            TextField("Текст", text: $speech)
            HStack {
                Button("всем") { social.say(speech, false); speech = "" }.disabled(busy || speech.isEmpty).buttonStyle(.borderless)
                if game.clan != nil {
                    Spacer()
                    Button("клану") { social.say(speech, true); speech = "" }.disabled(busy || speech.isEmpty).buttonStyle(.borderless)
                }
            }
        }
        Section("Инвентарь") {
            if game.inventory.isEmpty { Text("пусто").foregroundStyle(.secondary) }
            ForEach(game.inventory, id: \.id) { item in
                HStack {
                    Text(label(item.name, item.count) + (item.equipped ? " (надето)" : ""))
                    Spacer()
                    if item.equippable {
                        Button(item.equipped ? "снять" : "надеть") { onToggleEquip(item) }
                            .disabled(busy).buttonStyle(.borderless)
                    }
                    if item.usable && !game.character.ghost {
                        Button("исп.") { more.use(item) }.disabled(busy).buttonStyle(.borderless)
                    }
                    Button("?") { more.look(item.id) }.disabled(busy).buttonStyle(.borderless)
                    if item.count > 1 { Button("−1") { more.dropOne(item) }.disabled(busy).buttonStyle(.borderless) }
                    Button("бросить") { onDrop(item) }.disabled(busy).buttonStyle(.borderless)
                }
            }
        }
        Section {
            Button("Выйти", role: .destructive) { onSignOut() }
        }
    }
}

/// Trade, bank, crafting and item use, grouped to keep PlayingView readable.
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
}

/// Spells, techniques and stances learnt, and the «на кого?» list for one that needs a target.
struct MagicSection: View {
    let game: GameView
    let busy: Bool
    let pending: AbilityView?
    let more: MoreActions

    private func line(_ a: AbilityView) -> String {
        var s = a.name
        if a.manaCost > 0 { s += " · мана \(a.manaCost)" }
        if a.readyIn > 0 { s += " · через \((a.readyIn + 59) / 60) мин" }
        if a.later { s += " · позже" }
        return s
    }

    private func verb(_ a: AbilityView) -> String {
        switch a.kind {
        case "spell": return "читать"
        case "stance": return "встать"
        default: return "ударить"
        }
    }

    var body: some View {
        if let a = pending {
            Section("«\(a.name)» — на кого?") {
                ForEach(Targets.shared.choices(kind: a.target, game: game, exceptItem: nil), id: \.value) { t in
                    Button(t.label) { more.aimAbility(t.value) }.disabled(busy)
                }
                Button("отмена") { more.cancelAbility() }
            }
        }
        if !game.abilities.isEmpty && !game.character.ghost {
            Section("Магия и приёмы") {
                if let stance = game.stance { Text("Стойка: \(stance)").font(.caption) }
                ForEach(game.abilities, id: \.id) { a in
                    HStack {
                        Text(line(a)).font(.footnote)
                        Spacer()
                        Button("?") { more.look(a.id) }.disabled(busy).buttonStyle(.borderless)
                        Button(verb(a)) { more.useAbility(a) }
                            .disabled(busy || a.readyIn > 0 || a.later).buttonStyle(.borderless)
                    }
                }
            }
        }
    }
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
}

/// What was looked at or peeked into, and the character's own page: rank, full parameters, skills with help.
struct CharacterSection: View {
    let game: GameView
    let busy: Bool
    let more: MoreActions
    @State private var open = false

    var body: some View {
        if let l = game.look {
            Section(l.title) {
                Text(l.text).font(.footnote)
                Button("закрыть") { more.closeLook() }
            }
        }
        if let pk = game.peek {
            Section("Рюкзак: \(pk.targetName)") {
                ForEach(pk.items, id: \.id) { it in
                    HStack {
                        Text(it.name + (it.count > 1 ? " ×\(it.count)" : "") + (it.equipped ? " (надето)" : "")).font(.footnote)
                        Spacer()
                        Button("украсть") { more.steal(it) }.disabled(busy).buttonStyle(.borderless)
                    }
                }
                Button("закрыть") { more.closePeek() }
            }
        }
        Section {
            Button(open ? "Персонаж ▲" : "Персонаж ▼") { open.toggle() }
            if open {
                let c = game.character
                Text("\(c.rank) \(c.title)").font(.footnote)
                Text("парирование \(c.parry) · уклон от магии \(c.magicDodge) · защита от магии \(c.magicParry) · сопр. магии \(c.magicResist)").font(.caption)
                ForEach(c.skills.keys.sorted(), id: \.self) { k in
                    HStack {
                        Text("\(Rules.shared.skillTitle(key: k)) \(c.skills[k]?.intValue ?? 0)").font(.footnote)
                        Spacer()
                        if k == "meditation" && !c.ghost { Button("медитировать") { more.meditate() }.disabled(busy).buttonStyle(.borderless) }
                        Button("?") { more.look("skill." + k) }.disabled(busy).buttonStyle(.borderless)
                    }
                }
            }
        }
    }
}
