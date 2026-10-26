import CryptoKit
import Foundation
import Network

/// OMO X speedometer (Desay SV MapCast) link: control TCP 9527 + video TCP 9528.
/// Protocol per github.com/OMO-X/AboutOMO; behaviour mirrors UMOX `ControlChannelClient`.
public final class ClusterLink {
    public var onLog: (String) -> Void = { print("[omocast] \($0)") }
    /// Cluster opened video or asked for an IDR (also called after we drop frames).
    public var onKeyFrameRequest: () -> Void = {}

    public var isVideoLive: Bool { liveLock.lock(); defer { liveLock.unlock() }; return live }

    private let host: NWEndpoint.Host
    private let controlPort: NWEndpoint.Port
    private let videoPort: NWEndpoint.Port
    private let queue = DispatchQueue(label: "omocast.link")
    private var control: NWConnection?
    private var video: NWConnection?
    private var running = false
    private var wantVideo = false
    private var waitKey = true
    private var pendingBytes = 0
    private let liveLock = NSLock()
    private var live = false

    public init(host: String, controlPort: UInt16 = 9527, videoPort: UInt16 = 9528) {
        self.host = NWEndpoint.Host(host)
        self.controlPort = NWEndpoint.Port(rawValue: controlPort)!
        self.videoPort = NWEndpoint.Port(rawValue: videoPort)!
    }

    public func start() {
        queue.async { self.running = true; self.connectControl() }
    }

    public func stop() {
        queue.sync {
            running = false
            stopVideo()
            if let c = control {
                // MapProjectionSignal HIDE, then close.
                c.send(content: Self.packet(channel: 7, msg: 2, body: Self.ok), completion: .contentProcessed { _ in c.cancel() })
            }
            control = nil
        }
    }

    /// Annex-B access unit from the encoder. Thread-safe.
    public func sendVideo(_ frame: Data, isKey: Bool) {
        queue.async {
            guard self.wantVideo, self.isVideoLive, let v = self.video else { return }
            if self.waitKey {
                guard isKey else { return }
                self.waitKey = false
            }
            if self.pendingBytes > Self.maxPending {
                // ponytail: Wi-Fi can't keep up -> drop until next IDR; keeps memory under the 50 MB broadcast-extension cap.
                self.dropped += 1
                self.waitKey = true
                self.onKeyFrameRequest()
                return
            }
            let pkt = Self.videoPacket(frame)
            self.pendingBytes += pkt.count
            self.sent += 1
            v.send(content: pkt, completion: .contentProcessed { err in
                self.pendingBytes -= pkt.count
                if let err { self.videoFailed(v, err) }
            })
        }
    }

    /// Frames handed to TCP / dropped for backpressure since start (diagnostics).
    public private(set) var sent = 0
    public private(set) var dropped = 0

    // MARK: control channel (all on `queue`)

