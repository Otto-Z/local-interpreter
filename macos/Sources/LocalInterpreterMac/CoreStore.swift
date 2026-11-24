import AppKit
import CoreImage.CIFilterBuiltins
import Foundation
import LocalInterpreterCoreBindings
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
    @Published var searchHits: [FfiSearchHit] = []

    @Published var isCapturing: Bool = false
    @Published var audioLevel: Float = 0.0
    @Published var liveSourceText: String = ""
    @Published var liveTranslatedText: String = ""
    @Published var statusMessage: String = "就绪"
    @Published var syncStatus: String = "未同步"
    @Published var isSyncing: Bool = false

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

    @Published var engineStatusText: String = ""

    private let core: InterpreterCore
    private let overlay = SubtitleOverlayController()
    private let audio = AudioCaptureService()
    private let engines = LocalEngineManager()
    private var segmenter: SpeechSegmenterFfi?
    private let segmentQueue = DispatchQueue(label: "com.localinterpreter.mac.segment")
    private var activeSessionId: String?

    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = appSupport.appendingPathComponent("LocalInterpreter", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dbPath = dir.appendingPathComponent("unified.db").path

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
            self?.handleCapturedSamples(pcm)
        }
        audio.onError = { [weak self] msg in
            Task { @MainActor [weak self] in
                self?.statusMessage = msg
                self?.isCapturing = false
            }
        }

        reloadAll()
    }

    func reloadAll() {
        do {
            courses = try core.listCourses()
            sessions = try core.listSessions(courseId: selectedCourseId)
            glossary = try core.listGlossary(courseId: selectedCourseId)
            if let sid = selectedSession?.id {
                selectedSession = try core.getSession(sessionId: sid)
                segments = try core.listSegments(sessionId: sid)
                let bms = try core.listBookmarks(sessionId: sid)
                bookmarks = Set(bms.map(\.segmentId))
            }
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
            segmenter = SpeechSegmenterFfi(minSilenceMs: UInt32(max(200, pauseMs)), maxSegmentMs: 15_000)
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
        if let tail = segmenter?.flush(), !tail.samples.isEmpty {
            enqueueAudioSegment(tail, sessionId: sid)
        }
        segmenter = nil
        activeSessionId = nil
        statusMessage = "正在等待尾句转写并提炼核心知识点…"

        let endpoint = FfiLlmEndpoint(baseUrl: llmBaseUrl, apiKey: llmApiKey, model: llmModel)
        let shouldAutoSync = autoSyncAfterClass && !cloudRunUrl.trimmingCharacters(in: .whitespaces).isEmpty
        let coreRef = core
        let queueRef = segmentQueue

        Task.detached(priority: .userInitiated) { [weak self] in
            // Wait for any in-flight ASR/translation segments (including flushed tail) to persist first.
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

    nonisolated private func handleCapturedSamples(_ pcm: [Float]) {
        Task { @MainActor [weak self] in
            guard let self, let sid = self.activeSessionId, let seg = self.segmenter else { return }
            let ready = seg.pushSamples(pcm16k: pcm)
            for speech in ready {
                self.enqueueAudioSegment(speech, sessionId: sid)
            }
        }
    }

    private func enqueueAudioSegment(_ speech: FfiSpeechSegment, sessionId: String) {
        let asrEp = FfiAsrEndpoint(baseUrl: asrBaseUrl, apiKey: asrApiKey, model: asrModel)
        let llmEp = FfiLlmEndpoint(baseUrl: llmBaseUrl, apiKey: llmApiKey, model: llmModel)
        let courseId = selectedCourseId
        let srcHint = sourceLang
        let tgtPref = targetLang
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
                    }
                }
            } catch {
                Task { @MainActor [weak self] in
                    self?.statusMessage = "语音转写/翻译异常：\(error.localizedDescription)"
                }
            }
        }
    }

    func probeLocalEngines() async {
        let llmOk = await engines.probeHealth(baseUrl: llmBaseUrl)
        let asrOk = await engines.probeHealth(baseUrl: asrBaseUrl)
        engineStatusText = "ASR: \(asrOk ? "✓ 在线" : "✗ 未连接")  |  LLM: \(llmOk ? "✓ 在线" : "✗ 未连接")"
    }

    /// Submit a recognized utterance (from local ASR or manual test) for real-time translation + overlay update.
    func submitRecognizedUtterance(text: String, t0: Double, t1: Double) {
        guard let sid = activeSessionId ?? selectedSession?.id else { return }
        let endpoint = FfiLlmEndpoint(baseUrl: llmBaseUrl, apiKey: llmApiKey, model: llmModel)
        let courseId = selectedCourseId
        let srcHint = sourceLang
        let tgtPref = targetLang
        let coreRef = core

        liveSourceText = text
        overlay.setText(source: text, translation: "…")

        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let seg = try coreRef.processUtterance(
                    endpoint: endpoint,
                    sessionId: sid,
                    courseId: courseId,
                    t0: t0,
                    t1: t1,
                    srcLangHint: srcHint,
                    targetPref: tgtPref,
                    srcText: text,
                    lowConf: false
                )
                await MainActor.run {
                    self?.liveTranslatedText = seg.tgtText
                    self?.overlay.setText(source: seg.srcText, translation: seg.tgtText)
                    self?.reloadAll()
                }
            } catch {
                await MainActor.run {
                    self?.statusMessage = "翻译失败：\(error.localizedDescription)"
                }
            }
        }
    }

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

    /// Import legacy Python macOS `history.db` losslessly into the unified UUIDv7 SQLite store.
    func importLegacyMacDatabase(path: String) {
        do {
            let summary = try core.importLegacyDatabase(legacyDbPath: path, sourceTag: "macos-legacy")
            statusMessage = "导入完成：\(summary.courses) 门课，\(summary.sessions) 课次，\(summary.segments) 条字幕"
            reloadAll()
        } catch {
            statusMessage = "导入旧库失败：\(error.localizedDescription)"
        }
    }

    /// Push local lessons/knowledge points and pull iPhone/Android edits via Google Cloud Run.
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

    /// Generate a QR code NSImage for scanning the Google Cloud iPhone Knowledge PWA directly.
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
