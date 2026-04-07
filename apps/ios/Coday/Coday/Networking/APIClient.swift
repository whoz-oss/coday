import Foundation

enum APIError: LocalizedError {
    case invalidURL
    case networkError(Error)
    case httpError(Int, Data?)
    case decodingError(Error)
    case unknown

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .networkError(let e): return e.localizedDescription
        case .httpError(let code, _): return "HTTP \(code)"
        case .decodingError(let e): return "Decoding error: \(e.localizedDescription)"
        case .unknown: return "Unknown error"
        }
    }
}

actor APIClient {
    static let shared = APIClient()

    private var session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        self.session = URLSession(configuration: config)
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    // MARK: - GET

    func get<T: Decodable>(_ url: URL) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        addCommonHeaders(to: &request)
        return try await perform(request)
    }

    // MARK: - POST with body, returns Decodable

    func post<T: Decodable, B: Encodable>(_ url: URL, body: B) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        addCommonHeaders(to: &request)
        request.httpBody = try encoder.encode(body)
        return try await perform(request)
    }

    // MARK: - POST without body, returns Decodable

    func post<T: Decodable>(_ url: URL) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        addCommonHeaders(to: &request)
        return try await perform(request)
    }

    // MARK: - POST with body, discards response

    func postVoid<B: Encodable>(_ url: URL, body: B) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        addCommonHeaders(to: &request)
        request.httpBody = try encoder.encode(body)
        try await performVoid(request)
    }

    // MARK: - POST without body, discards response

    func postVoid(_ url: URL) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        addCommonHeaders(to: &request)
        try await performVoid(request)
    }

    // MARK: - PUT with body, returns Decodable

    func put<T: Decodable, B: Encodable>(_ url: URL, body: B) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        addCommonHeaders(to: &request)
        request.httpBody = try encoder.encode(body)
        return try await perform(request)
    }

    // MARK: - DELETE, returns Decodable

    func delete<T: Decodable>(_ url: URL) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        addCommonHeaders(to: &request)
        return try await perform(request)
    }

    // MARK: - Helpers

    private func addCommonHeaders(to request: inout URLRequest) {
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(AppConfig.username, forHTTPHeaderField: "x-forwarded-email")
    }

    private func perform<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await session.data(for: request)
        try validate(response: response, data: data)
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decodingError(error)
        }
    }

    private func performVoid(_ request: URLRequest) async throws {
        let (data, response) = try await session.data(for: request)
        try validate(response: response, data: data)
    }

    private func validate(response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw APIError.unknown
        }
        guard (200..<300).contains(http.statusCode) else {
            throw APIError.httpError(http.statusCode, data)
        }
    }
}
