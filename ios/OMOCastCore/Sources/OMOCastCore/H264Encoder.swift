import CoreMedia
import Foundation
import VideoToolbox

/// Hardware H.264 encoder tuned like the OMOWAY app: 30 fps, 2 Mbps, IDR every second, no B-frames.
public final class H264Encoder {
    public var onFrame: (Data, Bool) -> Void = { _, _ in }
    private let session: VTCompressionSession
    private let lock = NSLock()
    private var forceKey = true

    public init(width: Int32, height: Int32, fps: Int = 30, bitrate: Int = 2_000_000) throws {
        var s: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: nil, width: width, height: height, codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &s)
        guard status == noErr, let s else {
            throw NSError(domain: "OMOCast", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "H.264 encoder unavailable (\(status))"])
        }
        session = s
        let props: [CFString: Any] = [
            kVTCompressionPropertyKey_RealTime: true,
            kVTCompressionPropertyKey_ProfileLevel: kVTProfileLevel_H264_Baseline_AutoLevel,
            kVTCompressionPropertyKey_AllowFrameReordering: false,
            kVTCompressionPropertyKey_AverageBitRate: bitrate,
            kVTCompressionPropertyKey_ExpectedFrameRate: fps,
            kVTCompressionPropertyKey_MaxKeyFrameInterval: fps,
        ]
        for (key, value) in props { VTSessionSetProperty(s, key: key, value: value as CFTypeRef) }
        VTCompressionSessionPrepareToEncodeFrames(s)
    }

    deinit { VTCompressionSessionInvalidate(session) }

    public func requestKeyFrame() { lock.lock(); forceKey = true; lock.unlock() }

    public func encode(_ pixelBuffer: CVPixelBuffer, pts: CMTime) {
        lock.lock(); let key = forceKey; forceKey = false; submitted += 1; lock.unlock()
        let opts = key ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
        VTCompressionSessionEncodeFrame(session, imageBuffer: pixelBuffer, presentationTimeStamp: pts,
                                        duration: .invalid, frameProperties: opts, infoFlagsOut: nil) { [weak self] status, flags, sb in
            guard let self else { return }
            guard status == noErr, let sb, let frame = Self.annexB(sb) else {
                self.lock.lock(); self.lost += 1; self.lock.unlock()
                if status != noErr || !flags.contains(.frameDropped) { NSLog("[omocast] encoder status %d flags %d", status, flags.rawValue) }
                return
            }
            self.onFrame(frame.data, frame.isKey)
        }
    }

    /// Frames submitted / not produced by VideoToolbox (diagnostics).
    public private(set) var submitted = 0
    public private(set) var lost = 0

    static func annexB(_ sb: CMSampleBuffer) -> (data: Data, isKey: Bool)? {
        guard let block = CMSampleBufferGetDataBuffer(sb), let fmt = CMSampleBufferGetFormatDescription(sb) else { return nil }
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true) as? [[CFString: Any]]
        let isKey = !(attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
        var avcc = Data(count: CMBlockBufferGetDataLength(block))
        let status = avcc.withUnsafeMutableBytes {
            CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: $0.count, destination: $0.baseAddress!)
        }
        guard status == noErr else { return nil }
        return (annexB(avcc: avcc, parameterSets: isKey ? parameterSets(fmt) : []), isKey)
    }

    static func parameterSets(_ fmt: CMFormatDescription) -> [Data] {
        var count = 0
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: 0, parameterSetPointerOut: nil,
                                                           parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
        return (0..<count).compactMap { i in
            var ptr: UnsafePointer<UInt8>?
            var size = 0
            guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: i, parameterSetPointerOut: &ptr,
                                                                     parameterSetSizeOut: &size, parameterSetCountOut: nil,
                                                                     nalUnitHeaderLengthOut: nil) == noErr, let ptr else { return nil }
            return Data(bytes: ptr, count: size)
        }
    }

    /// AVCC (4-byte big-endian lengths) -> Annex-B with SPS/PPS first; the cluster drops IDRs without them glued on.
    public static func annexB(avcc: Data, parameterSets: [Data]) -> Data {
        let start = Data([0, 0, 0, 1])
        var out = Data()
        for ps in parameterSets { out += start + ps }
        var i = avcc.startIndex
        while i + 4 <= avcc.endIndex {
            let n = avcc[i..<i + 4].reduce(0) { $0 << 8 | Int($1) }
            i += 4
            guard n > 0, i + n <= avcc.endIndex else { break }
            out += start + avcc[i..<i + n]
            i += n
        }
        return out
    }
}
