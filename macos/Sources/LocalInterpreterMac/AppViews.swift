import AppKit
import LocalInterpreterCoreBindings
import SwiftUI
import UniformTypeIdentifiers

@main
struct LocalInterpreterMacApp: App {
    @StateObject private var store = CoreStore()

    var body: some Scene {
        WindowGroup("离线同传 · macOS") {
            MainShellView()
                .environmentObject(store)
                .frame(minWidth: 960, minHeight: 640)
        }
    }
}

enum SidebarTab: String, CaseIterable, Identifiable {
    case live = "实时同传"
    case knowledge = "知识点库"
    case glossary = "课程术语"
    case sync = "云同步 & 设置"

    var id: String { rawValue }
    var icon: String {
        switch self {
        case .live: return "waveform"
        case .knowledge: return "sparkles"
        case .glossary: return "book.closed"
        case .sync: return "icloud.and.arrow.up"
        }
    }
}

struct MainShellView: View {
    @EnvironmentObject var store: CoreStore
    @State private var tab: SidebarTab = .live
    @State private var newCourseName = ""

    var body: some View {
        NavigationSplitView {
            List(selection: $tab) {
                Section("导航") {
                    ForEach(SidebarTab.allCases) { item in
                        Label(item.rawValue, systemImage: item.icon).tag(item)
                    }
                }
                Section("课程") {
                    Button("全部课程") {
                        store.selectedCourseId = nil
                        store.reloadAll()
                    }
                    .buttonStyle(.plain)
                    .fontWeight(store.selectedCourseId == nil ? .semibold : .regular)

                    ForEach(store.courses, id: \.id) { c in
                        Button(c.name) {
                            store.selectedCourseId = c.id
                            store.reloadAll()
                        }
                        .buttonStyle(.plain)
                        .fontWeight(store.selectedCourseId == c.id ? .semibold : .regular)
                    }
                    HStack {
                        TextField("新课程名称", text: $newCourseName)
                            .textFieldStyle(.roundedBorder)
                        Button("+") {
                            store.createCourse(name: newCourseName)
                            newCourseName = ""
                        }
                    }
                }
            }
            .listStyle(.sidebar)
        } detail: {
            VStack(spacing: 0) {
                switch tab {
                case .live:
                    LiveInterpreterView()
                case .knowledge:
                    KnowledgeLibraryView()
                case .glossary:
                    GlossaryView()
                case .sync:
                    SyncAndSettingsView()
                }
                Divider()
                HStack {
                    Text(store.statusMessage).font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Text(store.syncStatus).font(.caption).foregroundStyle(.secondary)
                    Button("立即同步 Google Cloud") {
                        Task { await store.syncNow() }
                    }
                    .disabled(store.isSyncing)
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
            }
        }
    }
}

struct LiveInterpreterView: View {
    @EnvironmentObject var store: CoreStore

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                if !store.isCapturing {
                    Button("开始内录系统声音") { store.startLiveSession(source: .system) }
                        .buttonStyle(.borderedProminent)
                    Button("开始麦克风同传") { store.startLiveSession(source: .mic) }
                        .buttonStyle(.bordered)
                } else {
                    Button("下课并提炼知识点") { store.stopLiveSessionAndFinalize() }
                        .buttonStyle(.borderedProminent)
                        .tint(.red)
                }
                Button("悬浮字幕窗") { store.toggleOverlay() }
                    .buttonStyle(.bordered)
                Spacer()
                ProgressView(value: Double(min(1.0, store.audioLevel * 4.0)))
                    .frame(width: 90)
                Text("\(displayLanguageName(code: store.sourceLang)) → \(displayLanguageName(code: store.targetLang))")
                    .font(.caption.monospaced())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(.quaternary, in: Capsule())
            }
            .padding(.horizontal)
            .padding(.top, 12)

            List(store.segments, id: \.id) { seg in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(seg.srcText).font(.subheadline).foregroundStyle(.secondary)
                        Spacer()
                        if let sid = store.selectedSession?.id {
                            Button {
                                store.toggleBookmark(sessionId: sid, segmentId: seg.id)
                            } label: {
                                Image(systemName: store.bookmarks.contains(seg.id) ? "bookmark.fill" : "bookmark")
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Text(seg.tgtText).font(.body.weight(.medium))
                }
                .padding(.vertical, 4)
            }
        }
    }
}

struct KnowledgeLibraryView: View {
    @EnvironmentObject var store: CoreStore
    @State private var searchText = ""

