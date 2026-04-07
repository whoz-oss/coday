import SwiftUI
import Speech
import AVFoundation

struct ChatInputView: View {
    @Binding var text: String
    var isDisabled: Bool = false
    var onSend: () -> Void
    var onStop: () -> Void
    var isThinking: Bool = false

    @State private var isRecording = false
    @State private var speechRecognizer = SpeechRecognitionManager()
    @State private var showAttachPicker = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .bottom, spacing: 8) {
                // Voice button
                Button {
                    toggleVoice()
                } label: {
                    Image(systemName: isRecording ? "mic.fill" : "mic")
                        .foregroundStyle(isRecording ? .red : .secondary)
                        .frame(width: 36, height: 36)
                }
                .accessibilityLabel(isRecording ? "Stop recording" : "Start voice input")

                // Text field
                TextField(placeholderText, text: $text, axis: .vertical)
                    .lineLimit(1...8)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                    .disabled(isDisabled)
                    .onSubmit { if !text.isBlank { onSend() } }

                // Stop or Send button
                if isThinking {
                    Button(action: onStop) {
                        Image(systemName: "stop.circle.fill")
                            .font(.title2)
                            .foregroundStyle(.red)
                    }
                    .accessibilityLabel("Stop")
                } else {
                    Button(action: onSend) {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.title2)
                            .foregroundStyle(text.isBlank ? .secondary : .accentColor)
                    }
                    .disabled(text.isBlank)
                    .accessibilityLabel("Send")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color(.systemBackground))
        }
    }

    private var placeholderText: String {
        if isDisabled && isThinking { return "Agent is thinking…" }
        return "Message"
    }

    private func toggleVoice() {
        if isRecording {
            speechRecognizer.stop()
            isRecording = false
            if !speechRecognizer.transcript.isEmpty {
                text += (text.isEmpty ? "" : " ") + speechRecognizer.transcript
                speechRecognizer.transcript = ""
            }
        } else {
            SFSpeechRecognizer.requestAuthorization { status in
                guard status == .authorized else { return }
                DispatchQueue.main.async {
                    speechRecognizer.start()
                    isRecording = true
                }
            }
        }
    }
}

// MARK: - Speech Recognition Manager

@Observable
class SpeechRecognitionManager: NSObject {
    var transcript = ""
    var isAvailable = false

    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private let audioEngine = AVAudioEngine()

    override init() {
        super.init()
        recognizer = SFSpeechRecognizer(locale: Locale.current)
        isAvailable = recognizer?.isAvailable ?? false
    }

    func start() {
        guard let recognizer, recognizer.isAvailable else { return }
        let audioSession = AVAudioSession.sharedInstance()
        try? audioSession.setCategory(.record, mode: .measurement, options: .duckOthers)
        try? audioSession.setActive(true, options: .notifyOthersOnDeactivation)

        request = SFSpeechAudioBufferRecognitionRequest()
        guard let request else { return }
        request.shouldReportPartialResults = true

        task = recognizer.recognitionTask(with: request) { [weak self] result, _ in
            if let result {
                self?.transcript = result.bestTranscription.formattedString
            }
        }

        let node = audioEngine.inputNode
        let fmt = node.outputFormat(forBus: 0)
        node.installTap(onBus: 0, bufferSize: 1024, format: fmt) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }
        try? audioEngine.start()
    }

    func stop() {
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        try? AVAudioSession.sharedInstance().setActive(false)
    }
}
