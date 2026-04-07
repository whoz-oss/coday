import Foundation

struct ThreadUser: Codable, Hashable {
    let userId: String
}

struct ThreadSummary: Codable, Identifiable, Hashable {
    let id: String
    let username: String?
    let projectId: String
    let name: String
    let summary: String?
    let createdDate: String
    let modifiedDate: String
    let price: Double?
    let starring: [String]?
    let users: [ThreadUser]?
    let parentThreadId: String?
    let delegatedAgentName: String?
    let delegatedTask: String?

    var isStarred: Bool {
        starring?.contains(AppConfig.username) ?? false
    }

    var modifiedAt: Date {
        ISO8601DateFormatter().date(from: modifiedDate) ?? Date()
    }
}

struct ThreadDetails: Codable, Identifiable {
    let id: String
    let username: String?
    let projectId: String
    let name: String
    let summary: String?
    let createdDate: String
    let modifiedDate: String
    let pricimport Foundation

struct ThreadUser: Codable, Hashable {
    let userId: String
}

struct ThreadSummary:et
struct ThreadUsame    let userId: String
}

struct Threng}

struct ThreadSummat: In    let id: String
    let username: String?
    let p i    let username: n    let projectId: Strinhr    let name: String
   le    let summary: St B    let createdDate: Stre    let modifiedDate: StridS    let price: Double?
    le    let starring: [Stle    let users: [ThreadUser
S    let pacat > /Users/m1/Desktop/coday__feat-ios-native-app/apps/ios/Coday/Coday/Models/ChatMessage.swift << 'SWIFT_EOF'
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
            let src = try container.decodeIfPresent(String.self, forKey: .sourcimport Foundation

enum MessageRole: String, Codable {
    case user
    case assistant
    case system
}

enu t
enum MessageRolode    case user
    case assistant
       case ass =    case system
}  }

enum Messag func    case text
   r:    case errro    case warnva    case techninc    case delegatiye}

enum MessageConlf)
     case text(String)
    case im .    case image(base6  
    enum CodingKeys: String, CodingKey {
        case type, conont        case type, content, mimeType, s      }

    init(from decoder: Decoder) thro  
              let container = try decoder.con .        let type_ = try container.decode(String.self, forKey: .type)
           switch type_ {
        case "image":
            let b64 = ta        case "image":sr            let b64               let mime = try container.decodeIfPresent(String.self, forKey              let src = try container.decodeIfPresent(String.self, forKey: .sourcimport Foundation

ees
enum MessageRole: String, Codable {
    case user
    case assistant
    case system
}

enu t
//     case user
    case assistant
  S    case assar    case system
}  }

enu t
enum dId: enumng    case assistant
       case S       case ass =la}  }

enum Messag func    case t.
enpac   r:    case errro    case te
enum MessageConlf)
     case text(String)
    case im .    case imagepar     case text(St
}    case im cat > /Users/m1/Desktop/coday__feat-ios-native-app/apps/ios/Coday/Coday/Models/TokenUsage.swift << 'SWIFT_EOF'
import Foundation

struct ModelUsageSummaryDto: Codable, Identifiable {
    var id: String { "\(agentName)-\(providerName)-\(modelId)" }
    let agentName: String
    let providerName: String
    let modelId: String
    let promptTokens: Int?
    let completionTokens: Int?
    let totalTokens: Int?
    let callCount: Int
    let cost: Double
}

struct TokenUsageAggregationDto: Codable {
    let models: [ModelUsageSummaryDto]
    let tokenDataPartial: Bool
}

struct TimeSeriesPointDto: Codable {
    let date: String
    let agentName: String
    let providerName: String
    let modelId: String
    let promptTokens: Int?
    let completionTokens: Int?
    let totalTokens: Int?
    let callCount: Int
    let cost: Double
}

struct TokenUsageSeriesDto: Codable {
    let points: [TimeSeriesPointDto]
    let tokenDataPartial: Bool
}
