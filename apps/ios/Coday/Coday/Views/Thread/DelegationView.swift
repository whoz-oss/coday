import SwiftUI

/// Collapsible card showing a delegated sub-thread inline.
struct DelegationView: View {
    let subThreadId: String
    let agentName: String

    @State private var isExpanded = false
    @State private var subMessages: [ChatMessage] = []
    @State private var subStreaming = ""
    @State private var isLoading = false
    @State private var isLoaded = false

    private let threadService = ThreadService()

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header button
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isExpanded.toggle()
                }
                if isExpanded && !isLoaded {
                    loadMessages()
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "arrow.triangle.branch")
                        .foregroundStyle(.blue)
                    Text(agentName)
                        .font(.subheadline.bold())
                        .foregroundStyle(.blue)
                    Spacer()
                    if isLoading {
                        ProgressView().scaleEffect(0.7)
                    } else {
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .foregroundStyle(.secondary)
                            .font(.caption)
                    }
                }
                .padding(10)
                .background(Color.messageDelegation)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)

            // Expanded sub-thread content
            if isExpanded {
                VStack(alignment: .leading, spacing: 8) {
                    if subMessages.isEmpty && !isLoading {
                        Text("No messages")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.leading, 12)
                    }
                    ForEach(subMessages) { msg in
                        HStack(alignment: .top, spacing: 8) {
                            Rectangle()
                                .fill(Color.blue.opacity(0.3))
                                .frame(width: 2)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(msg.speaker)
                                    .font(.caption.bold())
                                    .foregroundStyle(.secondary)
                                MarkdownText(content: msg.plainText, font: .caption)
                            }
                        }
                    }
                    if !subStreaming.isEmpty {
                        HStack(alignment: .top, spacing: 8) {
                            Rectangle().fill(Color.blue.opacity(0.3)).frame(width: 2)
                            MarkdownText(content: subStreaming, font: .caption)
                        }
                    }
                }
                .padding(.leading, 12)
                .padding(.vertical, 8)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .accessibilityLabel("Delegation to \(agentName)")
    }

    private func loadMessages() {
        guard !isLoaded else { return }
        isLoading = true
        let project = UserDefaults.standard.string(forKey: "coday_current_project") ?? ""
        guard !project.isEmpty else {
            isLoading = false
            isLoaded = true
            return
        }
        Task {
            do {
                let rawEvents = try await threadService.getMessages(project: project, threadId: subThreadId)
                var seen = Set<String>()
                for raw in rawEvents {
                    if let event = buildCodayEvent(from: raw) {
                        if let msg = eventToChatMessage(event), !seen.contains(msg.id) {
                            seen.insert(msg.id)
                            await MainActor.run { subMessages.append(msg) }
                        }
                    }
                }
            } catch {
                // Non-fatal
            }
            await MainActor.run {
                isLoading = false
                isLoaded = true
            }
        }
    }

    private func eventToChatMessage(_ event: CodayEvent) -> ChatMessage? {
        switch event {
        case .message(let role, let name, let content, let ts, let pk, _):
            return ChatMessage(id: ts, role: role, speaker: name, content: content,
                               timestamp: event.date, type: .text, parentKey: pk)
        case .answer(let ans, let invite, let name, let ts, _, _):
            return ChatMessage(id: ts, role: .user, speaker: name ?? "User",
                               content: [.text(ans)], timestamp: event.date, type: .text, invite: invite)
        case .text(let speaker, let text, let ts, _):
            return ChatMessage(id: ts, role: .system, speaker: speaker ?? "System",
                               content: [.text(text)], timestamp: event.date, type: .text)
        case .toolRequest(_, let toolName, let args, let ts, _):
            return ChatMessage(id: ts, role: .system, speaker: "Tool",
                               content: [.text("\u{1F527} \(toolName)(\(args))")],
                               timestamp: event.date, type: .technical)
        default: return nil
        }
    }
}
