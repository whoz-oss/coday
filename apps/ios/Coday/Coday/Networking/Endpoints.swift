import Foundation

struct Endpoints {
    let base: URL

    init(baseURL: String) {
        self.base = URL(string: baseURL) ?? URL(string: "http://localhost:3000")!
    }

    // MARK: - Projects
    var projects: URL { base.appendingPathComponent("api/projects") }
    func project(_ name: String) -> URL { projects.appendingPathComponent(name) }

    // MARK: - Threads
    func threads(project: String) -> URL {
        self.project(project).appendingPathComponent("threads")
    }
    func thread(project: String, threadId: String) -> URL {
        threads(project: project).appendingPathComponent(threadId)
    }
    func stopThread(project: String, threadId: String) -> URL {
        thread(project: project, threadId: threadId).appendingPathComponent("stop")
    }
    func starThread(project: String, threadId: String) -> URL {
        thread(project: project, threadId: threadId).appendingPathComponent("star")
    }
    func threadMessages(project: String, threadId: String) -> URL {
        thread(project: project, threadId: threadId).appendingPathComponent("messages")
    }
    func threadMessage(project: String, threadId: String, messageId: String) -> URL {
        threadMessages(project: project, threadId: threadId).appendingPathComponent(messageId)
    }
    func eventStream(project: String, threadId: String) -> URL {
        thread(project: project, threadId: threadId).appendingPathComponent("event-stream")
    }
    func freeMessage(project: String, threadId: String) -> URL {
        thread(project: project, threadId: threadId).appendingPathComponent("free-message")
    }
    func threadFiles(project: String, threadId: String) -> URL {
        thread(project: project, threadId: threadId).appendingPathComponent("files")
    }
    func threadFile(project: String, threadId: String, filename: String) -> URL {
        threadFiles(project: project, threadId: threadId).appendingPathComponent(filename)
    }

    // MARK: - Agents
    func agents(project: String) -> URL {
        self.project(project).appendingPathComponent("agents")
    }

    // MARK: - Prompts
    func prompts(project: String) -> URL {
        self.project(project).appendingPathComponent("prompts")
    }

    // MARK: - Schedulers
    func schedulers(project: String) -> URL {
        self.project(project).appendingPathComponent("schedulers")
    }

    // MARK: - Token usage
    var tokenUsage: URL { base.appendingPathComponent("api/token-usage") }
    var tokenUsageSeries: URL { base.appendingPathComponent("api/token-usage/series") }
}
