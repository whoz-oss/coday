import SwiftUI
import UniformTypeIdentifiers

struct FileExchangeView: View {
    let projectName: String
    let threadId: String

    @State private var files: [FileInfo] = []
    @State private var isLoading = false
    @State private var isUploading = false
    @State private var error: String?
    @State private var showFilePicker = false
    @Environment(\.dismiss) private var dismiss

    struct FileInfo: Identifiable {
        let id = UUID()
        let name: String
        let size: Int?
    }

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    ProgressView("Loading files\u2026")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if files.isEmpty {
                    ContentUnavailableView(
                        "No Files",
                        systemImage: "doc",
                        description: Text("Upload files to share with the agent.")
                    )
                } else {
                    List {
                        ForEach(files) { file in
                            HStack(spacing: 12) {
                                Image(systemName: "doc.fill")
                                    .foregroundStyle(.blue)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(file.name)
                                        .font(.body)
                                        .lineLimit(1)
                                    if let size = file.size {
                                        Text(ByteCountFormatter.string(
                                            fromByteCount: Int64(size),
                                            countStyle: .file
                                        ))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
            }
            .navigationTitle("File Exchange")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showFilePicker = true
                    } label: {
                        if isUploading {
                            ProgressView().scaleEffect(0.8)
                        } else {
                            Image(systemName: "plus")
                        }
                    }
                    .disabled(isUploading)
                    .accessibilityLabel("Upload file")
                }
            }
            .fileImporter(
                isPresented: $showFilePicker,
                allowedContentTypes: [.data],
                allowsMultipleSelection: false
            ) { result in
                Task { await handleFileImport(result) }
            }
            .alert("Error", isPresented: Binding(
                get: { error != nil },
                set: { if !$0 { error = nil } }
            )) {
                Button("OK") { error = nil }
            } message: {
                Text(error ?? "")
            }
            .refreshable { await loadFiles() }
            .task { await loadFiles() }
        }
    }

    private func loadFiles() async {
        isLoading = true
        let endpoints = Endpoints(baseURL: AppConfig.baseURL)
        do {
            struct FilesResponse: Codable {
                struct FileEntry: Codable { let name: String; let size: Int? }
                let files: [FileEntry]
            }
            let resp: FilesResponse = try await APIClient.shared.get(
                endpoints.threadFiles(project: projectName, threadId: threadId)
            )
            files = resp.files.map { FileInfo(name: $0.name, size: $0.size) }
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    private func handleFileImport(_ result: Result<[URL], Error>) async {
        switch result {
        case .failure(let e):
            error = e.localizedDescription
        case .success(let urls):
            guard let url = urls.first else { return }
            isUploading = true
            do {
                let accessed = url.startAccessingSecurityScopedResource()
                defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                let data = try Data(contentsOf: url)
                let endpoints = Endpoints(baseURL: AppConfig.baseURL)
                var req = URLRequest(url: endpoints.threadFiles(project: projectName, threadId: threadId))
                req.httpMethod = "POST"
                let boundary = UUID().uuidString
                req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
                req.setValue(AppConfig.username, forHTTPHeaderField: "x-forwarded-email")
                var body = Data()
                let filename = url.lastPathComponent
                body.append("--\(boundary)\r\n".data(using: .utf8)!)
                body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
                body.append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
                body.append(data)
                body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
                req.httpBody = body
                let (_, response) = try await URLSession.shared.data(for: req)
                if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                    throw URLError(.badServerResponse)
                }
                await loadFiles()
            } catch {
                self.error = error.localizedDescription
            }
            isUploading = false
        }
    }
}
