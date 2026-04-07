import SwiftUI

struct SettingsView: View {
    @State private var baseURL = AppConfig.baseURL
    @State private var username = AppConfig.username
    @State private var savedFeedback = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Label("Server URL", systemImage: "server.rack")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextField("http://localhost:3000", text: $baseURL)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                    }
                } header: {
                    Text("Backend")
                } footer: {
                    Text("Base URL of your Coday Express server, e.g. http://localhost:3000")
                                   Section {                                   Section {  g, sp                                   Section {        /                                   Section                  .font(.caption)
                                          le                                           el                        t:                                          le                le                                          le                                                     .keyboardType(.emai                                          le           r: {
                    Text("Identity")
                } footer: {
                                                            U                                                            U  
                                               St                                                                                                                le                                               St                                                                                            St                                                                                                                le                                               St                                                           St                                                                                                                le                                               St                                                                                            St                                                                                  ss                                       ToolbarItem(placement: .confirmationAction) {
                    Button(savedFeedback ? "Saved!" : "Save") {
                        AppConfig.baseURL = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
                        AppConfig.username = username.trimmingCharacters(in: .whitespacesAndNewlines)
                        savedFeedback = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                            dismiss()
                        }
                    }
                    .disabled(baseURL.isBlank || username.isBlank)
                }
            }
        }
    }
}
