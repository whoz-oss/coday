import Foundation

enum DateFormatting {
    static let iso8601: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static let iso8601Basic: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        return f
    }()

    static func parse(_ string: String) -> Date? {
        let clean = string.strippingCodayTimestampSuffix()
        return iso8601.date(from: clean) ?? iso8601Basic.date(from: clean)
    }

    static let relativeFormatter: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .abbreviated
        return f
    }()
}
