import SwiftUI

/// Shows live-streaming text with a pulsing cursor indicator.
struct StreamingTextView: View {
    let text: String
    @State private var cursorVisible = true

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            MarkdownText(content: text)
                .frame(maxWidth: .infinity, alignment: .leading)
            // Blinking cursor
            Rectangle()
                .fill(Color.accentColor)
                .frame(width: 2, height: 16)
                .opacity(cursorVisible ? 1 : 0)
                .animation(.easeInOut(duration: 0.5).repeatForever(), value: cursorVisible)
                .onAppear { cursorVisible.toggle() }
        }
        .padding(10)
        .background(Color.messageBubbleAssistant)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
