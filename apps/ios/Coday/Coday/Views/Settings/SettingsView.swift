import SwiftUI

struct SettingsView: View {
    @State private var baseURL = AppConfig.baseURL
    @State private var username = AppConfig.username
    @State private var showSavedBanner = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Server URL") {
                        TextField("http://localhost:3000", text: $baseURL)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Username") {
                        TextField("user@example.com", text: $username)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .multilineTextAlignment(.trailing)
                    }
                } header: {
                    Text("Backend Connection")
                } footer: {
                    Text("The username is sent as x-forwarded-email to the Coday backend. In dev mode (no auth proxy) the server accepts any value.")
                }

                Section("About") {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                    LabeledContent("Build", value: Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1")
                    Link("GitHub Repository", destination: URL(string: "https://github.com/whoz-oss/coday")!)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .fontWeight(.semibold)
                }
            }
            .overlay(alignment: .bottom) {
                if showSavedBanner {
                    Text("Settings saved")
                        .font(.subheadline)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 10)
                        .background(.thinMaterial)
                        .clipShape(Capsule())
                        .padding(.bottom, 20)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
    }

    private func save() {
        AppConfig.baseURL = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        AppConfig.username = username.trimmingCharacters(in: .whitespacesAndNewlines)
        withAnimation { showSavedBanner = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation { showSavedBanner = false }
            dismiss()
        }
    }
}
