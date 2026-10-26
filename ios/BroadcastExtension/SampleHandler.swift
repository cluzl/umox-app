import CoreMedia
import ImageIO
import OMOCastCore
import ReplayKit

/// "OMO X" screen-broadcast target: iOS hands us every screen frame (including Apple Maps / Google Maps / Waze),
/// we scale it to 960x360 and stream it to the speedometer over its Wi-Fi.
final class SampleHandler: RPBroadcastSampleHandler {
    private var caster: Caster?

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        let ip = OMOCastSettings.clusterIP
        OMOCastSettings.log = []
        do {
            caster = try Caster(host: ip, log: { OMOCastSettings.appendLog($0) })
            caster?.start()
            OMOCastSettings.appendLog("Broadcast started → \(ip)")
        } catch {
            finishBroadcastWithError(error)
        }
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let caster, let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let raw = (CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber)?.uint32Value
        caster.push(pixels, orientation: raw.flatMap(CGImagePropertyOrientation.init(rawValue:)) ?? .up)
    }

    override func broadcastFinished() {
        caster?.stop()
        caster = nil
        OMOCastSettings.appendLog("Broadcast stopped")
    }
}
