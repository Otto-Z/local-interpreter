import AppKit

/// 整个悬浮窗都可以拖动（文字标签默认会吃掉鼠标事件）。
private final class OverlayContentView: NSView {
    override var mouseDownCanMoveWindow: Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? {
        bounds.contains(point) ? self : nil
    }
}

/// 置顶悬浮字幕：一个不抢焦点的透明面板，浮在其它应用上方。
/// 只显示当前一句的原文与译文，可拖动；字号和背景不透明度跟随设置。
final class SubtitleOverlayController {
    private var panel: NSPanel?
    private var effect: NSVisualEffectView?
    private var srcLabel: NSTextField?
    private var dstLabel: NSTextField?
    private var fontSize: CGFloat = 42
    private var backgroundOpacity: CGFloat = 0.82
    private(set) var isVisible = false

    private let panelWidth: CGFloat = 940
    private let padding: CGFloat = 26

    func show(size: Double, opacity: Double) {
        fontSize = CGFloat(max(20, min(80, size)))
        backgroundOpacity = CGFloat(max(0.2, min(0.95, opacity)))
        let panel = ensurePanel()
        applyStyle()
        if let screen = NSScreen.main {
            let frame = screen.visibleFrame
            let savedKey = "NSWindow Frame LocalInterpreterSubtitleOverlay"
            let hasSaved = UserDefaults.standard.object(forKey: savedKey) != nil
            var rect = panel.frame
            rect.size.width = panelWidth
            if !hasSaved {
                rect.origin = NSPoint(
                    x: frame.midX - panelWidth / 2,
                    y: frame.minY + 90
                )
            } else {
                rect.origin.x = min(
                    max(frame.minX, rect.origin.x),
                    frame.maxX - rect.width
                )
                rect.origin.y = min(
                    max(frame.minY, rect.origin.y),
                    frame.maxY - rect.height
                )
            }
            panel.setFrame(rect, display: false)
        }
        panel.orderFrontRegardless()
        isVisible = true
        if (srcLabel?.stringValue.isEmpty ?? true) && (dstLabel?.stringValue.isEmpty ?? true) {
            dstLabel?.stringValue = "等待声音…"
            resizeToFit()
        }
    }

    func hide() {
        panel?.orderOut(nil)
        isVisible = false
    }

    func setText(source: String, translation: String) {
        guard isVisible else { return }
        srcLabel?.stringValue = source
        dstLabel?.stringValue = translation.isEmpty ? "…" : translation
        resizeToFit()
    }

    func apply(size: Double, opacity: Double) {
        fontSize = CGFloat(max(20, min(80, size)))
        backgroundOpacity = CGFloat(max(0.2, min(0.95, opacity)))
        applyStyle()
        resizeToFit()
    }

    // MARK: - 面板

    private func ensurePanel() -> NSPanel {
        if let panel = panel { return panel }

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: panelWidth, height: 180),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.isMovableByWindowBackground = true
        panel.becomesKeyOnlyIfNeeded = true
        panel.isReleasedWhenClosed = false
        panel.animationBehavior = .utilityWindow

        let content = OverlayContentView(
            frame: NSRect(x: 0, y: 0, width: panelWidth, height: 180)
        )
        content.wantsLayer = true

        let effect = NSVisualEffectView()
        effect.translatesAutoresizingMaskIntoConstraints = false
        effect.material = .hudWindow
        effect.blendingMode = .behindWindow
        effect.state = .active
        effect.wantsLayer = true
        effect.layer?.cornerRadius = 18
        effect.layer?.masksToBounds = true
        content.addSubview(effect)

        let src = NSTextField(wrappingLabelWithString: "")
        src.translatesAutoresizingMaskIntoConstraints = false
        src.alignment = .center
        src.textColor = NSColor(calibratedWhite: 0.82, alpha: 1)
        src.font = .systemFont(ofSize: 17, weight: .regular)
        src.maximumNumberOfLines = 2
        src.lineBreakMode = .byWordWrapping

        let dst = NSTextField(wrappingLabelWithString: "")
        dst.translatesAutoresizingMaskIntoConstraints = false
        dst.alignment = .center
        dst.textColor = .white
        dst.font = .systemFont(ofSize: fontSize, weight: .semibold)
        dst.maximumNumberOfLines = 4
        dst.lineBreakMode = .byWordWrapping
        dst.cell?.wraps = true

        content.addSubview(src)
        content.addSubview(dst)

        NSLayoutConstraint.activate([
            effect.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            effect.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            effect.topAnchor.constraint(equalTo: content.topAnchor),
            effect.bottomAnchor.constraint(equalTo: content.bottomAnchor),

            src.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: padding),
            src.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -padding),
            src.topAnchor.constraint(equalTo: content.topAnchor, constant: padding - 4),

            dst.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: padding),
            dst.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -padding),
            dst.topAnchor.constraint(equalTo: src.bottomAnchor, constant: 6),
            dst.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -padding),
        ])

        panel.contentView = content
        panel.setContentSize(NSSize(width: panelWidth, height: 180))
        panel.setFrameAutosaveName("LocalInterpreterSubtitleOverlay")

        self.panel = panel
        self.effect = effect
        self.srcLabel = src
        self.dstLabel = dst
        return panel
    }

    private func applyStyle() {
        let srcSize = max(14, fontSize * 0.38)
        srcLabel?.font = .systemFont(ofSize: srcSize, weight: .regular)
        dstLabel?.font = .systemFont(ofSize: fontSize, weight: .semibold)
        effect?.alphaValue = backgroundOpacity
    }

    /// 面板高度跟着文字走，保持底边不动，避免字幕向上“长”出屏幕。
    private func resizeToFit() {
        guard let panel = panel, let content = panel.contentView else { return }
        content.layoutSubtreeIfNeeded()
        let height = min(460, max(120, content.fittingSize.height))
        let old = panel.frame
        var frame = NSRect(
            x: old.origin.x,
            y: old.origin.y,
            width: panelWidth,
            height: height
        )
        if old.height > 0, height != old.height {
            frame.origin.y = old.maxY - height
        }
        if let screen = panel.screen {
            let visible = screen.visibleFrame
            frame.origin.x = min(max(visible.minX, frame.origin.x), visible.maxX - frame.width)
            frame.origin.y = min(max(visible.minY, frame.origin.y), visible.maxY - frame.height)
        }
        panel.setFrame(frame, display: true)
    }
}
