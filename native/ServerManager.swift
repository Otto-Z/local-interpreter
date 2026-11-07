import Foundation

/// 启动并管理内置的 Python 引擎进程。
final class ServerManager {
    let port: Int = 8765
    private var process: Process?
    private var startedByUs = false
    private let session = URLSession(configuration: .ephemeral)

    var baseURL: URL { URL(string: "http://127.0.0.1:\(port)")! }
    var wsURL: URL { URL(string: "ws://127.0.0.1:\(port)/ws/audio")! }
    private var resources: URL { Bundle.main.resourceURL! }

    var dataDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("本地同传", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func startIfNeeded(completion: @escaping (Bool) -> Void) {
        health { [weak self] ok in
            guard let self = self else { return }
            if ok {
                completion(true)
                return
            }
            self.launch()
            var tries = 0
            func poll() {
                self.health { ok2 in
                    if ok2 {
                        completion(true)
                        return
                    }
                    tries += 1
                    if tries > 120 {
                        completion(false)
                        return
                    }
                    DispatchQueue.global().asyncAfter(deadline: .now() + 0.3) { poll() }
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + 0.5) { poll() }
        }
    }

    private func health(completion: @escaping (Bool) -> Void) {
        var request = URLRequest(url: baseURL.appendingPathComponent("api/health"))
        request.timeoutInterval = 0.6
        session.dataTask(with: request) { _, response, _ in
            if let http = response as? HTTPURLResponse, http.statusCode == 200 {
                completion(true)
            } else {
                completion(false)
            }
        }.resume()
    }

    private func launch() {
        let python = resources.appendingPathComponent("python/bin/python3.12")
        let source = resources.appendingPathComponent("source")
        let site = resources.appendingPathComponent("site-packages")
        guard FileManager.default.isExecutableFile(atPath: python.path) else {
            return
        }
        let p = Process()
        p.executableURL = python
        p.arguments = ["-m", "app.server"]
        p.currentDirectoryURL = source

        var env = ProcessInfo.processInfo.environment
        env["PYTHONPATH"] = site.path
        env["LOCAL_INTERPRETER_DATA"] = dataDir.path
        env["PORT"] = String(port)
        env["NO_BROWSER"] = "1"
        env["PYTHONUNBUFFERED"] = "1"
        env["PATH"] = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        p.environment = env

        let logURL = dataDir.appendingPathComponent("app.log")
        if !FileManager.default.fileExists(atPath: logURL.path) {
            FileManager.default.createFile(atPath: logURL.path, contents: nil)
        }
        if let handle = try? FileHandle(forWritingTo: logURL) {
            handle.seekToEndOfFile()
            p.standardOutput = handle
            p.standardError = handle
        }

        do {
            try p.run()
            process = p
            startedByUs = true
        } catch {
            NSLog("启动 Python 引擎失败: \(error)")
        }
    }

    func stop() {
        guard startedByUs, let p = process, p.isRunning else { return }
        p.terminate()
    }
}
