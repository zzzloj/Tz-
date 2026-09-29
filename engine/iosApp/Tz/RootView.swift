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
                                onTake: { item in model.run { try await $0.take(item: item) } },
                                onDrop: { item in model.run { try await $0.drop(item: item) } },
                                onToggleEquip: { item in model.run { try await $0.toggleEquip(item: item) } },
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
        .task { model.run { try await $0.resume() } }
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
    let onRefresh: () -> Void
    let onSignOut: () -> Void

    private func label(_ name: String, _ count: Int32) -> String {
        count > 1 ? "\(name) ×\(count)" : name
    }

    var body: some View {
        let c = game.character
        let loc = game.location
        Section {
            Text("\(c.name) · HP \(c.hp)/\(c.hpMax) · мана \(c.mana)/\(c.manaMax)").font(.footnote)
            if let d = loc.description_ { Text(d) }
            if !loc.npcs.isEmpty {
                Text("Здесь: " + loc.npcs.map { $0.name }.joined(separator: ", "))
            }
            if !loc.players.isEmpty {
                Text("Игроки: " + loc.players.joined(separator: ", "))
            }
            ForEach(loc.items, id: \.id) { item in
                HStack {
                    Text(label(item.name, item.count))
                    Spacer()
                    if item.takeable {
                        Button("взять") { onTake(item) }.disabled(busy).buttonStyle(.borderless)
                    }
                }
            }
        }
        Section("Выходы") {
            ForEach(loc.exits, id: \.target) { exit in
                Button(exit.label) { onGo(exit) }.disabled(busy)
            }
            Button("осмотреться") { onRefresh() }.disabled(busy)
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
                    Button("бросить") { onDrop(item) }.disabled(busy).buttonStyle(.borderless)
                }
            }
        }
        Section {
            Button("Выйти", role: .destructive) { onSignOut() }
        }
    }
}
