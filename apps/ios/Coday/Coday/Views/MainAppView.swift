import SwiftUI

struct MainAppView: View {
    let projectName: String

    @State private var threadListVM = ThreadListViewModel()
    @State private var selectedThreadId: String?
    @State private var showTokenUsage = false
    @State private var columnVisibility = NavigationSplitViewVisibility.automatic

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            // Sidebar: thread list
            ThreadListView(
                vm: threadListVM,
                projectName: projectName,
                selectedThreadId: $selectedThreadId
            )
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showTokenUsage = true
                    } label: {
                        Image(systemName: "chart.bar")
                    }
                    .accessibilityLabel("Token usage")
                }
            }
        } detail: {
            // Detail: thread or welcome
            if let threadId = selectedThreadId {
                ThreadView(
                    projectName: projectName,
                    threadId: threadId,
                    onThreadDeleted: {
                        selectedThreadId = nil
                        Task { await threadListVM.load(project: projectName) }
                    }
                )
                .id(threadId) // Force recreate when thread changes
            } else {
                WelcomeView(projectName: projectName) {
                    Task {
                        if let newId = try? await threadListVM.createThread() {
                            selectedThreadId = newId
                        }
                    }
                }
            }
        }
        .navigationTitle(projectName)
        .sheet(isPresented: $showTokenUsage) {
            TokenUsageView()
        }
        .task {
            await threadListVM.load(project: projectName)
        }
    }
}

struct WelcomeView: View {
    let projectName: String
    var onNewThread: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text(projectName)
                .font(.title2.bold())
            Text("Select a thread from the sidebar or start a new conversation.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button(action: onNewThread) {
                Label("New Thread", systemImage: "plus.bubble")
                    .font(.headline)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