    private func connectControl() {
        guard running, control == nil else { return }
        onLog("Connecting to speedometer \(host):\(controlPort)…")
        let c = NWConnection(host: host, port: controlPort, using: Self.tcp)
        control = c
        c.stateUpdateHandler = { [weak self] state in
            guard let self, self.control === c else { return }
            switch state {
            case .ready:
                self.onLog("Control channel connected")
                self.send(7, 41, Data(#"{"size":"960x360"}"#.utf8))
                self.readHeader(c)
                // Some firmware never sends the key exchange; UMOX forces SHOW after 2 s.
                self.queue.asyncAfter(deadline: .now() + 2) {
                    guard self.control === c, !self.wantVideo else { return }
                    self.onLog("No handshake after 2 s, sending SHOW")
                    self.show()
                }
            case .failed(let e), .waiting(let e):
                self.controlLost(c, e)
            default:
                break
            }
        }
        c.start(queue: queue)
    }

    private func controlLost(_ c: NWConnection, _ error: Error?) {
        guard control === c else { return }
        onLog("Control channel lost: \(error.map { "\($0)" } ?? "closed")")
        c.cancel()
        control = nil
        stopVideo()
        if running { queue.asyncAfter(deadline: .now() + 2) { self.connectControl() } }
    }

    private func readHeader(_ c: NWConnection) {
        c.receive(minimumIncompleteLength: 8, maximumLength: 8) { [weak self] hdr, _, _, err in
            guard let self, self.control === c else { return }
            guard let hdr, hdr.count == 8, err == nil else { return self.controlLost(c, err) }
            let channel = hdr.le32(0), len = Int(hdr.le32(4))
            guard (2...65_536).contains(len) else { return self.controlLost(c, nil) }
            c.receive(minimumIncompleteLength: len, maximumLength: len) { body, _, _, err in
                guard self.control === c else { return }
                guard let body, body.count == len, err == nil else { return self.controlLost(c, err) }
                self.handle(channel: channel, msg: body.le16(0), body: Data(body.dropFirst(2)))
                self.readHeader(c)
            }
        }
    }

    private func handle(channel: UInt32, msg: UInt16, body: Data) {
        switch msg {
        case 9: // heartbeat ping every 1 s: echo index + uptime or the cluster drops us
            send(1, 9, body)
        case 37: // day/night; must be acked with 39 or the cluster leaves the map view
            send(7, 39, body)
            onLog("Speedometer mode \(String(decoding: body, as: UTF8.self))")
        case 49: // P-256 ECDH key exchange gates the display; video itself is plain H.264
            send(1, 49, P256.KeyAgreement.PrivateKey().publicKey.derRepresentation)
            onLog("Key exchange done")
            show()
        case 11: // keyframe request
            send(7, 11, Self.ok)
            onKeyFrameRequest()
        case 10, 27:
            send(7, msg, Self.ok)
        default:
            let text = String(decoding: body, as: UTF8.self)
            if text.contains("DS_MSG_HU_REQ_SHOW") {
                send(1, 2, Data(#"{"msg_id":"DS_RESP_SHOW","result":0,"status":"STREAMING"}"#.utf8))
                show()
            } else if text.contains("DS_MSG_HU_REQ_HIDE") {
                onLog("Speedometer left the map view")
                stopVideo()
            } else {
                onLog("ch\(channel) msg\(msg) \(text.prefix(80))")
            }
        }
    }

    private func send(_ channel: UInt32, _ msg: UInt16, _ body: Data) {
        control?.send(content: Self.packet(channel: channel, msg: msg, body: body), completion: .contentProcessed { _ in })
    }

    // MARK: video channel (all on `queue`)

    private func show() {
        send(7, 1, Self.ok) // MapProjectionSignal SHOW
        wantVideo = true
        connectVideo()
    }

    private func connectVideo() {
        guard running, wantVideo, video == nil else {
            if isVideoLive { onKeyFrameRequest() }
            return
        }
        let v = NWConnection(host: host, port: videoPort, using: Self.tcp)
        video = v
        v.stateUpdateHandler = { [weak self] state in
            guard let self, self.video === v else { return }
            switch state {
            case .ready:
                self.pendingBytes = 0
                self.waitKey = true
                self.setLive(true)
                self.onLog("Video channel connected, streaming 960x360")
                self.onKeyFrameRequest()
            case .failed(let e), .waiting(let e):
                self.videoFailed(v, e)
            default:
                break
            }
        }
        v.start(queue: queue)
    }

    private func videoFailed(_ v: NWConnection, _ error: Error?) {
        guard video === v else { return }
        onLog("Video channel lost: \(error.map { "\($0)" } ?? "closed"), retrying")
        setLive(false)
        v.cancel()
        video = nil
        queue.asyncAfter(deadline: .now() + 1) { self.connectVideo() }
    }

    private func stopVideo() {
        wantVideo = false
        setLive(false)
        video?.cancel()
        video = nil
    }

    private func setLive(_ value: Bool) { liveLock.lock(); live = value; liveLock.unlock() }

    // MARK: wire format

    static let ok = Data(#"{"code":0}"#.utf8)
    static let maxPending = 1 << 20
    static var tcp: NWParameters {
        let t = NWProtocolTCP.Options()
        t.noDelay = true
        return NWParameters(tls: nil, tcp: t)
    }

    /// [u32 channel][u32 len = 2 + body][u16 msg][body], little-endian.
    public static func packet(channel: UInt32, msg: UInt16, body: Data) -> Data {
        le32(channel) + le32(UInt32(body.count + 2)) + Data([UInt8(msg & 0xFF), UInt8(msg >> 8)]) + body
    }

    /// Video: [u32 channel = 2][u32 len][Annex-B NALUs].
    public static func videoPacket(_ annexB: Data) -> Data {
        le32(2) + le32(UInt32(annexB.count)) + annexB
    }
}

func le32(_ v: UInt32) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }

extension Data {
    func le32(_ o: Int) -> UInt32 { (0..<4).reduce(0) { $0 | UInt32(self[startIndex + o + $1]) << (8 * $1) } }
    func le16(_ o: Int) -> UInt16 { UInt16(self[startIndex + o]) | UInt16(self[startIndex + o + 1]) << 8 }
}
