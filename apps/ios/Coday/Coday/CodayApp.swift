import SwiftUI

@main
struct CodayApp: App {
    @State private var appViewModel = AppViewModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(appViewModel)
        }
    }
}

struct RootView: View {
    @Environment(AppViewModel.self) private var appVM
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            ProjectListView()
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showSettings = true
                        } label: {
                            Image(systemName: "gear")
                        }
                        .accessibilityLabel("Settings")
                    }
                }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
    }
}
