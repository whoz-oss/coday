import Foundation

enum MessageRole: String, Codable {
    case user
    case assistant
    case system
}

enum MessageType {
    case text
    case error
    case warning
    case technical
    case delegation
}

enum MessageContentItem: Codable {
    case text(String)
    case image(base64: String, mimeType: String, source: String?)

    enum CodingKeys: String, CodingKey {
        case type, content, mimeType, source
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type_ = try container.decode(String.self, forKey: .type)
        switch type_ {
        case "image":
            let b64 = try container.decode(String.self, forKey: .content)
            let mime = try container.decodeIfPresent(String.self, forKey: .mimeType) ?? "image/jpeg"
            let src = try container.decodeIfPresent(String.self, forKey: .source)
            self = .image(base64: b64, mimeType: mime, source: src)
        default:
            let text = try container.decode(String.self, forKey: .content)
            self = .text(text)
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .text(let s):
            try container.encode("text", forKey: .type)
            try container.encode(s, forKey: .content)
        case .image(let b64, let mime, let src):
            try container.encode("image", forKey: .type)
            try container.encode(b64, forKey: .content)
            try container.encode(mime, forKey: .mimeType)
            try container.encodeIfPresent(src, forKey: .source)
        }
    }
}

struct ChatMessage: Identifiable {
    let id: String
    let role: MessageRole
    let speaker: String
    let content: [MessageContentItem]
    let timestamp: Date
    let type: MessageType
    var eventId: String?
    var parentKey: String?
    var invite: String?
    var subThreadId: String?
    var delegationAgentName: String?

    var plainText: String {
        content.compactMap {
            if case .text(let s) = $0 { return s }
            return nil
        }.joined(separator: "\n")
    }
}
