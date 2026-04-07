import SwiftUI

struct InviteView: View {
    let invite: ThreadViewModel.PendingInvite

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "questionmark.circle")
                    .foregroundStyle(.accentColor)
                Text(invite.invite)
                    .font(.subheadline)
            }
            if let def = invite.defaultValue, !def.isEmpty {
                Text("Default: \(def)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(Color.accentColor.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