    var body: some View {
        HSplitView {
            VStack(spacing: 8) {
                TextField("搜索知识点、术语、字幕…", text: $searchText)
                    .textFieldStyle(.roundedBorder)
                    .padding(10)
                    .onChange(of: searchText) { newValue in
                        store.search(newValue)
                    }
                List(store.sessions, id: \.id) { s in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.title.isEmpty ? "未命名课次" : s.title).font(.headline)
                        Text("知识点 \(s.knowledgePoints.count) 条 · \(s.summary)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        store.selectedSession = s
                        store.reloadAll()
                    }
                }
            }
            .frame(minWidth: 280)

            ScrollView {
                if let s = store.selectedSession {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Text(s.title.isEmpty ? "未命名课次" : s.title).font(.title2.bold())
                            Spacer()
                            Button("复制 Markdown") {
                                let md = store.exportMarkdown(sessionId: s.id)
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(md, forType: .string)
                                store.statusMessage = "已复制 Markdown 知识点到剪贴板"
                            }
                        }
                        if !s.summary.isEmpty {
                            GroupBox("课堂总结") {
                                Text(s.summary).frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                        GroupBox("核心知识点（自动同步至 iPhone）") {
                            if s.knowledgePoints.isEmpty {
                                Text("下课后自动提炼知识点").foregroundStyle(.secondary)
                            } else {
                                VStack(alignment: .leading, spacing: 10) {
                                    ForEach(Array(s.knowledgePoints.enumerated()), id: \.offset) { _, kp in
                                        VStack(alignment: .leading, spacing: 4) {
                                            HStack {
                                                Text(kp.title).font(.headline)
                                                Spacer()
                                                Text(String(repeating: "★", count: Int(max(1, min(3, kp.importance)))))
                                                    .foregroundStyle(.orange)
                                            }
                                            Text(kp.explanation).font(.body)
                                            if !kp.sourceQuote.isEmpty {
                                                Text("摘录：\(kp.sourceQuote)")
                                                    .font(.caption)
                                                    .foregroundStyle(.secondary)
                                            }
                                        }
                                        Divider()
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                } else {
                    Text("从左侧选择一节课查看知识点与双语总结")
                        .foregroundStyle(.secondary)
                        .padding(40)
                }
            }
        }
    }
}

struct GlossaryView: View {
    @EnvironmentObject var store: CoreStore

    var body: some View {
        List(store.glossary, id: \.id) { item in
            HStack {
                VStack(alignment: .leading) {
                    Text(item.term).font(.headline)
                    if !item.note.isEmpty {
                        Text(item.note).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text(item.translation).foregroundStyle(.blue)
            }
            .padding(.vertical, 3)
        }
    }
}

struct SyncAndSettingsView: View {
    @EnvironmentObject var store: CoreStore

    var body: some View {
        Form {
            Section("Google Cloud 同步 & iPhone 知识点复习") {
                TextField("Cloud Run 服务地址 (https://...run.app)", text: $store.cloudRunUrl)
                SecureField("Sync Auth Token", text: $store.cloudAuthToken)
                Toggle("下课提炼知识点后自动同步到 Google Cloud", isOn: $store.autoSyncAfterClass)
                HStack(spacing: 16) {
                    Button("立即同步") { Task { await store.syncNow() } }
                        .buttonStyle(.borderedProminent)
                    if let qr = store.makePwaQrImage() {
                        VStack(alignment: .leading, spacing: 4) {
                            Image(nsImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .frame(width: 110, height: 110)
                            Text("iPhone 扫码用 Safari 打开并「添加到主屏幕」")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section("语音识别 & 翻译引擎（本地 whisper-server / llama-server / OpenAI 兼容）") {
                TextField("ASR 地址 (whisper-server / SenseVoice)", text: $store.asrBaseUrl)
                TextField("ASR 模型", text: $store.asrModel)
                SecureField("ASR API Key（本地留空）", text: $store.asrApiKey)
                Divider()
                TextField("LLM 地址 (llama-server / MLX / OpenAI)", text: $store.llmBaseUrl)
                TextField("LLM 模型", text: $store.llmModel)
                SecureField("LLM API Key（本地留空）", text: $store.llmApiKey)
                HStack {
                    Button("检测引擎连接") { Task { await store.probeLocalEngines() } }
                    if !store.engineStatusText.isEmpty {
                        Text(store.engineStatusText).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }

            Section("悬浮字幕样式") {
                Slider(value: $store.overlayFontSize, in: 20...72, step: 2) {
                    Text("字号 \(Int(store.overlayFontSize))")
                }
                .onChange(of: store.overlayFontSize) { _ in store.applyOverlayStyle() }
                Slider(value: $store.overlayOpacity, in: 0.25...0.95, step: 0.05) {
                    Text("不透明度 \(Int(store.overlayOpacity * 100))%")
                }
                .onChange(of: store.overlayOpacity) { _ in store.applyOverlayStyle() }
            }

            Section("旧版数据迁移") {
                Button("导入旧版 Mac history.db（无损只读）") {
                    let panel = NSOpenPanel()
                    panel.allowedContentTypes = [.database, .data]
                    panel.allowsMultipleSelection = false
                    if panel.runModal() == .OK, let url = panel.url {
                        store.importLegacyMacDatabase(path: url.path)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .padding()
    }
}
