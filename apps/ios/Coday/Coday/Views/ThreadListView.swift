import SwiftUI

struct ThreadListView: View {
    @Bindable var vm: ThreadListViewModel
    let projectName: String
    @Binding var selectedThreadId: String?

    @State private var showRenameAlert = false
    @State private var renameTarget: ThreadSummary?
    @State private var renameText = ""
    @State private var isCreating = false

    var body: some View {
        Group {
            if vm.isLoading && vm.threads.isEmpty {
                ProgressView("Loading threads…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(selection: $selectedThreadId) {
                    if !vm.starredThreads.isEmpty {
                        Section("Starred") {
                            ForEach(vm.starredThreads) { thread in
                                ThreadRowView(
                                    thread: thread,
                                    isSelected: selectedThreadId == thread.id,
                                    onStar: { Task { await vm.toggleStar(thread) } },
                                    onRename: {
                                        renameTarget = thread
                                        renameText = thread.name
                                        showRenameAlert = true
                                    },
                                    onDelete: { Task { await vm.deleteThread(thread.id) } }
                                )
                                .tag(thread.id)
                            }
                        }
                    }
                    Section("Threads") {
                        ForEach(vm.unstarredThreads) { thread in
                            ThreadRowView(
                                thread: thread,
                                isSelected: selectedThreadId == thread.id,
                                onStar: { Task { await vm.toggleStar(thread) } },
                                onRename: {
                                    renameTarget = thread
                                    renameText = thread.name
                                    showRenameAlert = true
                                },
                                onDelete: { Task { await vm.deleteThread(thread.id) } }
                            )
                            .tag(thread.id)
                        }
                        if vm.threads.isEmpty {
                            Text("No threads yet")
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .center)
                        }
                    }
                }
                .listStyle(.sidebar)
            }
        }
        .navigationTitle("Threads")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    createThread()
                } label: {
                    if isCreating {
                        ProgressView()
                    } else {
                        Image(systemName: "square.and.pencil")
                    }
                }
                .disabled(isCreating)
                .accessibilityLabel("New thread")
            }
        }
        .refreshable { await vm.load(project: projectName) }
        .alert("Rename Thread", isPresented: $showRenameAlert, presenting: renameTarget) { target in
            TextField("Thread name", text: $renameText)
            Button("Rename") {
                Task { await vm.renameThread(target.id, newName: renameText) }
            }
            Button("Cancel", role: .cancel) {}
        } message: { target in
            Text("Enter a new name for "\(target.name)"")
        }
    }

    private func createThread() {
        isCreating = true
        Task {
            if let newId = try? await vm.createThread() {
                selectedThreadId = newId
            }
            isCreating = false
        }
    }
}

struct ThreadRowView: View {
    let thread: ThreadSummary
    let isSelected: Bool
    var onStar: () -> Void
    var onRename: () -> Void
    var onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(thread.name)
                    .font(.body)
                    .lineLimit(1)
                Spacer()
                if thread.isStarred {
                    Image(systemName: "star.fill")
                        .foregroundStyle(.yellow)
                        .font(.caption)
                }
            }
            if let summary = thread.summary, !summary.isEmpty {
                Text(summary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 2)
        .contextMenu {
            Button(thread.isStarred ? "Unstar" : "Star", action: onStar)
            Button("Rename", action: onRename)
            Divider()
            Button("Delete", role: .destructive, action: onDelete)
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive, action: onDelete) {
                Label("Delete", systemImage: "trash")
            }
            Button(action: onStar) {
                Label(thread.isStarred ? "Unstar" : "Star",
                      systemImage: thread.isStarred ? "star.slash" : "star")
            }
            .tint(.yellow)
        }
        .accessibilityLabel("\(thread.name)\(thread.isStarred ? ", starred" : "")")
    }
}
