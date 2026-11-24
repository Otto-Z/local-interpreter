import AppKit

private final class DragOverlayView: NSView {
    override var mouseDownCanMoveWindow: Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? {
        bounds.contains(point) ? self : nil
    }
}

/// Non-activating always-on-top subtitle panel for macOS (works over fullscreen apps & all Spaces).
final class SubtitleOverlayController {
    private var panel: NSPanel?
    private var effect: NSVisualEffectView?
    private var srcLabel: NSTextField?
    private var dstLabel: NSTextField?
    private var fontSize: CGFloat = 40
    private var opacity: CGFloat = 0.82
    private(set) var isVisible = false

    private let panelWidth: CGFloat = 940
    private let padding: CGFloat = 24

    func show(size: Double, opacity: Double) {
        fontSize = CGFloat(max(20, min(80, size)))
        self.opacity = CGFloat(max(0.2, min(0.95, opacity)))
        let panel = ensurePanel()
        applyStyle()
        if let screen = NSScreen.main {
            let visible = screen.visibleFrame
            var rect = panel.frame
            rect.size.width = panelWidth
            if UserDefaults.standard.object(forKey: "NSWindow Frame LocalInterpreterMacOverlay") == nil {
                rect.origin = NSPoint(x: visible.midX - panelWidth / 2, y: visible.minY + 90)
            }
            panel.setFrame(rect, display: false)
        }
        panel.orderFrontRegardless()
        isVisible = true
        if (dstLabel?.stringValue.isEmpty ?? true) {
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
        self.opacity = CGFloat(max(0.2, min(0.95, opacity)))
        applyStyle()
        resizeToFit()
    }

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: panelWidth, height: 170),
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
        panel.isReleasedWhenClosed = false

        let content = DragOverlayView(frame: NSRect(x: 0, y: 0, width: panelWidth, height: 170))
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
        src.textColor = NSColor(calibratedWhite: 0.84, alpha: 1)
        src.maximumNumberOfLines = 2

        let dst = NSTextField(wrappingLabelWithString: "")
        dst.translatesAutoresizingMaskIntoConstraints = false
        dst.alignment = .center
        dst.textColor = .white
        dst.maximumNumberOfLines = 4

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
        panel.setFrameAutosaveName("LocalInterpreterMacOverlay")
        self.panel = panel
        self.effect = effect
        self.srcLabel = src
        self.dstLabel = dst
        return panel
    }

    private func applyStyle() {
        srcLabel?.font = .systemFont(ofSize: max(14, fontSize * 0.38), weight: .regular)
        dstLabel?.font = .systemFont(ofSize: fontSize, weight: .semibold)
        effect?.alphaValue = opacity
    }

    private func resizeToFit() {
        guard let panel, let content = panel.contentView else { return }
        content.layoutSubtreeIfNeeded()
        let height = min(460, max(120, content.fittingSize.height))
        let old = panel.frame
        var frame = NSRect(x: old.origin.x, y: old.origin.y, width: panelWidth, height: height)
        if old.height > 0, height != old.height {
            frame.origin.y = old.maxY - height
        }
        panel.setFrame(frame, display: true)
    }
}
