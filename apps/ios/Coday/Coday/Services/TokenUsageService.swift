import Foundation

class TokenUsageService {
    private let api = APIClient.shared
    private var endpoints: Endpoints { Endpoints(baseURL: AppConfig.baseURL) }

    func getTokenUsage(from: Date, to: Date) async throws -> TokenUsageAggregationDto {
        var components = URLComponents(url: endpoints.tokenUsage, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "from", value: ISO8601DateFormatter().string(from: from)),
            URLQueryItem(name: "to", value: ISO8601DateFormatter().string(from: to))
        ]
        let url = components.url ?? endpoints.tokenUsage
        return try await api.get(url)
    }

    func getTokenUsageSeries(from: Date, to: Date) async throws -> TokenUsageSeriesDto {
        var components = URLComponents(url: endpoints.tokenUsageSeries, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "from", value: ISO8601DateFormatter().string(from: from)),
            URLQueryItem(name: "to", value: ISO8601DateFormatter().string(from: to))
        ]
        let url = components.url ?? endpoints.tokenUsageSeries
        return try await api.get(url)
    }
}
