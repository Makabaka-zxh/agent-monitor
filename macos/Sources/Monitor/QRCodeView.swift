import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

enum QRCodeRenderer {
    static func image(for payload: String, scale: CGFloat = 8) -> NSImage? {
        guard !payload.isEmpty, payload.utf8.count <= 1024 else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }
}

struct QRCodeCardView: View {
    let payload: String
    var size: CGFloat = 184

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.white)
                .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 4)
            if let qr = QRCodeRenderer.image(for: payload) {
                Image(nsImage: qr)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .padding(14)
                    .accessibilityLabel("扫码登录配对二维码")
            } else {
                ProgressView()
            }
        }
        .frame(width: size, height: size)
    }
}
