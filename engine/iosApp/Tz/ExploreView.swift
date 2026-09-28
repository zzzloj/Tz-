import SwiftUI
import Shared

/// Walk the world: the same screen logic as Android (shared Explorer).
@MainActor
final class ExploreModel: ObservableObject {
    private let explorer: Explorer
    @Published var location: LocationView?
    @Published var error: String?
    @Published var loading = false

    init() {
        let url = Bundle.main.object(forInfoDictionaryKey: "ServerURL") as? String ?? "http://localhost:8080"
        explorer = Explorer(api: GameApi(baseUrl: url, client: nil))
    }

    func start() async { await run { try await self.explorer.start() } }

    func go(_ exit: ExitView) async { await run { try await self.explorer.go(exit: exit) } }

    private func run(_ action: @escaping () async throws -> Void) async {
        loading = true
        do { try await action() } catch { self.error = error.localizedDescription }
        location = explorer.location
        if let e = explorer.error { self.error = e }
        loading = false
    }
}

struct ExploreView: View {
    @StateObject private var model = ExploreModel()

    var body: some View {
        NavigationStack {
            List {
                if let loc = model.location {
                    Section {
                        if let d = loc.description_ { Text(d) }
                        if !loc.npcs.isEmpty {
                            Text("Здесь: " + loc.npcs.map { $0.name }.joined(separator: ", "))
                        }
                    }
                    Section("Выходы") {
                        ForEach(loc.exits, id: \.target) { exit in
                            Button(exit.label) { Task { await model.go(exit) } }
                                .disabled(model.loading)
                        }
                    }
                }
                if let e = model.error {
                    Section {
                        Text("Нет связи с сервером: \(e)").foregroundStyle(.red)
                        Button("Повторить") { Task { await model.start() } }
                    }
                }
            }
            .navigationTitle(model.location?.name ?? "Территория Зла")
            .overlay { if model.loading { ProgressView() } }
            .task { await model.start() }
        }
    }
}
