import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation
import ScreenCaptureKit

protocol AudioCaptureDelegate: AnyObject {
    func audioCapture(didProduce samples: Data)
    func audioCapture(didFail message: String)
}

/// 麦克风 / 系统声音的本地采集，统一输出 16kHz 单声道 Float32。
/// 使用 AVAudioConverter 做正规重采样（带抗混叠滤波），避免简单抽取导致识别率下降。
final class AudioCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    enum Source {
        case mic
        case system
    }

    weak var delegate: AudioCaptureDelegate?
    var onLog: ((String) -> Void)?

    private let chunkFrames = 512
    private let targetFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: 16000,
        channels: 1,
        interleaved: false
    )!

    private var engine: AVAudioEngine?
    private var stream: SCStream?
    private var micConverter: AVAudioConverter?
    private var systemConverter: AVAudioConverter?
    private var systemSourceFormat: AVAudioFormat?
    private var chunk: [Float] = []
    private let lock = NSLock()
    private let audioQueue = DispatchQueue(label: "local.interpreter.audio")
    private var loggedFirstBuffer = false
    private var loggedFirstChunk = false
    // 每次开始/停止都会递增；异步启动完成时如果代际已变，就丢弃这次采集
    private var captureGeneration = 0

    func start(source: Source) {
        captureGeneration += 1
        switch source {
        case .mic:
            startMic()
        case .system:
            startSystem()
        }
    }

    func stop() {
        captureGeneration += 1
        if let engine = engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        engine = nil
        if let stream = stream {
            Task { try? await stream.stopCapture() }
        }
        stream = nil
        micConverter = nil
        systemConverter = nil
        systemSourceFormat = nil
        lock.lock()
        chunk.removeAll()
        loggedFirstBuffer = false
        loggedFirstChunk = false
        lock.unlock()
    }

    // MARK: - 麦克风

    private func startMic() {
        let generation = captureGeneration
        let status = AVCaptureDevice.authorizationStatus(for: .audio)
        log("mic auth status=\(status.rawValue)")
        if status == .notDetermined {
            AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
                guard let self = self, self.captureGeneration == generation else {
                    return
                }
                if granted {
                    self.startMic()
                } else {
                    self.fail("没有麦克风权限。请到 系统设置 → 隐私与安全性 → 麦克风 中允许「本地同传」。")
                }
            }
            return
        }
        if status == .denied || status == .restricted {
            fail("没有麦克风权限。请到 系统设置 → 隐私与安全性 → 麦克风 中允许「本地同传」。")
            return
        }

        let engine = AVAudioEngine()
        let input = engine.inputNode
        // 注意：不要开启 setVoiceProcessingEnabled。在部分 macOS 版本上它会把输入
        // 变成 9 声道且输出全静音，导致完全识别不出文字（已实测）。
        let format = input.outputFormat(forBus: 0)
        log("mic format rate=\(format.sampleRate) ch=\(format.channelCount)")
        guard format.sampleRate > 0 else {
            fail("没有可用的麦克风输入")
            return
        }
        guard let converter = AVAudioConverter(from: format, to: targetFormat) else {
            fail("无法初始化音频重采样器")
            return
        }
        converter.sampleRateConverterQuality = AVAudioQuality.high.rawValue
        micConverter = converter

        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            guard let self = self else { return }
            if !self.loggedFirstBuffer {
                self.loggedFirstBuffer = true
                self.log("mic first buffer frames=\(buffer.frameLength) rate=\(buffer.format.sampleRate)")
            }
            guard let converted = self.convert(buffer, using: converter) else { return }
            self.process16k(converted)
        }

        engine.prepare()
        do {
            try engine.start()
            self.engine = engine
            log("mic engine started")
        } catch {
            fail("麦克风启动失败：\(error.localizedDescription)")
        }
    }

    // MARK: - 系统声音

    private func startSystem() {
        let generation = captureGeneration
        log("system audio requested")
        if !CGPreflightScreenCaptureAccess() {
            log("screen capture permission missing, requesting")
            CGRequestScreenCaptureAccess()
        }
        Task {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(
                    false, onScreenWindowsOnly: false
                )
                guard self.captureGeneration == generation else { return }
                guard let display = content.displays.first else {
                    self.fail("没有找到可用的显示器")
                    return
                }
                let filter = SCContentFilter(
                    display: display, excludingApplications: [], exceptingWindows: []
                )
                let config = SCStreamConfiguration()
                config.capturesAudio = true
                config.excludesCurrentProcessAudio = true
                config.sampleRate = 48000
                config.channelCount = 2
                config.width = 2
                config.height = 2
                config.minimumFrameInterval = CMTime(value: 1, timescale: 1)

                let stream = SCStream(filter: filter, configuration: config, delegate: self)
                try stream.addStreamOutput(self, type: .audio, sampleHandlerQueue: self.audioQueue)
                // 先记下 stream，保证启动过程中的音频也能被 stop() 找到
                self.stream = stream
                try await stream.startCapture()
                guard self.captureGeneration == generation else {
                    try? await stream.stopCapture()
                    if self.stream === stream { self.stream = nil }
                    return
                }
                self.log("system audio stream started")
            } catch {
                guard self.captureGeneration == generation else { return }
                self.stream = nil
                self.fail("无法采集系统音频（需要「屏幕录制」权限）：\(error.localizedDescription)")
            }
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        guard stream === self.stream else { return }
        fail("系统音频采集中断：\(error.localizedDescription)")
    }

    func stream(
        _ stream: SCStream,
        didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
        of outputType: SCStreamOutputType
    ) {
        guard outputType == .audio else { return }
        guard stream === self.stream else { return }
        guard let buffer = AudioCapture.makePCMBuffer(from: sampleBuffer) else { return }
        if !loggedFirstBuffer {
            loggedFirstBuffer = true
            log("system first buffer frames=\(buffer.frameLength) rate=\(buffer.format.sampleRate) ch=\(buffer.format.channelCount)")
        }
        let format = buffer.format
        if systemConverter == nil || systemSourceFormat != format {
            systemSourceFormat = format
            guard let converter = AVAudioConverter(from: format, to: targetFormat) else {
                fail("无法初始化系统音频重采样器")
                return
            }
            converter.sampleRateConverterQuality = AVAudioQuality.high.rawValue
            systemConverter = converter
        }
        guard let converter = systemConverter,
              let converted = convert(buffer, using: converter)
        else { return }
        process16k(converted)
    }

    // MARK: - 转换与分包

    private func convert(_ buffer: AVAudioPCMBuffer, using converter: AVAudioConverter) -> [Float]? {
        let ratio = targetFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else {
            return nil
        }
        var error: NSError?
        var supplied = false
        let inputBlock: AVAudioConverterInputBlock = { _, status in
            if supplied {
                status.pointee = .noDataNow
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return buffer
        }
        let result = converter.convert(to: output, error: &error, withInputFrom: inputBlock)
        if let error = error {
            log("convert error: \(error.localizedDescription)")
            return nil
        }
        if result == .error { return nil }
        guard output.frameLength > 0, let channel = output.floatChannelData?[0] else {
            return nil
        }
        return Array(
            UnsafeBufferPointer(start: channel, count: Int(output.frameLength))
        )
    }

    private func process16k(_ samples: [Float]) {
        guard !samples.isEmpty else { return }
        lock.lock()
        chunk.append(contentsOf: samples)
        var parts: [[Float]] = []
        while chunk.count >= chunkFrames {
            parts.append(Array(chunk.prefix(chunkFrames)))
            chunk.removeFirst(chunkFrames)
        }
        lock.unlock()

        for part in parts {
            if !loggedFirstChunk {
                loggedFirstChunk = true
                log("first pcm chunk to engine (\(part.count) frames)")
            }
            let data = part.withUnsafeBufferPointer { Data(buffer: $0) }
            delegate?.audioCapture(didProduce: data)
        }
    }

    private func log(_ text: String) {
        onLog?(text)
    }

    private func fail(_ message: String) {
        log("audio failure: \(message)")
        DispatchQueue.main.async {
            self.delegate?.audioCapture(didFail: message)
        }
    }

    // MARK: - CMSampleBuffer -> AVAudioPCMBuffer

    private static func makePCMBuffer(from sampleBuffer: CMSampleBuffer) -> AVAudioPCMBuffer? {
        guard let fd = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(fd)
        else { return nil }
        let frames = CMSampleBufferGetNumSamples(sampleBuffer)
        guard frames > 0,
              let format = AVAudioFormat(streamDescription: asbd),
              let pcm = AVAudioPCMBuffer(
                  pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)
              )
        else { return nil }
        pcm.frameLength = AVAudioFrameCount(frames)

        let channels = max(Int(asbd.pointee.mChannelsPerFrame), 1)
        let size = MemoryLayout<AudioBufferList>.size
            + MemoryLayout<AudioBuffer>.size * (channels - 1)
        let raw = UnsafeMutableRawPointer.allocate(
            byteCount: size, alignment: MemoryLayout<AudioBufferList>.alignment
        )
        defer { raw.deallocate() }
        let abl = raw.bindMemory(to: AudioBufferList.self, capacity: 1)

        var blockBuffer: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: abl,
            bufferListSize: size,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard status == noErr else { return nil }

        let dst = UnsafeMutableAudioBufferListPointer(pcm.mutableAudioBufferList)
        let src = UnsafeMutableAudioBufferListPointer(abl)
        for i in 0..<min(dst.count, src.count) {
            guard let s = src[i].mData, let d = dst[i].mData else { continue }
            memcpy(d, s, min(Int(src[i].mDataByteSize), Int(dst[i].mDataByteSize)))
        }
        return pcm
    }
}
