import CoreImage
import CoreMedia
import CoreVideo
import CryptoKit
import Foundation
import OMOCastCore

func check(_ ok: Bool, _ what: String) {
    print(ok ? "PASS" : "FAIL", what)
    if !ok { exit(1) }
}

func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

let ci = CIContext()

func buffer(_ w: Int, _ h: Int, _ image: CIImage) -> CVPixelBuffer {
    var pb: CVPixelBuffer?
    CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_32BGRA,
                        [kCVPixelBufferIOSurfacePropertiesKey: [:] as [CFString: Any]] as CFDictionary, &pb)
    ci.render(image, to: pb!, bounds: CGRect(x: 0, y: 0, width: w, height: h), colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!)
    return pb!
}

/// (r, g, b) at x, y measured from the top-left.
func pixel(_ pb: CVPixelBuffer, _ x: Int, _ y: Int) -> (Int, Int, Int) {
    CVPixelBufferLockBaseAddress(pb, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
    let p = CVPixelBufferGetBaseAddress(pb)!.assumingMemoryBound(to: UInt8.self) + y * CVPixelBufferGetBytesPerRow(pb) + x * 4
    return (Int(p[2]), Int(p[1]), Int(p[0]))
}

func isRed(_ c: (Int, Int, Int)) -> Bool { c.0 > 200 && c.1 < 60 && c.2 < 60 }
func isBlue(_ c: (Int, Int, Int)) -> Bool { c.2 > 200 && c.0 < 60 && c.1 < 60 }

func unit() {
    let size = Data(#"{"size":"960x360"}"#.utf8)
    check(hex(ClusterLink.packet(channel: 7, msg: 41, body: size)) == "07000000" + "14000000" + "2900" + hex(size),
          "control framing: [ch7][len=msg+body][msg 41][json]")
    check(hex(ClusterLink.videoPacket(Data([0, 0, 0, 1, 0x65]))) == "02000000" + "05000000" + "0000000165",
          "video framing: [ch2][len][Annex-B]")
    let der = P256.KeyAgreement.PrivateKey().publicKey.derRepresentation
    check(der.count == 91 && hex(der).hasPrefix("3059301306072a8648ce3d020106082a8648ce3d03010703420004"),
          "key exchange reply: 91-byte P-256 SubjectPublicKeyInfo")
    let annexB = H264Encoder.annexB(avcc: Data([0, 0, 0, 2, 0x65, 0xAA, 0, 0, 0, 1, 0x41]),
                                    parameterSets: [Data([0x67, 1]), Data([0x68, 2])])
    check(hex(annexB) == "00000001" + "6701" + "00000001" + "6802" + "00000001" + "65aa" + "00000001" + "41",
          "AVCC -> Annex-B, SPS/PPS glued before IDR")
    check(H264Encoder.annexB(avcc: Data([0, 0, 0, 9, 1]), parameterSets: []).isEmpty, "truncated NAL is dropped, not over-read")

    let scaler = FrameScaler()
    // Landscape iPhone screen (2532x1170): left half red, right half blue.
    let split = CIImage(color: .red).cropped(to: CGRect(x: 0, y: 0, width: 1266, height: 1170))
        .composited(over: CIImage(color: .blue).cropped(to: CGRect(x: 0, y: 0, width: 2532, height: 1170)))
    let land = scaler.fill(buffer(2532, 1170, split))!
    check(CVPixelBufferGetWidth(land) == 960 && CVPixelBufferGetHeight(land) == 360, "output is 960x360")
    check(isRed(pixel(land, 100, 180)) && isBlue(pixel(land, 860, 180)), "landscape frame keeps left/right")

    // Portrait buffer + orientation tag (how ReplayKit hands over a landscape screen): top half red.
    let top = CIImage(color: .red).cropped(to: CGRect(x: 0, y: 1266, width: 1170, height: 1266))
        .composited(over: CIImage(color: .blue).cropped(to: CGRect(x: 0, y: 0, width: 1170, height: 2532)))
    let portrait = buffer(1170, 2532, top)
    let r = scaler.fill(portrait, orientation: .right)!, l = scaler.fill(portrait, orientation: .left)!
    check(isBlue(pixel(r, 100, 180)) && isRed(pixel(r, 860, 180)), "orientation .right rotates upright (top -> right)")
    check(isRed(pixel(l, 100, 180)) && isBlue(pixel(l, 860, 180)), "orientation .left rotates upright (top -> left)")

    let src = buffer(2532, 1170, split)
    _ = scaler.fill(src)
    let t0 = Date()
    for _ in 0..<30 { _ = scaler.fill(src) }
    let ms = Date().timeIntervalSince(t0) / 30 * 1000
    print(String(format: "info scale 2532x1170 -> 960x360: %.1f ms/frame", ms))
    check(ms < 33, "scaler keeps up with 30 fps")

    // Encoder must emit every frame (cluster has no frame-drop tolerance beyond the next IDR).
    let enc = try! H264Encoder(width: 960, height: 360)
    let got = NSLock()
    var frames = 0, keys = 0
    enc.onFrame = { _, key in got.lock(); frames += 1; if key { keys += 1 }; got.unlock() }
    let src960 = scaler.fill(src)!
    let t1 = Date()
    for i in 0..<60 { enc.encode(src960, pts: CMTime(value: Int64(i), timescale: 30)) }
    while Date().timeIntervalSince(t1) < 3 { got.lock(); let n = frames; got.unlock(); if n >= 60 { break }; usleep(10_000) }
    print("info encoder emitted \(frames)/60 frames, \(keys) keyframes")
    check(frames == 60, "encoder emits every frame")
}

/// Streams a moving 2532x1170 test pattern at ~60 fps for `seconds` to a (mock) speedometer.
func live(host: String, port: UInt16, seconds: Double) throws {
    let caster = try Caster(host: host, controlPort: port, videoPort: port + 1)
    caster.start()
    let rect = CGRect(x: 0, y: 0, width: 2532, height: 1170)
    // Pre-render a few screens (rendering per frame on the CPU-bound test host is slower than 60 fps).
    let screens = (0..<24).map { i in
        buffer(2532, 1170, CIFilter(name: "CICheckerboardGenerator", parameters: [
            "inputCenter": CIVector(x: CGFloat(300 + i * 80), y: 585),
            "inputColor0": CIColor(red: 0.1, green: 0.5, blue: 1), "inputColor1": CIColor.white, "inputWidth": 160,
        ])!.outputImage!.cropped(to: rect))
    }
    let start = Date()
    var pushed = 0
    // Busy-wait pacing at 60 fps like ReplayKit; Thread.sleep/usleep on this Mac overshoot 16 ms by up to 130 ms.
    while Date().timeIntervalSince(start) < seconds {
        caster.push(screens[pushed % screens.count])
        pushed += 1
        let due = start.addingTimeInterval(Double(pushed) / 60)
        while Date() < due {}
    }
    print("pushed \(pushed) screen frames in \(seconds) s; encoder submitted \(caster.encoder.submitted) lost \(caster.encoder.lost); link sent \(caster.link.sent), dropped \(caster.link.dropped)")
    Thread.sleep(forTimeInterval: 1)
    caster.stop()
}

let args = CommandLine.arguments
unit()
if args.count >= 2, args[1] == "live" {
    try live(host: args.count > 2 ? args[2] : "127.0.0.1", port: args.count > 3 ? UInt16(args[3])! : 9527,
             seconds: args.count > 4 ? Double(args[4])! : 6)
    print("live stream finished")
}
