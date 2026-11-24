import AppKit
import CoreImage.CIFilterBuiltins
import Foundation
import LocalInterpreterCoreBindings
import PDFKit
import SwiftUI

/// Main actor state store bridging the SwiftUI macOS shell with the shared Rust `InterpreterCore`.
@MainActor
final class CoreStore: ObservableObject {
    @Published var courses: [FfiCourse] = []
    @Published var sessions: [FfiSession] = []
    @Published var selectedCourseId: String? = nil
    @Published var selectedSession: FfiSession? = nil
    @Published var segments: [FfiSegment] = []
    @Published var bookmarks: Set<String> = []
    @Published var glossary: [FfiGlossaryEntry] = []
    @Published var questions: [FfiQuestion] = []
    @Published var summaryNotes: [FfiSummaryNote] = []
    @Published var materials: [FfiMaterial] = []
    @Published var offlineModels: [FfiModelStatus] = []
    @Published var searchHits: [FfiSearchHit] = []

    @Published var isCapturing: Bool = false
    @Published var audioLevel: Float = 0.0
    @Published var liveSourceText: String = ""
    @Published var liveTranslatedText: String = ""
    @Published var statusMessage: String = "就绪"
    @Published var syncStatus: String = "未同步"
    @Published var isSyncing: Bool = false
    @Published var assistantBusy: Bool = false
    @Published var downloadingModelId: String? = nil
    @Published var engineStatusText: String = ""

    // Persisted settings
    @AppStorage("li_cloud_url") var cloudRunUrl: String = ""
    @AppStorage("li_cloud_token") var cloudAuthToken: String = ""
    @AppStorage("li_llm_url") var llmBaseUrl: String = "http://127.0.0.1:8080/v1"
    @AppStorage("li_llm_key") var llmApiKey: String = ""
    @AppStorage("li_llm_model") var llmModel: String = "hy-mt2-1.8b"
    @AppStorage("li_asr_url") var asrBaseUrl: String = "http://127.0.0.1:8178/v1"
    @AppStorage("li_asr_key") var asrApiKey: String = ""
    @AppStorage("li_asr_model") var asrModel: String = "whisper-1"
    @AppStorage("li_pause_ms") var pauseMs: Int = 550
    @AppStorage("li_src_lang") var sourceLang: String = "auto"
    @AppStorage("li_tgt_lang") var targetLang: String = "auto"
    @AppStorage("li_overlay_size") var overlayFontSize: Double = 40
    @AppStorage("li_overlay_opacity") var overlayOpacity: Double = 0.82
    @AppStorage("li_auto_sync") var autoSyncAfterClass: Bool = true
    @AppStorage("li_auto_question") var autoDetectQuestions: Bool = true
    @AppStorage("li_hf_mirror") var useHfMirror: Bool = true

    private let core: InterpreterCore
    private let modelsDir: String
    private let overlay = SubtitleOverlayController()
    private let audio = AudioCaptureService()
    private let engines = LocalEngineManager()
    private let vadLock = NSLock()
    private var vadSegmenter: SpeechSegmenterFfi?
    private var vadSessionId: String?
    private let segmentQueue = DispatchQueue(label: "com.localinterpreter.mac.segment")
    private var activeSessionId: String?

    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = appSupport.appendingPathComponent("LocalInterpreter", isDirectory: true)
        let mDir = dir.appendingPathComponent("models", isDirectory: true)
        try? FileManager.default.createDirectory(at: mDir, withIntermediateDirectories: true)
        let dbPath = dir.appendingPathComponent("unified.db").path
        self.modelsDir = mDir.path

        let deviceKey = "li_device_id"
        let deviceId: String
        if let saved = UserDefaults.standard.string(forKey: deviceKey), !saved.isEmpty {
            deviceId = saved
        } else {
            let fresh = "macos-\(UUID().uuidString.prefix(8))"
            UserDefaults.standard.set(fresh, forKey: deviceKey)
            deviceId = fresh
        }

        do {
            self.core = try InterpreterCore.open(dbPath: dbPath, deviceId: deviceId)
        } catch {
            fatalError("Failed to open Rust InterpreterCore database: \(error)")
        }

