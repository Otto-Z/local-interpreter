import Foundation

/// 原生 WebSocket 客户端：把 PCM 推给本地引擎，并把事件转回网页层。
final class ServerLink: NSObject, URLSessionWebSocketDelegate {
    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    var onEvent: ((String) -> Void)?
    var onClosed: ((String) -> Void)?
    private var closing = false
    private var generation = 0

    override init() {
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }

    func connect(url: URL, startMessage: String) {
        generation += 1
        let generation = self.generation
        closing = false
        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()
        send(string: startMessage)
        receive(generation)
    }

    func send(string: String) {
        task?.send(.string(string)) { _ in }
    }

    func send(data: Data) {
        task?.send(.data(data)) { _ in }
    }

    func close() {
        generation += 1
        closing = true
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
    }

    private func receive(_ generation: Int) {
        guard generation == self.generation, let task = task else { return }
        task.receive { [weak self] result in
            guard let self = self,
                  self.generation == generation,
                  self.task === task
            else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.onEvent?(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.onEvent?(text)
                    }
                @unknown default:
                    break
                }
                self.receive(generation)
            case .failure(let error):
                if !self.closing {
                    self.onClosed?(error.localizedDescription)
                }
            }
        }
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        guard webSocketTask === task else { return }
        if !closing {
            let text = reason.flatMap { String(data: $0, encoding: .utf8) } ?? "连接已关闭"
            onClosed?(text)
        }
    }
}
