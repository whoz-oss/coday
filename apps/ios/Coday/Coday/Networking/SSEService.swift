import Foundation

enum SSEConnectionStatus {
    case disconnected
    case connecting
    case connected
    case reconnecting(attempt: Int)
    case failed
}

@MainActor
class SSEService: NSObject, ObservableObject, URLSessionDataDelegate {
    @Published var connectionStatus: SSEConnectionStatus = .disconnected

    private var urlSession: URLSession?
    private var dataTask: URLSessionDataTask?
    private var buffer = ""
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 5
    private var currentURL: URL?
    private var reconnectTask: Task<Void, Never>?

    var onEvent: ((CodayEvent) -> Void)?

    func connect(to url: URL) {
        disconnect()
        currentURL = url
        reconnectAttempts = 0
        openConnection(to: url)
    }

    func disconnect() {
        reconnectTask?.cancel()
        reconnectTask = nil
        dataTask?.cancel()
        dataTask = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
        buffer = ""
        connectionStatus = .disconnected
    }

    private func openConnection(to url: URL) {
        connectionStatus = reconnectAttempts == 0 ? .connecting : .reconnecting(attempt: reconnectAttempts)
        buffer = ""

        var request = URLRequest(url: url)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue(AppConfig.username, forHTTPHeaderField: "x-forwarded-email")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.timeoutInterval = 0

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 0
        config.timeoutIntervalForResource = 0
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.urlSession = session
        self.dataTask = session.dataTask(with: request)
        self.dataTask?.resume()
    }

    nonisolated func urlSession(_ session: URLSession,
                                dataTask: URLSessionDataTask,
                                didReceive response: URLResponse,
                                completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        completionHandler(.allow)
        Task { @MainActor in
            self.connectionStatus = .connected
            self.reconnectAttempts = 0
        }
    }

    nonisolated func urlSession(_ session: URLSession,
                                dataTask: URLSessionDataTask,
                                didReceive data: Data) {
        guard let text = String(data: data, encoding: .utf8) else { return }
        Task { @MainActor in
            self.buffer += text
            self.processBuffer()
        }
    }

    nonisolated func urlSession(_ session: URLSession,
                                task: URLSessionTask,
                                didCompleteWithError error: Error?) {
        Task { @MainActor in
            if let error, (error as NSError).code == NSURLErrorCancelled {
                return
            }
            self.scheduleReconnect()
        }
    }

    private func processBuffer() {
        let separator = "\n\n"
        var remaining = buffer

        while let range = remaining.range(of: separator) {
            let eventBlock = String(remaining[remaining.startIndex..<range.lowerBound])
            remaining = String(remaining[range.upperBound...])
            parseEventBlock(eventBlock)
        }
        buffer = remaining
    }

    private func parseEventBlock(_ block: String) {
        var dataLines: [String] = []
        for line in block.split(separator: "\n", omittingEmptySubsequences: false) {
            let s = String(line)
            if s.hasPrefix("data:") {
                let value = String(s.dropFirst(5)).trimmingCharacters(in: .init(charactersIn: " "))
                dataLines.append(value)
            }
        }
        let jsonStr = dataLines.joined()
        guard !jsonStr.isEmpty else { return }
        parseJSON(jsonStr)
    }

    private func parseJSON(_ json: String) {
        guard let data = json.data(using: .utf8) else { return }
        let decoder = JSONDecoder()
        do {
            let raw = try decoder.decode(RawCodayEvent.self, from: data)
            if let event = buildCodayEvent(from: raw) {
                onEvent?(event)
            }
        } catch {
        }
    }

    private func scheduleReconnect() {
        guard reconnectAttempts < maxReconnectAttempts, let url = currentURL else {
            connectionStatus = .failed
            return
        }
        reconnectAttempts += 1
        connectionStatus = .reconnecting(attempt: reconnectAttempts)
        let delay = Double(reconnectAttempts) * 2.0
        reconnectTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            openConnection(to: url)
        }
    }
}
