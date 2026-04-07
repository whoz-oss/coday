import Foundation
import Combine

@MainActor
@Observable
class AppViewModel {
    var selectedProject: ProjectInfo?
    var navigateTo: AppDestination?

    enum AppDestination: Hashable {
        case project(String)
        case thread(project: String, threadId: String)
    }
}
