import Foundation

struct ProjectInfo: Codable, Identifiable, Hashable {
    var id: String { name }
    let name: String
    let volatile: Bool?

    var isVolatile: Bool { volatile ?? false }
}

struct ProjectListResponse: Codable {
    let projects: [ProjectInfo]
    let defaultProject: String?
    let forcedProject: String?
}

struct ProjectDetails: Codable, Identifiable {
    var id: String { name }
    let name: String
    // config is untyped in the backend
}

struct CreateProjectRequest: Codable {
    let name: String
    let path: String
}
