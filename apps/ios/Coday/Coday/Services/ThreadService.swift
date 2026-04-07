import Foundation

class ThreadService {
    private let api = APIClient.shared
    private var endpoints: Endpoints { Endpoints(baseURL: AppConfig.baseURL) }

    func listThreads(project: String) async throws -> [ThreadSummary] {
        struct Response: Codable { let threads: [ThreadSummary] }
        let resp: Response = try await api.get(endpoints.threads(project: project))
        return resp.threads
    }

    func getThread(project: String, threadId: String) async throws -> ThreadDetails {
        try await api.get(endpoints.thread(project: project, threadId: threadId))
    }

    func createThread(project: String, name: String? = nil) async throws -> ThreadCreationResponse {
        struct Body: Encodable { let name: String? }
        return try await api.post(endpoints.threads(project: project), body: Body(name: name))
    }

    func updateThread(project: String, threadId: String, name: String) async throws -> ThreadUpdateResponse {
        struct Body: Encodable { let name: String }
        return try await api.put(endpoints.thread(project: project, threadId: threadId), body: Body(name: name))
    }

    func deleteThread(project: String, threadId: String) async throws {
        struct Response: Codable { let success: Bool? }
        let _: Response = try await api.delete(endpoints.thread(project: project, threadId: threadId))
    }

    func stopThread(project: String, threadId: String) async throws {
        try await api.postVoid(endpoints.stopThread(project: project, threadId: threadId))
    }

    func starThread(project: String, threadId: String) async throws -> ThreadStarResponse {
        try await api.post(endpoints.starThread(project: project, threadId: threadId))
    }

    func unstarThread(project: String, threadId: String) async throws -> ThreadStarResponse {
        try await api.delete(endpoints.starThread(project: project, threadId: threadId))
    }

    func getMessages(project: String, threadId: String) async throws -> [RawCodayEvent] {
        struct Response: Codable { let messages: [RawCodayEvent] }
        let resp: Response = try await api.get(endpoints.threadMessages(project: project, threadId: threadId))
        return resp.messages
    }

    func sendFreeMessage(project: String, threadId: String, message: String) async throws {
        struct Body: Encodable { let message: String }
        try await api.postVoid(endpoints.freeMessage(project: project, threadId: threadId), body: Body(message: message))
    }

    func sendAnswer(project: String, threadId: String, answer: String, parentKey: String?, invite: String?) async throws {
        struct Body: Encodable {
            let type: String
            let answer: String
            let parentKey: String?
            let invite: String?
        }
        try await api.postVoid(
            endpoints.threadMessages(project: project, threadId: threadId),
            body: Body(type: "answer", answer: answer, parentKey: parentKey, invite: invite)
        )
    }

    func sendChoice(project: String, threadId: String, choice: String, parentKey: String?) async throws {
        struct Body: Encodable {
            let type: String
            let answer: String
            let parentKey: String?
        }
        try await api.postVoid(
            endpoints.threadMessages(project: project, threadId: threadId),
            body: Body(type: "answer", answer: choice, parentKey: parentKey)
        )
    }

    func deleteMessage(project: String, threadId: String, messageId: String) async throws {
        struct Response: Codable { let success: Bool? }
        let _: Response = try await api.delete(endpoints.threadMessage(project: project, threadId: threadId, messageId: messageId))
    }
}
