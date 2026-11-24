import AVFoundation
import Foundation

/// Decodes any macOS-supported audio or video file (`.wav`, `.m4a`, `.mp3`, `.aac`, `.mp4`, `.mov`)
/// directly into 16 kHz mono Float32 PCM chunks using native `AVAssetReader` (zero external `ffmpeg` dependency).
enum AudioFileImporter {
    enum ImportError: LocalizedError {
        case noAudioTrack
        case cannotStartReading(String)

        var errorDescription: String? {
            switch self {
            case .noAudioTrack:
                return "文件中没有找到可用的音轨"
            case .cannotStartReading(let detail):
                return "无法解码音视频文件：\(detail)"
            }
        }
    }

    static func decodeTo16kMonoChunks(
        url: URL,
        chunkSize: Int = 4096,
        onChunk: ([Float]) -> Void
    ) async throws {
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        guard let track = tracks.first else {
            throw ImportError.noAudioTrack
        }

        let reader = try AVAssetReader(asset: asset)
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 16_000.0,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: outputSettings)
        output.alwaysCopiesSampleData = false
        reader.add(output)

        guard reader.startReading() else {
            throw ImportError.cannotStartReading(reader.error?.localizedDescription ?? "unknown")
        }

        var buffer: [Float] = []
        buffer.reserveCapacity(chunkSize * 2)

        while reader.status == .reading {
            guard let sampleBuffer = output.copyNextSampleBuffer() else { break }
            guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { continue }
            var lengthAtOffset = 0
            var totalLength = 0
            var dataPointer: UnsafeMutablePointer<Int8>?
            let status = CMBlockBufferGetDataPointer(
                blockBuffer,
                atOffset: 0,
                lengthAtOffsetOut: &lengthAtOffset,
                totalLengthOut: &totalLength,
                dataPointerOut: &dataPointer
            )
            guard status == kCMBlockBufferNoErr, let ptr = dataPointer, totalLength >= MemoryLayout<Float>.size else {
                continue
            }
            let floatCount = totalLength / MemoryLayout<Float>.size
            ptr.withMemoryRebound(to: Float.self, capacity: floatCount) { floatPtr in
                let slice = UnsafeBufferPointer(start: floatPtr, count: floatCount)
                buffer.append(contentsOf: slice)
            }
            while buffer.count >= chunkSize {
                let slice = Array(buffer.prefix(chunkSize))
                buffer.removeFirst(chunkSize)
                onChunk(slice)
            }
        }

        if !buffer.isEmpty {
            onChunk(buffer)
        }
    }
}
