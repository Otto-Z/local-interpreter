import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation
import LocalInterpreterCoreBindings
import ScreenCaptureKit

/// Unified macOS microphone (`AVAudioEngine`) & system audio (`ScreenCaptureKit`) capture
/// feeding 16 kHz mono Float32 PCM chunks into the shared Rust DSP & pipeline core.
final class AudioCaptureService: NSObject, SCStreamOutput, SCStreamDelegate {
    enum Source {
        case mic
        case system
    }

    var onSamples16k: (([Float]) -> Void)?
    var onLevel: ((Float) -> Void)?
    var onError: ((String) -> Void)?

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
    private let audioQueue = DispatchQueue(label: "com.localinterpreter.mac.audio")
    private var generation = 0

    func start(source: Source) {
        stop()
        generation += 1
        switch source {
        case .mic:
            startMic(gen: generation)
        case .system:
            startSystem(gen: generation)
        }
    }

    func stop() {
        generation += 1
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
    }

    private func startMic(gen: Int) {
        let status = AVCaptureDevice.authorizationStatus(for: .audio)
        if status == .notDetermined {
            AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
                guard let self, self.generation == gen else { return }
                if granted {
                    self.startMic(gen: gen)
                } else {
                    self.onError?("缺少麦克风权限，请在 系统设置 → 隐私与安全性 → 麦克风 中允许。")
                }
            }
            return
        }
        guard status == .authorized else {
            onError?("缺少麦克风权限，请在 系统设置 → 隐私与安全性 → 麦克风 中允许。")
            return
        }

        let engine = AVAudioEngine()
        let input = engine.inputNode
        // Do NOT enable setVoiceProcessingEnabled: on some macOS releases it forces 9ch silence.
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, let converter = AVAudioConverter(from: format, to: targetFormat) else {
            onError?("无法初始化麦克风重采样器")
            return
        }
        converter.sampleRateConverterQuality = AVAudioQuality.high.rawValue
        micConverter = converter

        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            guard let self, let pcm = self.convert(buffer, using: converter) else { return }
            self.emit(pcm)
        }

        engine.prepare()
        do {
            try engine.start()
            self.engine = engine
        } catch {
            onError?("麦克风启动失败：\(error.localizedDescription)")
        }
    }

    private func startSystem(gen: Int) {
        if !CGPreflightScreenCaptureAccess() {
            CGRequestScreenCaptureAccess()
        }
        Task {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(
                    false, onScreenWindowsOnly: false
                )
                guard self.generation == gen else { return }
                guard let display = content.displays.first else {
                    self.onError?("未找到可用显示器")
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
                self.stream = stream
                try await stream.startCapture()
                if self.generation != gen {
                    try? await stream.stopCapture()
                    if self.stream === stream { self.stream = nil }
                }
            } catch {
                guard self.generation == gen else { return }
                self.stream = nil
                self.onError?("无法采集系统声音（需授予屏幕录制权限）：\(error.localizedDescription)")
            }
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        guard stream === self.stream else { return }
        onError?("系统音频采集中断：\(error.localizedDescription)")
    }

    func stream(
        _ stream: SCStream,
        didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
        of outputType: SCStreamOutputType
    ) {
        guard outputType == .audio, stream === self.stream else { return }
        guard let pcmBuffer = makePCMBuffer(from: sampleBuffer) else { return }
        let format = pcmBuffer.format
        if systemConverter == nil || systemSourceFormat != format {
            systemSourceFormat = format
            guard let conv = AVAudioConverter(from: format, to: targetFormat) else { return }
            conv.sampleRateConverterQuality = AVAudioQuality.high.rawValue
            systemConverter = conv
        }
        guard let conv = systemConverter, let pcm = convert(pcmBuffer, using: conv) else { return }
        emit(pcm)
    }

    private func emit(_ pcm: [Float]) {
        guard !pcm.isEmpty else { return }
        let rms = computeAudioRms(samples: pcm)
        onLevel?(rms)
        onSamples16k?(pcm)
    }

    private func convert(_ buffer: AVAudioPCMBuffer, using converter: AVAudioConverter) -> [Float]? {
        let ratio = targetFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else {
            return nil
        }
        var err: NSError?
        var supplied = false
        let status = converter.convert(to: output, error: &err) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard err == nil, status != .error, output.frameLength > 0,
              let ch = output.floatChannelData?[0] else {
            return nil
        }
        return Array(UnsafeBufferPointer(start: ch, count: Int(output.frameLength)))
    }

    private func makePCMBuffer(from sampleBuffer: CMSampleBuffer) -> AVAudioPCMBuffer? {
        guard let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(formatDesc)
        else { return nil }
        let numSamples = CMSampleBufferGetNumSamples(sampleBuffer)
        guard numSamples > 0 else { return nil }
        var asbdCopy = asbd.pointee
        guard let format = AVAudioFormat(streamDescription: &asbdCopy),
              let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(numSamples))
        else { return nil }
        pcm.frameLength = AVAudioFrameCount(numSamples)
        let status = CMSampleBufferCopyPCMDataIntoAudioBufferList(
            sampleBuffer, at: 0, frameCount: Int32(numSamples), into: pcm.mutableAudioBufferList
        )
        return status == noErr ? pcm : nil
    }
}
