import Foundation

// MARK: - Base Protocol

protocol CodayEventProtocol {
    var type: String { get }
    var timestamp: String { get }
    var parentKey: String? { get }
    var threadId: String? { get }
}

// MARK: - Raw envelope for decoding

struct RawCodayEvent: Codable {
    let type: String
    let timestamp: String?
    let parentKey: String?
    let threadId: String?

    // MessageEvent
    let role: String?
    let name: String?
    let content: [MessageContentItem]?

    // TextChunkEvent
    let chunk: String?

    // TextEvent
    let speaker: String?
    let text: String?

    // AnswerEvent
    let answer: String?
    let invite: String?

    // ChoiceEvent
    let options: [String]?
    let optionalQuestion: String?
    let allowFreeText: Bool?

    // InviteEvent
    let defaultValue: String?

    // ToolRequestEvent
    let toolRequestId: String?
    let toolName: String?
    let args: AnyCodable?

    // ToolResponseEvent
    let result: AnyCodable?
    let output: AnyCodable?

    // DelegationEvent
    let subThreadId: String?
    let agentName: String?

    // ThreadUpdateEvent
    let summary: String?

    // FileEvent
    let filename: String?
    let operation: String?
    let size: Int?
    let mimeType: String?

    // ErrorEvent
    let error: AnyCodable?

    // WarnEvent
    let warning: String?

    // OAuthRequestEvent
    let authUrl: String?
    let state: String?
    let integrationName: String?

    // OAuthCallbackEvent
    let code: String?
}

// MARK: - Typed Events

enum CodayEvent {
    case message(role: MessageRole, name: String, content: [MessageContentItem], timestamp: String, parentKey: String?, threadId: String?)
    case textChunk(chunk: String, timestamp: String, threadId: String?)
    case text(speaker: String?, text: String, timestamp: String, threadId: String?)
    case answer(answer: String, invite: String?, name: String?, timestamp: String, parentKey: String?, threadId: String?)
    case choice(options: [String], invite: String, optionalQuestion: String?, allowFreeText: Bool, timestamp: String, parentKey: String?, threadId: String?)
    case invite(invite: String, defaultValue: String?, timestamp: String, parentKey: String?, threadId: String?)
    case toolRequest(toolRequestId: String?, toolName: String, args: String, timestamp: String, threadId: String?)
    case toolResponse(toolRequestId: String?, toolName: String?, result: String, timestamp: String, threadId: String?)
    case thinking(timestamp: String, threadId: String?)
    case delegation(subThreadId: String, agentName: String, timestamp: String, threadId: String?)
    case threadUpdate(name: String?, summary: String?, timestamp: String, threadId: String?)
    case fileEvent(filename: String, operation: String, timestamp: String, threadId: String?)
    case error(message: String, timestamp: String, threadId: String?)
    case warn(warning: String, timestamp: String, threadId: String?)
    case heartbeat(timestamp: String)
    case oauthRequest(authUrl: String, state: String?, integrationName: String?, timestamp: String)
    case oauthCallback(code: String?, state: String?, integrationName: String?, timestamp: String)
    case summary(summary: String, timestamp: String, threadId: String?)
    case unknown(type: String, timestamp: String)

    var timestamp: String {
        switch self {
        case .message(_, _, _, let t, _, _): return t
        case .textChunk(_, let t, _): return t
        case .text(_, _, let t, _): return t
        case .answer(_, _, _, let t, _, _): return t
        case .choice(_, _, _, _, let t, _, _): return t
        case .invite(_, _, let t, _, _): return t
        case .toolRequest(_, _, _, let t, _): return t
        case .toolResponse(_, _, _, let t, _): return t
        case .thinking(let t, _): return t
        case .delegation(_, _, let t, _): return t
        case .threadUpdate(_, _, let t, _): return t
        case .fileEvent(_, _, let t, _): return t
        case .error(_, let t, _): return t
        case .warn(_, let t, _): return t
        case .heartbeat(let t): return t
        case .oauthRequest(_, _, _, let t): return t
        case .oauthCallback(_, _, _, let t): return t
        case .summary(_, let t, _): return t
        case .unknown(_, let t): return t
        }
    }

    var threadId: String? {
        switch self {
        case .message(_, _, _, _, _, let tid): return tid
        case .textChunk(_, _, let tid): return tid
        case .text(_, _, _, let tid): return tid
        case .answer(_, _, _, _, _, let tid): return tid
        case .choice(_, _, _, _, _, _, let tid): return tid
        case .invite(_, _, _, _, let tid): return tid
        case .toolRequest(_, _, _, _, let tid): return tid
        case .toolResponse(_, _, _, _, let tid): return tid
        case .thinking(_, let tid): return tid
        case .delegation(_, _, _, let tid): return tid
        case .threadUpdate(_, _, _, let tid): return tid
        case .fileEvent(_, _, _, let tid): return tid
        case .error(_, _, let tid): return tid
        case .warn(_, _, let tid): return tid
        case .heartbeat: return nil
        case .oauthRequest: return nil
        case .oauthCallback: return nil
        case .summary(_, _, let tid): return tid
        case .unknown: return nil
        }
    }

