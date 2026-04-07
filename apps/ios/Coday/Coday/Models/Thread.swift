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
    let price: Double?
    let starring: [String]?
    let users: [ThreadUser]?
    let parentThreadId: String?
    let delegatedAgentName: String?
    let delegatedTask: String?
}

struct ThreadCreationResponse: Codable {
    let id: String
    let name: String?
    let projectId: String?
}

struct ThreadUpdateResponse: Codable {
    let id: String
    let name: String?
}

struct ThreadStarResponse: Codable {
    let success: Bool?
    let starring: [String]?
}
