import CoreMedia
import CoreVideo
import Foundation
import ImageIO

/// Screen frames -> 960x360 H.264 -> OMO X speedometer. Used by the iOS broadcast extension and the macOS self-test.
public final class Caster {
    public let link: ClusterLink
    public let encoder: H264Encoder
    private let scaler = FrameScaler()
    private let lock = NSLock()
    private var last: CVPixelBuffer?
    private var lastSent = CMTime.zero

    public init(host: String, controlPort: UInt16 = 9527, videoPort: UInt16 = 9528,
                log: @escaping (String) -> Void = { print("[omocast] \($0)") }) throws {
        encoder = try H264Encoder(width: 960, height: 360)
        link = ClusterLink(host: host, controlPort: controlPort, videoPort: videoPort)
        link.onLog = log
        encoder.onFrame = { [link] data, isKey in link.sendVideo(data, isKey: isKey) }
        link.onKeyFrameRequest = { [weak self] in self?.resendKeyFrame() }
    }

    public func start() { link.start() }
    public func stop() { link.stop() }

    /// One captured screen frame. ReplayKit delivers up to 60 fps; the cluster wants 30.
    public func push(_ pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation = .up) {
        guard link.isVideoLive else { return }
        let now = CMClockGetTime(CMClockGetHostTimeClock())
        lock.lock(); defer { lock.unlock() }
        // ponytail: 30 fps cap with 25% jitter slack, no pacing timer; frames arrive irregularly on a busy phone.
        guard (now - lastSent).seconds >= 0.75 / 30 else { return }
        guard let out = scaler.fill(pixelBuffer, orientation: orientation) else { return }
        last = out
        lastSent = now
        encoder.encode(out, pts: now)
    }

    /// iOS sends no frames while the screen is still, so answer IDR requests by re-encoding the last frame.
    private func resendKeyFrame() {
        encoder.requestKeyFrame()
        lock.lock(); defer { lock.unlock() }
        guard let last else { return }
        lastSent = CMClockGetTime(CMClockGetHostTimeClock())
        encoder.encode(last, pts: lastSent)
    }
}
