import Foundation
import SwiftUI

/// Renders a markdown string as an AttributedString for SwiftUI Text.
/// Falls back to plain text if parsing fails.
struct MarkdownRenderer {
    static func render(_ markdown: String) -> AttributedString {
        do {
            var options = AttributedString.MarkdownParsingOptions()
            options.interpretedSyntax = .inlineOnlyPreservingWhitespace
            return try AttributedString(markdown: markdown, options: options)
        } catch {
            return AttributedString(markdown)
        }
    }

    /// Full markdown including block elements (headers, code blocks, lists)
    static func renderFull(_ markdown: String) -> AttributedString {
        do {
            return try AttributedString(markdown: markdown)
        } catch {
            return AttributedString(markdown)
        }
    }
}

/// A SwiftUI view that renders markdown text.
struct MarkdownText: View {
    let markdown: String

    var body: some View {
        Text(MarkdownRenderer.render(markdown))
    }
}
