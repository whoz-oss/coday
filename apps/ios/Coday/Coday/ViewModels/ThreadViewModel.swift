import Foundation
import Observation

@MainActor
@Observable
class ThreadViewModel {
    var messages: [ChatMessage] = []
    var streamingText: String = ""
    var isThinking: Bool = false
    var connectionStatus: SSEConnectionStatus = .disconnected
    var error: String?

    var pendingChoice: PendingChoice?
    var pendingInvite: PendingInvite?
    var pendingOAuth: PendingOAuth?

    var subThreadEventBuffers: [String: [CodayEvent]] = [:]

    private let threadService = ThreadService()
    private let sseService = SSEService()
    private var projectName: String = ""
    private var threadId: String = ""
    private var accumulatedChunks = ""
    private var thinkingTask: Task<Void, Never>?
    private var seenMessageIds = Set<String>()
    private var pendingInviteParentKey: String?
    private var pendingChoiceParentKey: String?

    struct PendingChoice {
        let options: [String]
        let invite: String
        let optionalQuestion: String?
        let allowFreeText: Bool
        let parentKey: String?
    }

    struct PendingInvite {
        let invite: String
        let defaultValue: String?
        let parentKey: String?
    }

    struct PendingOAuth {
        let authUrl: String
        let state: String?
        let integrationName: String?
    }

    func connect(project: String, threadId: String) {
        self.projectName = project
        self.threadId = threadId
        reset()

        let endpoints = Endpoints(baseURL: AppConfig.baseURL)
        let url = endpoints.eventStream(project: project, threadId: threadId)

        sseService.onEvent = { [weak self] event in
            Task { @MainActor [weak self] in
                self?.handleEvent(event)
            }
        }
        sseService.connect(to: url)

        Task {
            await loadHistory()
        }
    }

    func disconnect() {
        sseService.disconnect()
        thinkingTask?.cancel()
    }

    func reset() {
        messages = []
        streamingText = ""
        accumulatedChunks = ""
        isThinking = false
        pendingChoice = nil
        pendingInvite = nil
        pendingOAuth = nil
        subThreadEventBuffers = [:]
        seenMessageIds = []
        error = nil
        thinkingTask?.cancel()
    }

    private func loadHistory() async {
        do {
            let rawEvents = try await threadService.getMessages(project: projectName, threadId: threadId)
            for raw in rawEvents {
                if let event = buildCodayEvent(from: raw) {
                    handleHistoricalEvent(event)
                }
            }
        } catch {
        }
    }

    private func handleHistoricalEvent(_ event: CodayEvent) {
        switch event {
        case .message(let role, let name, let content, let ts, let pk, _):
            addMessage(ChatMessage(
                id: ts, role: role, speaker: name, content: content,
                timestamp: event.date, type: .text,
                parentKey: pk
            ))
        case .text(let speaker, let text, let ts, _):
            addMessage(ChatMessage(
                id: ts, role: .system, speaker: speaker ?? "System",
                content: [.text(text)], timestamp: event.date, type: .text
            ))
        case .answer(let answer, let invite, let name, let ts, let pk, _):
            addMessage(ChatMessage(
                id: ts, role: .user, speaker: name ?? AppConfig.username,
                content: [.text(answer)], timestamp: event.date, type: .text,
                parentKey: pk, invite: invite
            ))
        case .toolRequest(let reqId, let toolName, let args, let ts, _):
            addMessage(ChatMessage(
                id: ts, role: .system, speaker: "Tool",
                content: [.text("\u{1F527} \(toolName)(\(args))")],
                timestamp: event.date, type: .technical, eventId: ts
            ))
        case .toolResponse(_, let toolName, let result, let ts, _):
            let truncated = result.count > 200 ? String(result.prefix(200)) + "..." : result
            addMessage(ChatMessage(
                id: ts + "-resp", role: .system, speaker: "Tool",
                content: [.text("\u{2705} \(toolName ?? "result"): \(truncated)")],
                timestamp: event.date, type: .technical
            ))
        case .delegation(let subId, let agentName, let ts, _):
            if !seenMessageIds.contains("delegation-\(subId)") {
                addMessage(ChatMessage(
                    id: ts, role: .system, speaker: agentName,
                    content: [.text("")], timestamp: event.date, type: .delegation,
                    subThreadId: subId, delegationAgentName: agentName
                ))
                seenMessageIds.insert("delegation-\(subId)")
            }
        case .warn(let warning, let ts, _):
            addMessage(ChatMessage(
                id: ts, role: .system, speaker: "Warning",
                content: [.text(warning)], timestamp: event.date, type: .warning
            ))
        case .error(let msg, let ts, _):
            addMessage(ChatMessage(
                id: ts, role: .system, speaker: "Error",
                content: [.text(msg)], timestamp: event.date, type: .error
            ))
        default:
            break
        }
    }

