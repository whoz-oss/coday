import Foundation

@MainActor
@Observable
class ThreadListViewModel {
    var threads: [ThreadSummary] = []
    var isLoading = false
    var error: String?
    var projectName: String = ""

    private let service = ThreadService()

    var starredThreads: [ThreadSummary] {
        threads.filter { $0.isStarred }.sorted { $0.modifiedAt > $1.modifiedAt }
    }

    var unstarredThreads: [ThreadSummary] {
        threads.filter { !$0.isStarred }.sorted { $0.modifiedAt > $1.modifiedAt }
    }

    func load(project: String) async {
        projectName = project
        isLoading = true
        error = nil
        do {
            threads = try await service.listThreads(project: project)
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    func createThread(name: String? = nil) async throws -> String {
        let resp = try await service.createThread(project: projectName, name: name)
        await load(project: projectName)
        return resp.id
    }

    func deleteThread(_ threadId: String) async {
        do {
            try await service.deleteThread(project: projectName, threadId: threadId)
            threads.removeAll { $0.id == threadId }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func toggleStar(_ thread: ThreadSummary) async {
        // Optimistic update
        if let idx = threads.firstIndex(where: { $0.id == thread.id }) {
            let username = AppConfig.username
            var updated = threads[idx]
            var starring = updated.starring ?? []
            if starring.contains(username) {
                starring.removeAll { $0 == username }
            } else {
                starring.append(username)
            }
            // Rebuild with new starring — ThreadSummary is a struct so we reconstruct
            threads[idx] = ThreadSummary(
                id: updated.id, username: updated.username,
                projectId: updated.projectId, name: updated.name,
                summary: updated.summary, createdDate: updated.createdDate,
                modifiedDate: updated.modifiedDate, price: updated.price,
                starring: starring, users: updated.users,
                parentThreadId: updated.parentThreadId,
                delegatedAgentName: updated.delegatedAgentName,
                delegatedTask: updated.delegatedTask
            )
        }
        do {
            if thread.isStarred {
                _ = try await service.unstarThread(project: projectName, threadId: thread.id)
            } else {
                _ = try await service.starThread(project: projectName, threadId: thread.id)
            }
        } catch {
            // Revert on failure
            await load(project: projectName)
        }
    }

    func renameThread(_ threadId: String, newName: String) async {
        do {
            _ = try await service.updateThread(project: projectName, threadId: threadId, name: newName)
            if let idx = threads.firstIndex(where: { $0.id == threadId }) {
                let t = threads[idx]
                threads[idx] = ThreadSummary(
                    id: t.id, username: t.username, projectId: t.projectId,
                    name: newName, summary: t.summary, createdDate: t.createdDate,
                    modifiedDate: t.modifiedDate, price: t.price,
                    starring: t.starring, users: t.users,
                    parentThreadId: t.parentThreadId,
                    delegatedAgentName: t.delegatedAgentName,
                    delegatedTask: t.delegatedTask
                )
            }
        } catch {
            self.error = error.localizedDescription
        }
    }
}
