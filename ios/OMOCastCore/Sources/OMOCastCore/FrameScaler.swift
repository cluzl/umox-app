import CoreImage
import CoreVideo
import ImageIO

/// Screen frame -> upright 960x360 BGRA: rotate per ReplayKit orientation, scale to cover, centre-crop.
public final class FrameScaler {
    public let width: Int
    public let height: Int
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
    private var pool: CVPixelBufferPool?

    public init(width: Int = 960, height: Int = 360) {
        self.width = width
        self.height = height
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey: width,
            kCVPixelBufferHeightKey: height,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as [CFString: Any],
        ]
        CVPixelBufferPoolCreate(nil, nil, attrs as CFDictionary, &pool)
    }

    public func fill(_ source: CVPixelBuffer, orientation: CGImagePropertyOrientation = .up) -> CVPixelBuffer? {
        var img = CIImage(cvPixelBuffer: source).oriented(orientation)
        img = img.transformed(by: CGAffineTransform(translationX: -img.extent.minX, y: -img.extent.minY))
        let scale = max(CGFloat(width) / img.extent.width, CGFloat(height) / img.extent.height)
        // Lanczos keeps street names legible after a ~2.6x shrink.
        img = img.applyingFilter("CILanczosScaleTransform", parameters: [kCIInputScaleKey: scale, kCIInputAspectRatioKey: 1])
        let dx = (img.extent.width - CGFloat(width)) / 2, dy = (img.extent.height - CGFloat(height)) / 2
        img = img.transformed(by: CGAffineTransform(translationX: -img.extent.minX - dx, y: -img.extent.minY - dy))

        guard let pool else { return nil }
        var out: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out) == kCVReturnSuccess, let out else { return nil }
        context.render(img, to: out, bounds: CGRect(x: 0, y: 0, width: width, height: height), colorSpace: srgb)
        return out
    }
}
