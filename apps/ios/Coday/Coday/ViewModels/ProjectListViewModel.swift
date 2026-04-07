import Foundation

@MainActor
@Observable
class ProjectListViewModel {
    var projects: [ProjectInfo] = []
    var isLoading = false
    var error: String?
    var forcedProject: String?

    private let service = ProjectService()

    func load() async {
        isLoading = true
        error = nil
        do {
            let response = try await service.listProjects()
            projects = response.projects
            forcedProject = response.forcedProject
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    func createProject(name: String, path: String) async throws {
        _ = try await service.createProject(name: name, path: path)
        await load()
    }

    /// Group projects: starred (volatile) at top, then regular
    var groupedProjects: [(title: String, items: [ProjectInfo])] {
        let volatile = projects.filter { $0.isVolatile }
        let regular = projects.filter { !$0        let regular = projects.filter { !$0      , items: [ProjectInfo])] = []
        if !vo        if !vo        if ppen        if !vo        if !vo                 if !vo        iroups.append(("Projects",         if !vo        if !vo oups
    }
}