        audio.onLevel = { [weak self] rms in
            Task { @MainActor [weak self] in
                self?.audioLevel = rms
            }
        }
        audio.onSamples16k = { [weak self] pcm in
            self?.handleCapturedSamplesOffMain(pcm)
        }
        audio.onError = { [weak self] msg in
            Task { @MainActor [weak self] in
                self?.statusMessage = msg
                self?.isCapturing = false
            }
        }

        reloadAll()
    }

    deinit {
        audio.stop()
        engines.stopAll()
    }

    var selectedCourse: FfiCourse? {
        guard let cid = selectedCourseId else { return nil }
        return courses.first { $0.id == cid }
    }

    func reloadAll() {
        do {
            courses = try core.listCourses()
            sessions = try core.listSessions(courseId: selectedCourseId)
            glossary = try core.listGlossary(courseId: selectedCourseId)
            if let cid = selectedCourseId {
                materials = try core.listMaterials(courseId: cid)
            } else {
                materials = []
            }
            if let sid = selectedSession?.id {
                selectedSession = try core.getSession(sessionId: sid)
                segments = try core.listSegments(sessionId: sid)
                let bms = try core.listBookmarks(sessionId: sid)
                bookmarks = Set(bms.map(\.segmentId))
                questions = try core.listQuestions(sessionId: sid)
                summaryNotes = try core.listSummaryNotes(sessionId: sid)
            } else {
                segments = []
                bookmarks = []
                questions = []
                summaryNotes = []
            }
            offlineModels = core.listOfflineModels(modelsDir: modelsDir)
        } catch {
            statusMessage = "加载数据失败：\(error.localizedDescription)"
        }
    }

    func createCourse(name: String, kind: String = "课堂") {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            let created = try core.createCourse(name: trimmed, kind: kind)
            selectedCourseId = created.id
            reloadAll()
        } catch {
            statusMessage = "创建课程失败：\(error.localizedDescription)"
        }
    }

    // MARK: - Live Interpretation

    func startLiveSession(source: AudioCaptureService.Source) {
        do {
            let srcName = source == .system ? "system" : "mic"
            let session = try core.createSession(
                courseId: selectedCourseId,
                audioSource: srcName,
                sourceLang: sourceLang,
                targetLang: targetLang
            )
            activeSessionId = session.id
            selectedSession = session
            let seg = SpeechSegmenterFfi(minSilenceMs: UInt32(max(200, pauseMs)), maxSegmentMs: 15_000)
            vadLock.lock()
            vadSegmenter = seg
            vadSessionId = session.id
            vadLock.unlock()
            isCapturing = true
            statusMessage = source == .system ? "正在采集系统音频（Rust VAD + ASR）…" : "正在采集麦克风（Rust VAD + ASR）…"
            audio.start(source: source)
            reloadAll()
        } catch {
            statusMessage = "启动课次失败：\(error.localizedDescription)"
        }
    }

    func stopLiveSessionAndFinalize() {
        audio.stop()
        isCapturing = false
        audioLevel = 0
        guard let sid = activeSessionId else { return }
        vadLock.lock()
        let tail = vadSegmenter?.flush()
        vadSegmenter = nil
        vadSessionId = nil
        vadLock.unlock()
        if let tail, !tail.samples.isEmpty {
            enqueueAudioSegment(tail, sessionId: sid)
        }
        activeSessionId = nil
        statusMessage = "正在等待尾句转写并提炼核心知识点…"

        let endpoint = FfiLlmEndpoint(baseUrl: llmBaseUrl, apiKey: llmApiKey, model: llmModel)
        let shouldAutoSync = autoSyncAfterClass && !cloudRunUrl.trimmingCharacters(in: .whitespaces).isEmpty
        let coreRef = core
        let queueRef = segmentQueue

        Task.detached(priority: .userInitiated) { [weak self] in
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                queueRef.async { cont.resume() }
            }
            do {
                let updated = try coreRef.finalizeSessionKnowledge(
                    endpoint: endpoint,
                    sessionId: sid,
                    extractGlossary: true
                )
                await MainActor.run {
                    self?.selectedSession = updated
                    self?.statusMessage = "已生成 \(updated.knowledgePoints.count) 条知识点"
                    self?.reloadAll()
                }
                if shouldAutoSync {
                    await self?.syncNow()
                }
            } catch {
                await MainActor.run {
                    self?.statusMessage = "知识点提炼失败：\(error.localizedDescription)"
                }
            }
        }
    }

    /// Runs Rust VAD directly on the background audio capture queue (never wakes @MainActor per frame).
    nonisolated private func handleCapturedSamplesOffMain(_ pcm: [Float]) {
        vadLock.lock()
        let seg = vadSegmenter
        let sid = vadSessionId
        vadLock.unlock()
        guard let seg, let sid else { return }
        let ready = seg.pushSamples(pcm16k: pcm)
        guard !ready.isEmpty else { return }
        Task { @MainActor [weak self] in
            for speech in ready {
                self?.enqueueAudioSegment(speech, sessionId: sid)
            }
        }
    }

    private func enqueueAudioSegment(_ speech: FfiSpeechSegment, sessionId: String) {
        let asrEp = FfiAsrEndpoint(baseUrl: asrBaseUrl, apiKey: asrApiKey, model: asrModel)
        let llmEp = FfiLlmEndpoint(baseUrl: llmBaseUrl, apiKey: llmApiKey, model: llmModel)
        let courseId = selectedCourseId
        let srcHint = sourceLang
        let tgtPref = targetLang
        let shouldAutoQa = autoDetectQuestions
        let coreRef = core

        segmentQueue.async { [weak self] in
            do {
                if let seg = try coreRef.processAudioSegment(
                    asrEndpoint: asrEp,
                    llmEndpoint: llmEp,
                    sessionId: sessionId,
                    courseId: courseId,
                    t0: speech.t0S,
                    t1: speech.t1S,
                    srcLangHint: srcHint,
                    targetPref: tgtPref,
                    pcm16k: speech.samples
                ) {
                    Task { @MainActor [weak self] in
                        self?.liveSourceText = seg.srcText
                        self?.liveTranslatedText = seg.tgtText
                        self?.overlay.setText(source: seg.srcText, translation: seg.tgtText)
                        self?.reloadAll()
                        if shouldAutoQa && isClassroomQuestion(src: seg.srcText, tgt: seg.tgtText) {
                            self?.askAssistant(question: seg.srcText, segmentId: seg.id, sourceTag: "自动捕获")
                        }
                    }
                }
            } catch {
                Task { @MainActor [weak self] in
                    self?.statusMessage = "语音转写/翻译异常：\(error.localizedDescription)"
                }
            }
        }
    }

    // MARK: - Live Classroom Assistant & Course Materials (PDFKit)

    func recapLast5Minutes() {
        guard let sid = activeSessionId ?? selectedSession?.id, !assistantBusy else { return }
        assistantBusy = true
        statusMessage = "助教正在总结近 5 分钟要点…"
        let endpoint = FfiLlmEndpoint(baseUrl: llmBaseUrl, apiKey: llmApiKey, model: llmModel)
        let coreRef = core
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                _ = try coreRef.recapRecentMinutes(endpoint: endpoint, sessionId: sid, minutes: 5)
                await MainActor.run {
                    self?.assistantBusy = false
                    self?.statusMessage = "已生成近 5 分钟课堂总结"
                    self?.reloadAll()
                }
            } catch {
                await MainActor.run {
                    self?.assistantBusy = false
                    self?.statusMessage = "总结失败：\(error.localizedDescription)"
                }
            }
        }
    }

    func askAssistant(question: String, segmentId: String? = nil, sourceTag: String = "提问") {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, let sid = activeSessionId ?? selectedSession?.id, !assistantBusy else { return }
        assistantBusy = true
        statusMessage = "助教正在结合课堂与课件解答…"
        let endpoint = FfiLlmEndpoint(baseUrl: llmBaseUrl, apiKey: llmApiKey, model: llmModel)
        let cid = selectedCourseId
        let coreRef = core
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                _ = try coreRef.askAssistantQuestion(
                    endpoint: endpoint,
                    sessionId: sid,
                    courseId: cid,
                    question: q,
                    segmentId: segmentId,
                    sourceTag: sourceTag
                )
                await MainActor.run {
                    self?.assistantBusy = false
                    self?.statusMessage = "助教已完成解答"
                    self?.reloadAll()
                }
            } catch {
                await MainActor.run {
                    self?.assistantBusy = false
                    self?.statusMessage = "助教解答失败：\(error.localizedDescription)"
                }
            }
        }
    }

    func generateQuizForSelectedSession() {
        guard let sid = selectedSession?.id, !assistantBusy else { return }
        assistantBusy = true
        statusMessage = "正在生成 5 道课后复习自测题…"
        let endpoint = FfiLlmEndpoint(baseUrl: llmBaseUrl, apiKey: llmApiKey, model: llmModel)
        let coreRef = core
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let updated = try coreRef.generateSessionQuiz(endpoint: endpoint, sessionId: sid)
                await MainActor.run {
                    self?.assistantBusy = false
                    self?.selectedSession = updated
                    self?.statusMessage = "已生成课后自测题（同步后可在 iPhone 上刷题）"
                    self?.reloadAll()
                }
            } catch {
                await MainActor.run {
                    self?.assistantBusy = false
                    self?.statusMessage = "生成复习题失败：\(error.localizedDescription)"
                }
            }
        }
    }

    /// Extract per-page text from PDF (via Apple PDFKit) or Markdown/TXT and index into the Rust course store.
    func importCourseMaterial(url: URL) {
        guard let cid = selectedCourseId else {
            statusMessage = "请先在左侧选择或创建一门课程，再导入课件"
            return
        }
        let name = url.lastPathComponent
        var pages: [FfiMaterialPage] = []
        if url.pathExtension.lowercased() == "pdf" {
            if let doc = PDFDocument(url: url) {
                for i in 0..<doc.pageCount {
                    if let text = doc.page(at: i)?.string?.trimmingCharacters(in: .whitespacesAndNewlines),
                       !text.isEmpty {
                        pages.append(FfiMaterialPage(page: UInt32(i + 1), text: text))
                    }
                }
            }
        } else if let text = try? String(contentsOf: url, encoding: .utf8) {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                pages.append(FfiMaterialPage(page: 1, text: trimmed))
            }
        }
        guard !pages.isEmpty else {
            statusMessage = "未能从「\(name)」中提取到可读文字（扫描版 PDF 暂不支持）"
            return
        }
        do {
            let mat = try core.addMaterialPages(courseId: cid, name: name, path: url.path, pages: pages)
            statusMessage = "已导入课件「\(mat.name)」（共 \(mat.pageCount) 页）"
            reloadAll()
        } catch {
            statusMessage = "导入课件失败：\(error.localizedDescription)"
        }
    }

    func deleteMaterial(_ id: String) {
        do {
            try core.deleteMaterial(materialId: id)
            reloadAll()
        } catch {
            statusMessage = "删除课件失败：\(error.localizedDescription)"
        }
    }

    func generateOutlineForSelectedCourse() {
        guard let cid = selectedCourseId, !assistantBusy else { return }
        assistantBusy = true
        statusMessage = "正在根据课程课件生成预习提纲…"
        let endpoint = FfiLlmEndpoint(baseUrl: llmBaseUrl, apiKey: llmApiKey, model: llmModel)
        let coreRef = core
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                _ = try coreRef.generateCourseOutline(endpoint: endpoint, courseId: cid)
                await MainActor.run {
                    self?.assistantBusy = false
                    self?.statusMessage = "已生成课程预习提纲"
                    self?.reloadAll()
                }
            } catch {
                await MainActor.run {
                    self?.assistantBusy = false
                    self?.statusMessage = "生成提纲失败：\(error.localizedDescription)"
                }
            }
        }
    }

    // MARK: - Offline Models & Local Metal Engine Launcher

    func downloadModel(id: String) {
        guard downloadingModelId == nil else { return }
        downloadingModelId = id
        statusMessage = "正在后台断点续传下载模型 \(id)…"
        let dir = modelsDir
        let mirror = useHfMirror
        let coreRef = core
        Task.detached(priority: .utility) { [weak self] in
            do {
                let path = try coreRef.downloadOfflineModel(modelsDir: dir, modelId: id, useHfMirror: mirror)
                await MainActor.run {
                    self?.downloadingModelId = nil
                    self?.statusMessage = "模型下载完成：\(URL(fileURLWithPath: path).lastPathComponent)"
                    self?.reloadAll()
                }
            } catch {
                await MainActor.run {
                    self?.downloadingModelId = nil
                    self?.statusMessage = "模型下载失败：\(error.localizedDescription)"
                    self?.reloadAll()
                }
            }
        }
    }

    func deleteModel(id: String) {
        _ = try? core.deleteOfflineModel(modelsDir: modelsDir, modelId: id)
        reloadAll()
    }

    /// Launch local `llama-server` & `whisper-server` with Apple Silicon Metal offload using downloaded models.
    func launchLocalOfflineServers() {
        let ready = core.listOfflineModels(modelsDir: modelsDir).filter(\.isReady)
        var launched: [String] = []
        if let llmModelFile = ready.first(where: { $0.role == "translator" || $0.role == "assistant" }) {
            let ok = engines.launchLocalBinary(
                name: "llama-server",
                arguments: ["-m", llmModelFile.localPath, "--port", "8080", "-ngl", "99", "-c", "4096"]
            )
            if ok {
                llmBaseUrl = "http://127.0.0.1:8080/v1"
                llmModel = llmModelFile.id
                launched.append("llama-server(Metal)")
            }
        }
        if let asrModelFile = ready.first(where: { $0.role == "asr" }) {
            let ok = engines.launchLocalBinary(
                name: "whisper-server",
                arguments: ["-m", asrModelFile.localPath, "--port", "8178", "--inference-path", "/v1/audio/transcriptions"]
            )
            if ok {
                asrBaseUrl = "http://127.0.0.1:8178/v1"
                asrModel = asrModelFile.id
                launched.append("whisper-server")
            }
        }
        statusMessage = launched.isEmpty
            ? "未检测到已下载模型或本机 llama-server/whisper-server（可用 brew install llama.cpp whisper-cpp 安装）"
            : "已拉起本机离线服务：\(launched.joined(separator: " + "))"
        Task { await probeLocalEngines() }
    }

    func probeLocalEngines() async {
        let llmOk = await engines.probeHealth(baseUrl: llmBaseUrl)
        let asrOk = await engines.probeHealth(baseUrl: asrBaseUrl)
        engineStatusText = "ASR: \(asrOk ? "✓ 在线" : "✗ 未连接")  |  LLM: \(llmOk ? "✓ 在线" : "✗ 未连接")"
    }

    // MARK: - Bookmarks, Overlay, Export, Legacy Migration & Google Cloud Sync

    func toggleBookmark(sessionId: String, segmentId: String) {
        do {
            _ = try core.toggleBookmark(sessionId: sessionId, segmentId: segmentId, note: "Mac 收藏")
            reloadAll()
        } catch {
            statusMessage = "书签操作失败：\(error.localizedDescription)"
        }
    }

    func toggleOverlay() {
        if overlay.isVisible {
            overlay.hide()
        } else {
            overlay.show(size: overlayFontSize, opacity: overlayOpacity)
        }
    }

    func applyOverlayStyle() {
        overlay.apply(size: overlayFontSize, opacity: overlayOpacity)
    }

    func search(_ query: String) {
        do {
            searchHits = try core.search(query: query, limit: 40)
        } catch {
            searchHits = []
        }
    }

    func exportMarkdown(sessionId: String) -> String {
        (try? core.exportSessionMarkdown(sessionId: sessionId)) ?? ""
    }

    func importLegacyMacDatabase(path: String) {
        do {
            let summary = try core.importLegacyDatabase(legacyDbPath: path, sourceTag: "macos-legacy")
            statusMessage = "导入完成：\(summary.courses) 门课，\(summary.sessions) 课次，\(summary.segments) 条字幕"
            reloadAll()
        } catch {
            statusMessage = "导入旧库失败：\(error.localizedDescription)"
        }
    }

    func syncNow() async {
        let url = cloudRunUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else {
            syncStatus = "请先在设置中填写 Google Cloud Run 地址"
            return
        }
        guard !isSyncing else { return }
        isSyncing = true
        syncStatus = "正在与 Google Cloud 同步…"
        let token = cloudAuthToken
        let coreRef = core

        let result: Result<FfiSyncStats, Error> = await Task.detached(priority: .utility) {
            do {
                let stats = try coreRef.syncCloudRun(baseUrl: url, authToken: token)
                return .success(stats)
            } catch {
                return .failure(error)
            }
        }.value

        isSyncing = false
        switch result {
        case .success(let stats):
            syncStatus = "同步成功：上传 \(stats.pushedItems) 项，拉取 \(stats.pulledItems) 项"
            reloadAll()
        case .failure(let error):
            syncStatus = "同步失败：\(error.localizedDescription)"
        }
    }

    func makePwaQrImage() -> NSImage? {
        let url = cloudRunUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty, let data = url.data(using: .utf8) else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.setValue(data, forKey: "inputMessage")
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let rep = NSCIImageRep(ciImage: scaled)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}
