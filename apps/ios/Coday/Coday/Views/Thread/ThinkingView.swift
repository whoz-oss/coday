import SwiftUI

/// Animated three-dot thinking indicator.
struct ThinkingView: View {
    @State private var phase: Int = 0

    private let timer = Timer.publish(every: 0.4, on: .main, in: .common).autoconnect()

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<3) { i in
                Circle()
                    .fill(Color.secondary.opacity(phase == i ? 1 : 0.3))
                    .frame(width: 8, height: 8)
                    .scaleEffect(phase == i ? 1.3 : 1.0)
                    .animation(.easeInOut(duration: 0.3), value: phase)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.messageBubbleAssistant)
        .clipShape(Capsule())
        .onReceive(timer) { _ in
            phase = (phase + 1) % 3
        }
        .accessibilityLabel("Agent is thinking")
    }
}
