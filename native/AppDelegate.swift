import AppKit
import UniformTypeIdentifiers
import WebKit

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
    private var window: NSWindow!
    private var webView: WKWebView!
    private var loadedUI = false
    private var currentSource: AudioCapture.Source = .mic

    private let server = ServerManager()
    private let audio = AudioCapture()
    private let link = ServerLink()
    private let overlay = SubtitleOverlayController()

    // MARK: - 生命周期

    func applicationDidFinishLaunching(_ notification: Notification) {
        setupMenu()
        setupWindow()
        loadPlaceholder()
        audio.delegate = self
        audio.onLog = { [weak self] text in self?.log(text) }
        server.startIfNeeded { [weak self] ok in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if ok {
                    self.loadUI()
                } else {
                    self.showFatal("本地引擎启动失败", detail: "请查看日志：\(self.server.dataDir.path)/app.log")
                }
            }
        }
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationWillTerminate(_ notification: Notification) {
        audio.stop()
        link.close()
        server.stop()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    // MARK: - 界面

    private func setupMenu() {
        let mainMenu = NSMenu()

        let appItem = NSMenuItem()
        mainMenu.addItem(appItem)
        let appMenu = NSMenu()
        appMenu.addItem(
            withTitle: "关于 本地同传",
            action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)),
            keyEquivalent: ""
        )
        appMenu.addItem(NSMenuItem.separator())
        appMenu.addItem(
            withTitle: "退出 本地同传",
            action: #selector(NSApplication.terminate(_:)),
            keyEquivalent: "q"
        )
        appItem.submenu = appMenu

        let subtitleItem = NSMenuItem()
        mainMenu.addItem(subtitleItem)
        let subtitleMenu = NSMenu(title: "字幕")
        let overlayItem = NSMenuItem(
            title: "悬浮字幕",
            action: #selector(toggleOverlayMenu),
            keyEquivalent: "f"
        )
        overlayItem.keyEquivalentModifierMask = [.command, .shift]
        overlayItem.target = self
        subtitleMenu.addItem(overlayItem)
        let subtitleModeItem = NSMenuItem(
            title: "全屏字幕模式",
            action: #selector(toggleSubtitleMenu),
            keyEquivalent: "s"
        )
        subtitleModeItem.keyEquivalentModifierMask = [.command, .shift]
        subtitleModeItem.target = self
        subtitleMenu.addItem(subtitleModeItem)
        subtitleMenu.addItem(NSMenuItem.separator())
        let runItem = NSMenuItem(
            title: "开始 / 停止",
            action: #selector(toggleRunMenu),
            keyEquivalent: "\r"
        )
        runItem.keyEquivalentModifierMask = [.command, .shift]
        runItem.target = self
        subtitleMenu.addItem(runItem)
        let bookmarkItem = NSMenuItem(
            title: "标记当前句",
            action: #selector(bookmarkMenu),
            keyEquivalent: "b"
        )
        bookmarkItem.keyEquivalentModifierMask = [.command, .shift]
        bookmarkItem.target = self
        subtitleMenu.addItem(bookmarkItem)
        subtitleItem.submenu = subtitleMenu

        let editItem = NSMenuItem()
        mainMenu.addItem(editItem)
        let editMenu = NSMenu(title: "编辑")
        editMenu.addItem(withTitle: "撤销", action: Selector(("undo:")), keyEquivalent: "z")
        editMenu.addItem(withTitle: "重做", action: Selector(("redo:")), keyEquivalent: "Z")
        editMenu.addItem(NSMenuItem.separator())
        editMenu.addItem(withTitle: "剪切", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        editMenu.addItem(withTitle: "复制", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editMenu.addItem(withTitle: "粘贴", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editMenu.addItem(withTitle: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = editMenu

        NSApp.mainMenu = mainMenu
    }

    private func setupWindow() {
        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: "audio")
        if #available(macOS 13.3, *) {
            config.preferences.isElementFullscreenEnabled = true
        }

        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.setValue(false, forKey: "drawsBackground")
        log("webview configured")

        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1320, height: 880),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "本地同传"
        window.titlebarAppearsTransparent = true
        window.appearance = NSAppearance(named: .darkAqua)
        window.backgroundColor = NSColor(calibratedWhite: 0.045, alpha: 1)
        window.contentView = webView
        window.minSize = NSSize(width: 980, height: 640)
        if !window.setFrameUsingName("LocalInterpreterMain") {
            window.center()
        }
        window.setFrameAutosaveName("LocalInterpreterMain")
        window.makeKeyAndOrderFront(nil)
    }

    private func loadPlaceholder() {
        let html = """
        <!doctype html><html><head><meta charset="utf-8">
        <style>
          html,body{height:100%;margin:0;background:#0b0d10;color:#9aa3af;
            font-family:-apple-system,"PingFang SC",sans-serif;
            display:flex;align-items:center;justify-content:center}
          .box{text-align:center;line-height:1.8}
          .dot{display:inline-block;width:8px;height:8px;border-radius:50%;
            background:#4fd1a5;margin-right:8px;animation:p 1.2s infinite}
          @keyframes p{0%,100%{opacity:1}50%{opacity:.25}}
        </style></head><body><div class="box">
          <div><span class="dot"></span>正在启动本地引擎…</div>
          <div style="font-size:12px;color:#6b7480">首次启动加载语音模型可能需要一点时间</div>
        </div></body></html>
        """
        webView.loadHTMLString(html, baseURL: nil)
    }

    private func loadUI() {
        guard !loadedUI else { return }
        loadedUI = true
        log("loading UI at \(server.baseURL)")
        webView.load(
            URLRequest(
                url: server.baseURL,
                cachePolicy: .reloadIgnoringLocalCacheData,
                timeoutInterval: 30
            )
        )
        maybeAutoStart()
    }

    private func maybeAutoStart() {
        guard let source = ProcessInfo.processInfo.environment["LI_AUTOSTART"],
              source == "mic" || source == "system"
        else { return }
        log("autostart source=\(source)")
        if let seconds = Double(ProcessInfo.processInfo.environment["LI_AUTOSTOP"] ?? "") {
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
                self?.stopSession(notify: true)
            }
        }
        startSession(config: ["source": source])
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        log("page finished: \(webView.url?.absoluteString ?? "?")")
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        log("page failed: \(error.localizedDescription)")
    }

    private func showFatal(_ title: String, detail: String) {
        let html = """
        <!doctype html><html><head><meta charset="utf-8">
        <style>html,body{height:100%;margin:0;background:#0b0d10;color:#e8eaed;
          font-family:-apple-system,"PingFang SC",sans-serif;display:flex;
          align-items:center;justify-content:center;text-align:center}
          h2{color:#f07070;margin:0 0 10px}p{color:#9aa3af;font-size:13px;max-width:560px;line-height:1.7}</style>
        </head><body><div><h2>\(title)</h2><p>\(detail)</p></div></body></html>
        """
        webView.loadHTMLString(html, baseURL: nil)
    }

    // MARK: - JS 桥

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard message.name == "audio",
              let body = message.body as? [String: Any],
              let action = body["action"] as? String
        else {
            log("bridge message ignored: \(message.name)")
            return
        }
        log("bridge action: \(action)")

        switch action {
        case "start":
            let source = (body["config"] as? [String: Any])?["source"] as? String ?? "mic"
            log("start session source=\(source)")
            startSession(config: body["config"] as? [String: Any] ?? [:])
        case "stop":
            log("stop session")
            stopSession(notify: true)
        case "save_text":
            saveText(
                filename: body["filename"] as? String ?? "export.txt",
                text: body["text"] as? String ?? ""
            )
        case "pause":
            audio.stop()
            if let data = try? JSONSerialization.data(
                withJSONObject: ["type": "pause"]
            ), let json = String(data: data, encoding: .utf8) {
                link.send(string: json)
            }
        case "resume":
            audio.start(source: currentSource)
        case "overlay":
            let on = body["on"] as? Bool ?? false
            let size = body["size"] as? Double ?? 46
            let opacity = body["opacity"] as? Double ?? 0.82
            if on {
                overlay.show(size: size, opacity: opacity)
            } else {
                overlay.hide()
            }
        case "overlay_text":
            overlay.setText(
                source: body["src"] as? String ?? "",
                translation: body["dst"] as? String ?? ""
            )
        case "bookmark":
            if let data = try? JSONSerialization.data(
                withJSONObject: ["type": "bookmark"]
            ), let json = String(data: data, encoding: .utf8) {
                link.send(string: json)
            }
        case "recap":
            if let data = try? JSONSerialization.data(
                withJSONObject: ["type": "recap", "minutes": 5]
            ), let json = String(data: data, encoding: .utf8) {
                link.send(string: json)
            }
        case "ask":
            if let text = body["text"] as? String, !text.isEmpty {
                let payload = ["type": "ask", "text": text]
                if let data = try? JSONSerialization.data(withJSONObject: payload),
                   let json = String(data: data, encoding: .utf8) {
                    link.send(string: json)
                }
            }
        case "ping":
            log("JS bridge ready")
            forward(object: ["type": "native_ready"])
        case "quit":
            NSApp.terminate(nil)
        default:
            break
        }
    }

    // MARK: - 文件选择与保存

    func webView(
        _ webView: WKWebView,
        runOpenPanelWith parameters: WKOpenPanelParameters,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping ([URL]?) -> Void
    ) {
        let panel = NSOpenPanel()
        panel.title = "选择课件"
        panel.message = "支持 PDF / DOCX / PPTX / TXT / Markdown"
        panel.allowsMultipleSelection = parameters.allowsMultipleSelection
        panel.canChooseDirectories = parameters.allowsDirectories
        panel.canChooseFiles = true
        var types: [UTType] = [.pdf, .plainText]
        for ext in ["docx", "pptx", "md", "markdown"] {
            if let type = UTType(filenameExtension: ext) {
                types.append(type)
            }
        }
        panel.allowedContentTypes = types
        panel.allowsOtherFileTypes = true
        if let window = window {
            panel.beginSheetModal(for: window) { response in
                completionHandler(response == .OK ? panel.urls : nil)
            }
        } else {
            completionHandler(nil)
        }
    }

    private func saveText(filename: String, text: String) {
        let panel = NSSavePanel()
        panel.title = "保存"
        panel.nameFieldStringValue =
            filename.replacingOccurrences(of: "/", with: "_")
        panel.canCreateDirectories = true
        panel.isExtensionHidden = false
        let write: (URL) -> Void = { [weak self] url in
            do {
                try text.write(to: url, atomically: true, encoding: .utf8)
                self?.forward(object: ["type": "native_saved", "path": url.path])
            } catch {
                self?.forward(
                    object: [
                        "type": "native_save_error",
                        "message": "保存失败：\(error.localizedDescription)",
                    ]
                )
            }
        }
        if let window = window {
            panel.beginSheetModal(for: window) { response in
                guard response == .OK, let url = panel.url else { return }
                write(url)
            }
        } else if panel.runModal() == .OK, let url = panel.url {
            write(url)
        }
    }

    // MARK: - 菜单快捷键

    @objc private func toggleOverlayMenu() {
        forward(object: ["type": "hotkey", "action": "overlay"])
    }

    @objc private func toggleSubtitleMenu() {
        forward(object: ["type": "hotkey", "action": "subtitle"])
    }

    @objc private func bookmarkMenu() {
        forward(object: ["type": "hotkey", "action": "bookmark"])
    }

    @objc private func toggleRunMenu() {
        forward(object: ["type": "hotkey", "action": "toggle"])
    }

    private func log(_ text: String) {
        NSLog("[native] %@", text)
        let url = server.dataDir.appendingPathComponent("native.log")
        let fd = open(url.path, O_WRONLY | O_CREAT | O_APPEND, 0o644)
        guard fd >= 0 else { return }
        let line = "[native] \(text)\n"
        _ = line.withCString { write(fd, $0, strlen($0)) }
        close(fd)
    }

    private func startSession(config: [String: Any]) {
        stopSession()
        var start = config
        start["type"] = "start"
        start["sample_rate"] = 16000
        guard let data = try? JSONSerialization.data(withJSONObject: start),
              let json = String(data: data, encoding: .utf8)
        else { return }

        link.onEvent = { [weak self] text in
            DispatchQueue.main.async { self?.forward(json: text) }
        }
        link.onClosed = { [weak self] reason in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.audio.stop()
                self.forward(object: ["type": "native_stopped", "reason": reason])
            }
        }
        link.connect(url: server.wsURL, startMessage: json)

        let source: AudioCapture.Source =
            (config["source"] as? String == "system") ? .system : .mic
        currentSource = source
        audio.start(source: source)
    }

    private func stopSession(notify: Bool = false) {
        audio.stop()
        link.close()
        if notify {
            forward(object: ["type": "native_stopped"])
        }
    }

    private var loggedEventTypes = Set<String>()

    private func forward(json: String) {
        if let start = json.range(of: "\"type\":\""),
           let end = json[start.upperBound...].firstIndex(of: "\"") {
            let type = String(json[start.upperBound..<end])
            if !loggedEventTypes.contains(type) {
                loggedEventTypes.insert(type)
                log("event \(type): \(String(json.prefix(220)))")
            }
        }
        let js = "window.nativeEvent && window.nativeEvent(\(json));"
        webView.evaluateJavaScript(js, completionHandler: nil)
    }

    private func forward(object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let json = String(data: data, encoding: .utf8)
        else { return }
        forward(json: json)
    }
}

extension AppDelegate: AudioCaptureDelegate {
    func audioCapture(didProduce samples: Data) {
        link.send(data: samples)
    }

    func audioCapture(didFail message: String) {
        forward(object: ["type": "native_error", "message": message])
    }
}