    func handleEvent(_ event: CodayEvent) {
        if let subId = event.threadId, subId != threadId {
            if subThreadEventBuffers[subId] == nil {
                subThreadEventBuffers[subId] = []
            }
            subThreadEventBuffers[subId]?.append(event)
            return
        }

        switch event {
        case .message(let role, let name, let content, let ts, let pk, _):
            if role == .assistant { clearStreaming() }
            stopThinking()
            addMessage(ChatMessage(
                id: ts, role: role, speaker: name, content: content,
                timestamp: event.date, type: .text, parentKey: pk
            ))

        case .textChunk(let chunk, _, _):
            accumulatedChunks += chunk
            streamingText = accumulatedChunks

        case .text(let speaker, let text, let ts, _):
            addMessage(ChatMessage(
                id: ts, role: .system, speaker: speaker ?? "System",
                content: [.text(text)], timestamp: event.date, type: .text
            ))

        case .answer(let answer, let invite, let name, let ts, let pk, _):
            addMessage(ChatMessage(
                id: ts, role: .user, speaker: name ?? AppConfig.username,
                content: [.text(answer)], timestamp: event.date, type: .text,
                parentKey: pk, invite: invite
            ))

        case .thinking:
            guard pendingChoice == nil && pendingInvite == nil else { return }
            startThinking()

        case .invite(let invite, let defaultVal, _, let pk, _):
            stopThinking()
            pendingInvite = PendingInvite(invite: invite, defaultValue: defaultVal, parentKey: pk)
            pendingChoice = nil

        case .choice(let options, let invite, let optQ, let allowFree, _, let pk, _):
            stopThinking()
            pendingChoice = PendingChoice(
                options: options, invite: invite, optionalQuestion: optQ,
                allowFreeText: allowFree, parentKey: pk
            )
            pendingInvite = nil

        case .delegation(let subId, let agentName, let ts, _):
            if !seenMessageIds.contains("delegation-\(subId)") {
                addMessage(ChatMessage(
                    id: ts, role: .system, speaker: agentName,
                    content: [.text("")], timestamp: event.date, type: .delegation,
                    subThreadId: subId, delegationAgentName: agentName
                ))
                seenMessageIds.insert("delegation-\(subId)")
            }

        case .threadUpdate(let name, _, _, _):
            break

        case .warn(let warning, let ts, _):
            addMessage(ChatMessage(
                id: ts, role: .system, speaker: "Warning",
                content: [.text(warning)], timestamp: event.date, type: .warning
            ))

        case .error(let msg, let ts, _):
            stopThinking()
            addMessage(ChatMessage(
                id: ts, role: .system, speaker: "Error",
                content: [.text(msg)], timestamp: event.date, type: .error
            ))

        case .oauthRequest(let authUrl, let state, let integrationName, _):
            pendingOAuth = PendingOAuth(authUrl: authUrl, state: state, integrationName: integrationName)

        case .heartbeat:
            break

        default:
            break
        }
    }

    func sendMessage(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        if let invite = pendingInvite {
            pendingInvite = nil
            do {
                try await threadService.sendAnswer(
                    project: projectName, threadId: threadId,
                    answer: trimmed, parentKey: invite.parentKey, invite: invite.invite
                )
            } catch {
                self.error = error.localizedDescription
            }
        } else {
            do {
                try await threadService.sendFreeMessage(
                    project: projectName, threadId: threadId, message: trimmed
                )
                isThinking = true
                startThinking()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    func sendChoice(_ choice: String) async {
        let parentKey = pendingChoice?.parentKey
        pendingChoice = nil
        do {
            try await threadService.sendChoice(
                project: projectName, threadId: threadId,
                choice: choice, parentKey: parentKey
            )
        } catch {
            self.error = error.localizedDescription
        }
    }

    func stopExecution() async {
        do {
            try await threadService.stopThread(project: projectName, threadId: threadId)
            stopThinking()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func deleteMessage(id: String) async {
        messages.removeAll { $0.id == id || ($0.id > id) }
        do {
            try await threadService.deleteMessage(
                project: projectName, threadId: threadId, messageId: id
            )
        } catch {
            self.error = error.localizedDescription
        }
    }

    func getBufferedSubThreadEvents(_ subThreadId: String) -> [CodayEvent] {
        subThreadEventBuffers[subThreadId] ?? []
    }

    private func addMessage(_ message: ChatMessage) {
        guard !seenMessageIds.contains(message.id) else { return }
        seenMessageIds.insert(message.id)
        messages.append(message)
    }

    private func clearStreaming() {
        accumulatedChunks = ""
        streamingText = ""
    }

    private func startThinking() {
        isThinking = true
        thinkingTask?.cancel()
        thinkingTask = Task {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard !Task.isCancelled else { return }
            isThinking = false
        }
    }

    private func stopThinking() {
        thinkingTask?.cancel()
        isThinking = false
    }
}