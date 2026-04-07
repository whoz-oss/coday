import Foundation
import SwiftUI

// MARK: - Date formatting
extension Date {
    func chatTimestamp() -> String {
        let cal = Calendar.current
        if cal.isDateInToday(self) {
            return formatted(.dateTime.hour().minute())
        } else {
            return formatted(.dateTime.day().month().hour().minute())
        }
    }
}

// MARK: - String helpers
extension String {
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// Strip Coday timestamp suffix like "-x81ku"
    func strippingCodayTimestampSuffix() -> String {
        guard count > 6, let lastHyphen = lastIndex(of: "-") else { return self }
        let suffix = self[lastHyphen...]
        if suffix.count == 6 { return String(self[startIndex..<lastHyphen]) }
        return self
    }
}

// MARK: - View helpers
extension View {
    @ViewBuilder
    func `if`<Content: View>(_ condition: Bool, transform: (Self) -> Content) -> some View {
        if condition {
            transform(self)
        } else {
            self
        }
    }
}

// MARK: - Color semantic aliases
extension Color {
    static var messageUser: Color { .blue.opacity(0.12) }
    static var messageAssistant: Color { .clear }
    static var messageSystem: Color { .secondary.opacity(0.08) }
    static var messageError: Color { .red.opacity(0.12) }
    static var messageWarning: Color { .orange.opacity(0.12) }
    static var messageTechnical: Color { .purple.opacity(0.08) }
    static var messageDelegation: Color { .blue.opacity(0.10) }
}
