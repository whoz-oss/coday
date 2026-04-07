import Foundation

struct ModelUsageSummaryDto: Codable, Identifiable {
    var id: String { "\(agentName)-\(providerName)-\(modelId)" }
    let agentName: String
    let providerName: String
    let modelId: String
    let promptTokens: Int?
    let completionTokens: Int?
    let totalTokens: Int?
    let callCount: Int
    let cost: Double
}

struct TokenUsageAggregationDto: Codable {
    let models: [ModelUsageSummaryDto]
    let tokenDataPartial: Bool
}

struct TimeSeriesPointDto: Codable {
    let date: String
    let agentName: String
    let providerName: String
    let modelId: String
    let promptTokens: Int?
    let completionTokens: Int?
    let totalTokens: Int?
    let callCount: Int
    let cost: Double
}

struct TokenUsageSeriesDto: Codable {
    let points: [TimeSeriesPointDto]
    let tokenDataPartial: Bool
}