    var date: Date {
        let ts = timestamp
        let clean: String
        if ts.count > 6, let lastHyphen = ts.lastIndex(of: "-") {
            let suffix = ts[lastHyphen...]
            if suffix.count == 6 {
                clean = String(ts[ts.startIndex..<lastHyphen])
            } else {
                clean = ts
            }
        } else {
            clean = ts
        }
        return ISO8601DateFormatter().date(from: clean) ?? Date()
    }
}

// MARK: - Factory

func buildCodayEvent(from raw: RawCodayEvent) -> CodayEvent? {
    let ts = raw.timestamp ?? ISO8601DateFormatter().string(from: Date())
    switch raw.type {
    case "message":
        guard let roleStr = raw.role,
              let role = MessageRole(rawValue: roleStr) else { return nil }
        return .message(
            role: role,
            name: raw.name ?? roleStr,
            content: raw.content ?? [],
            timestamp: ts,
            parentKey: raw.parentKey,
            threadId: raw.threadId
        )
    case "text-chunk":
        return .textChunk(chunk: raw.chunk ?? "", timestamp: ts, threadId: raw.threadId)
    case "text":
        return .text(speaker: raw.speaker, text: raw.text ?? "", timestamp: ts, threadId: raw.threadId)
    case "answer":
        return .answer(
            answer: raw.answer ?? "",
            invite: raw.invite,
            name: raw.name,
            timestamp: ts,
            parentKey: raw.parentKey,
            threadId: raw.threadId
        )
    case "choice":
        return .choice(
            options: raw.options ?? [],
            invite: raw.invite ?? "",
            optionalQuestion: raw.optionalQuestion,
            allowFreeText: raw.allowFreeText ?? false,
            timestamp: ts,
            parentKey: raw.parentKey,
            threadId: raw.threadId
        )
    case "invite":
        return .invite(
            invite: raw.invite ?? "",
            defaultValue: raw.defaultValue,
            timestamp: ts,
            parentKey: raw.parentKey,
            threadId: raw.threadId
        )
    case "tool-request":
        let argsStr = raw.args.map { String(describing: $0.value) } ?? ""
        return .toolRequest(
            toolRequestId: raw.toolRequestId,
            toolName: raw.toolName ?? raw.name ?? "tool",
            args: argsStr,
            timestamp: ts,
            threadId: raw.threadId
        )
    case "tool-response":
        let resultStr = raw.result.map { String(describing: $0.value) }
            ?? raw.output.map { String(describing: $0.value) }
            ?? ""
        return .toolResponse(
            toolRequestId: raw.toolRequestId,
            toolName: raw.toolName ?? raw.name,
            result: resultStr,
            timestamp: ts,
            threadId: raw.threadId
        )
    case "thinking":
        return .thinking(timestamp: ts, threadId: raw.threadId)
    case "delegation":
        guard let subId = raw.subThreadId, let agent = raw.agentName else { return nil }
        return .delegation(subThreadId: subId, agentName: agent, timestamp: ts, threadId: raw.threadId)
    case "thread-update":
        return .threadUpdate(name: raw.name, summary: raw.summary, timestamp: ts, threadId: raw.threadId)
    case "file":
        return .fileEvent(
            filename: raw.filename ?? "",
            operation: raw.operation ?? "",
            timestamp: ts,
            threadId: raw.threadId
        )
    case "error":
        let msg = raw.error.map { String(describing: $0.value) } ?? "Unknown error"
        return .error(message: msg, timestamp: ts, threadId: raw.threadId)
    case "warn":
        return .warn(warning: raw.warning ?? "", timestamp: ts, threadId: raw.threadId)
    case "heartbeat":
        return .heartbeat(timestamp: ts)
    case "oauth-request":
        return .oauthRequest(
            authUrl: raw.authUrl ?? "",
            state: raw.state,
            integrationName: raw.integrationName,
            timestamp: ts
        )
    case "oauth-callback":
        return .oauthCallback(
            code: raw.code,
            state: raw.state,
            integrationName: raw.integrationName,
            timestamp: ts
        )
    case "summary":
        return .summary(summary: raw.summary ?? "", timestamp: ts, threadId: raw.threadId)
    default:
        return .unknown(type: raw.type, timestamp: ts)
    }
}

// MARK: - AnyCodable helper

struct AnyCodable: Codable {
    let value: Any

    init(_ value: Any) { self.value = value }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let i = try? container.decode(Int.self) { value = i }
        else if let d = try? container.decode(Double.self) { value = d }
        else if let b = try? container.decode(Bool.self) { value = b }
        else if let s = try? container.decode(String.self) { value = s }
        else if let arr = try? container.decode([AnyCodable].self) { value = arr.map { $0.value } }
        else if let dict = try? container.decode([String: AnyCodable].self) {
            value = dict.mapValues { $0.value }
        } else {
            value = "<undecodable>"
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch value {
        case let i as Int: try container.encode(i)
        case let d as Double: try container.encode(d)
        case let b as Bool: try container.encode(b)
        case let s as String: try container.encode(s)
        default: try container.encode("<unencodable>")
        }
    }
}
