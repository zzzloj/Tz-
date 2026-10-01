import SwiftUI

@main
struct TzApp: App {
    /// "system", "night" or "parchment" (account page).
    @AppStorage("theme") private var theme = "system"

    var body: some Scene {
        WindowGroup {
            RootView().tzTheme()
                .preferredColorScheme(theme == "night" ? .dark : theme == "parchment" ? .light : nil)
        }
    }
}
