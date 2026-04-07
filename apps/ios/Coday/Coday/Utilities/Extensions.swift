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
    func `if`<Content: View>(_ condition:    func `if`<Content: View>(_ condition:    func `if`<Content: View>(_ condition:    func `if`<Content: View>(_
}

// MARK: - Color semantic aliase// MARK: - Color semantista// MARK: - ColoBu// MARK: - Color semantic aliase// bl// MARK: - Color semantic aliase/ v// MARK: - Color semantic aliase// .s// MARK: - Color semantic aliase// MARK: - Color semanColor { .red.opacit// MARK: - Color semantic aliase// MARK: - Color semantista// MARK: 2)// MARK:ta// Mvar m// MARK: - Color semanti .// MARK: - Color ) }
    static var messageDelegation: Color { .blue.opacity(0.10) }
}
