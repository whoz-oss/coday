import SwiftUI

struct ThreadView: View {
    let projectName: String
    let threadId: String
    var onThreadDeleted: (() -> Void)?

    @State private var vm = ThreadViewModel()
    @State private var inputText = ""
    @State private var showFileExchange = false

    var body: some View {
        VStack(spacing: 0) {
            // Connection status banner
            connectionBanner

            // Chat history
            ChatHistoryView(
                messages: vm.messages,
                streamingText: vm.streamingText,
                isThinking: vm.isThinking,
                onDeleteMessage: { id in
                    Task { await vm.deleteMessage(id: id) }
                }
            )

            Divider()

            // Overlays for choice / invite / oauth
            if let choice = vm.pendingChoice {
                ChoiceView(choice: choice) { selected in
                    Task { await vm.sendChoice(selected) }
                }
                .padding(.horizontal)
                .padding(.top, 8)
            } else if let invite = vm.pendingInvite {
                InviteView(invite: invite)
                    .padding(.horizontal)
                    .padding(.top, 8)
            }

            if let oauth = vm.pendingOAuth {
                OAuthRequestView(oauth: oauth) {
                    vm.pendingOAuth = nil
                }
                .padding(.horizontal)
                .padding(.top, 8)
            }

            // Input
            ChatInputView(
                text: $inputText,
                isDisabled: vm.isThinking && vm.pendingChoice == nil && vm.pendingInvite == nil,
                onSend: {
                    let msg = inputText
                    inputText = ""
                    Task { await vm.sendMessage(msg) }
                },
                onStop: {
                    Task { await vm.stopExecution() }
                },
                isThinking: vm.isThinking
            )
        }
        .navigationTitle("Thread")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showFileExchange = true
                } label: {
                    Image(systemName: "paperclip")
                }
                .accessibilityLabel("File exchange")
            }
        }
        .sheet(isPresented: $showFileExchange) {
            FileExchangeView(projectName: projectName, threadId: threadId)
        }
        .task {
            vm.connect(project: projectName, threadId: threadId)
        }
        .onDisappear {
            vm.disconnect()
        }
        .alert("Error", isPresented: Binding(
            get: { vm.error != nil },
            set: { if !$0 { vm.error = nil } }
        )) {
            Button("OK") { vm.error = nil }
        } message: {
            Text(vm.error ?? "")
        }
    }

    @ViewBuilder
    private var connectionBanner: some View {
        switch vm.connectionStatus {
        case .connecting:
            HStack(spacing: 8) {
                ProgressView().scaleEffect(0.7)
                Text("Connecting…").font(.caption)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(.yellow.opacity(0.2))
        case .reconnecting(let attempt):
            Text("Reconnecting (\(attempt)/5)…")
                .font(.caption)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .background(.orange.opacity(0.2))
        case .failed:
            Text("Connection lost. Pull to refresh.")
                .font(.caption)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .background(.red.opacity(0.2))
        default:
            EmptyView()
        }
    }
}
