import Foundation

/// Probes and optionally launches local macOS inference binaries (`llama-server` / `whisper-server`)
/// so the user can run completely offline with Metal acceleration without touching a terminal.
final class LocalEngineManager {
    private var processes: [Process] = []
    private let lock = NSLock()

    deinit {
        stopAll()
    }

    /// Check whether a local HTTP server responds on `/health` or `/v1/models`.
    func probeHealth(baseUrl: String) async -> Bool {
        let trimmed = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !trimmed.isEmpty else { return false }
        let root = trimmed.hasSuffix("/v1") ? String(trimmed.dropLast(3)) : trimmed
        for path in ["\(root)/health", "\(root)/v1/models", "\(root)/models"] {
            guard let url = URL(string: path) else { continue }
            var req = URLRequest(url: url, timeoutInterval: 2.5)
            req.httpMethod = "GET"
            if let (_, resp) = try? await URLSession.shared.data(for: req),
               let http = resp as? HTTPURLResponse,
               (200...299).contains(http.statusCode) {
                return true
            }
        }
        return false
    }

    /// Launch a local binary (`llama-server` or `whisper-server`) if present in PATH or app bundle.
    @discardableResult
    func launchLocalBinary(name: String, arguments: [String]) -> Bool {
        guard let execUrl = findExecutable(named: name) else { return false }
        let proc = Process()
        proc.executableURL = execUrl
        proc.arguments = arguments
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = FileHandle.nullDevice
        do {
            try proc.run()
            lock.lock()
            processes.append(proc)
            lock.unlock()
            return true
        } catch {
            return false
        }
    }

    func stopAll() {
        lock.lock()
        let copy = processes
        processes.removeAll()
        lock.unlock()
        for p in copy where p.isRunning {
            p.terminate()
        }
    }

    private func findExecutable(named name: String) -> URL? {
        if let bundled = Bundle.main.url(forAuxiliaryExecutable: name) {
            return bundled
        }
        let searchPaths = [
            "/opt/homebrew/bin/\(name)",
            "/usr/local/bin/\(name)",
            "\(NSHomeDirectory())/.local/bin/\(name)",
        ]
        for p in searchPaths where FileManager.default.isExecutableFile(atPath: p) {
            return URL(fileURLWithPath: p)
        }
        return nil
    }
}
