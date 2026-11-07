import AppKit
import Foundation
import PDFKit
import Vision

/// 扫描版 PDF 的离线 OCR：把每页渲染成图片，用 macOS Vision 识别中英文。
/// 用法：pdfocr <file.pdf>  → 标准输出 {"pages":[{"page":1,"text":"..."}]}
func renderPage(_ page: PDFPage, scale: CGFloat) -> CGImage? {
    let bounds = page.bounds(for: .mediaBox)
    let width = Int(bounds.width * scale)
    let height = Int(bounds.height * scale)
    guard width > 0, height > 0 else { return nil }
    guard let context = CGContext(
        data: nil,
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }
    context.setFillColor(NSColor.white.cgColor)
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.scaleBy(x: scale, y: scale)
    page.draw(with: .mediaBox, to: context)
    return context.makeImage()
}

func recognize(_ image: CGImage) throws -> String {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.recognitionLanguages = ["zh-Hans", "en-US"]
    request.usesLanguageCorrection = true
    let handler = VNImageRequestHandler(cgImage: image, options: [:])
    try handler.perform([request])
    let observations = request.results ?? []
    let sorted = observations.sorted { left, right in
        let dy = abs(left.boundingBox.midY - right.boundingBox.midY)
        if dy > 0.02 { return left.boundingBox.midY > right.boundingBox.midY }
        return left.boundingBox.minX < right.boundingBox.minX
    }
    return sorted.compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
}

let args = CommandLine.arguments
guard args.count >= 2, let document = PDFDocument(url: URL(fileURLWithPath: args[1])) else {
    FileHandle.standardError.write("cannot open pdf\n".data(using: .utf8)!)
    exit(2)
}

var pages: [[String: Any]] = []
let pageLimit = min(document.pageCount, 120)
for index in 0..<pageLimit {
    guard let page = document.page(at: index), let image = renderPage(page, scale: 2.0) else {
        continue
    }
    do {
        let text = try recognize(image).trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty {
            pages.append(["page": index + 1, "text": text])
        }
    } catch {
        FileHandle.standardError.write("page \(index + 1): \(error)\n".data(using: .utf8)!)
    }
}

let payload: [String: Any] = ["pages": pages]
do {
    let data = try JSONSerialization.data(withJSONObject: payload, options: [])
    FileHandle.standardOutput.write(data)
} catch {
    FileHandle.standardError.write("json error\n".data(using: .utf8)!)
    exit(1)
}
