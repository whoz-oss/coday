import Foundation

@MainActor
@Observable
class TokenUsageViewModel {
    var aggregation: TokenUsageAggregationDto?
    var series: TokenUsageSeriesDto?
    var isLoading = false
    var error: String?
    var fromDate: Date = Calendar.current.date(byAdding: .month, value: -1, to: Date()) ?? Date()
    var toDate: Date = Date()

    private let service = TokenUsageService()

    func load() async {
        isLoading = true
        error = nil
        do {
            async let agg = service.getTokenUsage(from: fromDate, to: toDate)
            async let ser = service.getTokenUsageSeries(from: fromDate, to: toDate)
            aggregation = try await agg
            series = try await ser
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    var totalCost: Double {
        aggregation?.models.reduce(0) { $0 + $1.cost } ?? 0
    }

    var totalTokens: Int {
        aggregation?.models.compactMap { $0.totalTokens }.reduce(0, +) ?? 0
    }
}