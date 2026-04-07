import SwiftUI

struct ChatMessageView: View {
    let message: ChatMessage
    var onDelete: (() -> Void)?

    @State private var showDeleteConfirm = false
    @State private var expandedImage: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Speaker + timestamp header
            HStack(spacing: 6) {
                Image(systemName: roleIcon)
                    .font(.caption)
                    .foregroundStyle(roleColor)
                Text(message.speaker)
                    .font(.caption.bold())
                    .foregroundStyle(roleColor)
                Spacer()
                Text(message.timestamp.chatTimestamp())
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }

            // Content bubble
            Group {
                switch message.type {
                case .delegation:
                    if let subId = message.subThreadId {
                        DelegationView(
                            subThreadId: subId,
                            agentName: message.delegationAgentName ?? message.speaker
                        )
                    }
                default:
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(message.content.enumerated()), id: \.offset) { _, item in
                            switch item {
                            case .text(let text):
                                MarkdownText(content: text)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            case .image(let base64, let mimeType, let source):
                                if let uiImage = decodeImage(base64: base64) {
                                    Image(uiImage: uiImage)
                                        .resizable()
                                        .scaledToFit()
                                        .frame(maxHeight: 300)
                                        .clipShape(RoundedRectangle(cornerRadius: 8))
                                        .onTapGesture { expandedImage = base64 }
                                        .accessibilityLabel(source ?? "Image")
                                }
                            }
                        }
                    }
                    .padding(10)
                    .background(bubbleColor)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }
        }
        .contextMenu {
            Button {
                let text = message.plainText
                UIPasteboard.general.string = text
            } label: {
                Label("Copy", systemImage: "doc.on.doc")
            }
            if onDelete != nil {
                Divider()
                Button(role: .destructive) {
                    showDeleteConfirm = true
                } label: {
                    Label("Delete from here", systemImage: "trash")
                }
            }
        }
        .confirmationDialog(
            "Delete this message and all following?",
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) { onDelete?() }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(item: Binding(
            get: { expandedImage.map { ExpandableImage(base64: $0) } },
            set: { expandedImage = $0?.base64 }
        )) { img in
            if let uiImage = decodeImage(base64: img.base64) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .ignoresSafeArea()
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(message.speaker): \(message.plainText)")
    }

    private var roleIcon: String {
        switch message.role {
        case .user: return "person.circle"
        case .assistant: return "cpu"
        case .system: return "info.circle"
        }
    }

    private var roleColor: Color {
        switch message.type {
        case .error: return .red
        case .warning: return .orange
        case .technical: return .secondary
        case .delegation: return .blue
        case .text:
            switch message.role {
            case .user: return .accentColor
            case .assistant: return .primary
            case .system: return .secondary
            }
        }
    }

    private var bubbleColor: Color {
        switch message.type {
        case .error: return .messageError
        case .warning: return .messageWarning
        case .technical: return .messageTechnical
        case .delegation: return .messageDelegation
        case .text:
            return message.role == .user ? Color.accentColor.opacity(0.15) : .messageBubbleAssistant
        }
    }

    private func decodeImage(base64: String) -> UIImage? {
        guard let data = Data(base64Encoded: base64) else { return nil }
        return UIImage(data: data)
    }
}

private struct ExpandableImage: Identifiable {
    let id = UUID()
    let base64: String
}
