import Foundation

class ProjectService {
    private let api = APIClient.shared
    private var endpoints: Endpoints { Endpoints(baseURL: AppConfig.baseURL) }

    func listProjects() async throws -> ProjectListResponse {
        try await api.get(endpoints.projects)
    }

    func createProject(name: String, path: String) async throws -> ProjectDetails {
        struct Body: Encodable { let name: String; let path: String }
        return try await api.post(endpoints.projects, body: Body(name: name, path: path))
    }
}
