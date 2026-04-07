import SwiftUI

struct OAuthRequestView: View {
    let oauth: ThreadViewModel.PendingOAuth
    var onDismiss: () -> Void

    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "lock.shield.fill")
                    .foregroundStyle(.blue)
                    .font(.title3)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Authorization Required")
                        .font(.subheadline.bold())
                    if let name = oauth.integrationName {
                        Text(name)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                        .font(.title3)
                }
                .accessibilityLabel("Dismiss")
            }

            Text("Tap the button below to authorize. Return to Coday after completing authorization.")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack(spacing: 8) {
                Link(destination: URL(string: oauth.authUrl) ?? URL(string: "about:blank")!) {
                    Label("Open Authorization Page", systemImage: "safari")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    UIPasteboard.general.string = oauth.authUrl
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(copied ? "Copied" : "Copy URL")
            }
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.blue.opacity(0.3), lineWidth: 1)
        )
    }
}
