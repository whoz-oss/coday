import SwiftUI
import Charts

struct TokenUsageView: View {
    @State private var vm = TokenUsageViewModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading {
                    ProgressView("Loading usage…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let error = vm.error {
                    ContentUnavailableView(
                        "Failed to load",
                        systemImage: "exclamationmark.triangle",
                        description: Text(error)
                    )
                } else {
                    List {
                        Section {
                            HStack(spacing: 16) {
                                StatCard(
                                    title: "Total Cost",
                                    value: String(format: "$0.0000", vm.totalCost),
                                    icon: "dollarsign.circle",
                                    color: .green
                                )
                                StatCard(
                                    title: "Total Tokens",
                                    value: formatTokens(vm.totalTokens),
                                    icon: "number.circle",
                                    color: .blue
                                )
                            }
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                        }

                        if let series = vm.series, !series.points.isEmpty {
                            Section("Cost Over Time") {
                                Chart {
                                    ForEach(aggregatedByDate(series.points), id: \.date) { point in
                                        LineMark(
                                            x: .value("Date", point.date),
                                            y: .value("Cost", point.cost)
                                        )
                                        .foregroundStyle(Color.accentColor)
                                        AreaMark(
                                            x: .value("Date", point.date),
                                            y: .value("Cost", point.cost)
                                        )
                                        .foregroundStyle(Color.accentColor.opacity(0.15))
                                    }
                                }
                                .frame(height: 180)
                                .padding(.vertical, 8)
                            }
                        }

                        if let models = vm.aggregation?.models, !models.isEmpty {
                            Section("By Model") {
                                ForEach(models.sorted { $0.cost > $1.cost }) { model in
                                    ModelUsageRow(model: model)
                                }
                            }
                        }

                        Section("Date Range") {
                            DatePicker("From", selection: $vm.fromDate, displayedComponents: .date)
                            DatePicker("To", selection: $vm.toDate, displayedComponents: .date)
                            Button("Reload") { Task { await vm.load() } }
                                .frame(maxWidth: .infinity, alignment: .center)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Token Usage")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .task { await vm.load() }
        }
    }

    private func formatTokens(_ count: Int) -> String {
        if count >= 1_000_000 { return String(format: "0.0M", Double(count) / 1_000_000) }
        if count >= 1_000 { return String(format: "0.0K", Double(count) / 1_000) }
        return "\(count)"
    }

    private struct DailyPoint { let date: String; let cost: Double }

    private func aggregatedByDate(_ points: [TimeSeriesPointDto]) -> [DailyPoint] {
        var byDate: [String: Double] = [:]
        for p in points { byDate[p.date, default: 0] += p.cost }
        return byDate.map { DailyPoint(date: $0.key, cost: $0.value) }
            .sorted { $0.date < $1.date }
    }
}

struct StatCard: View {
    let title: String
    let value: String
    let icon: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: icon).foregroundStyle(color)
                Text(title).font(.caption).foregroundStyle(.secondary)
            }
            Text(value).font(.title3.bold())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(color.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct ModelUsageRow: View {
    let model: ModelUsageSummaryDto

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.modelId).font(.subheadline.bold()).lineLimit(1)
                    Text("\(model.agentName) · \(model.providerName)")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text(String(format: "$0.0000", model.cost))
                    .font(.subheadline.bold())
                    .foregroundStyle(.green)
            }
            HStack(spacing: 16) {
                Label("\(model.callCount) calls", systemImage: "arrow.left.arrow.right")
                if let total = model.totalTokens {
                    Label("\(total) tokens", systemImage: "number")
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}
