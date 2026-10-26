#!/usr/bin/env python3
"""
Uji tanpa hardware untuk mode CarPlay-dongle UMOX.

  make-replay OUT.bin W H      : bikin rekaman "stream dongle" (pesan 0x55AA55AA type 6, header video 20 B)
                                 dari H.264 sintetis ffmpeg, SPS+PPS+IDR dibundel seperti CPC200 asli.
  cluster PORT_BASE OUT.h264   : mock cluster OMO X: kontrol (PORT_BASE) kirim ping 9 + key 0x31,
                                 video (PORT_BASE+1) terima framing Desay [u32 ch=2][u32 len][NALU],
                                 validasi header, tulis Annex-B ke OUT.h264.
Stdlib saja (+ ffmpeg untuk make-replay).
"""
import socket, struct, subprocess, sys, threading, time

MAGIC = 0x55AA55AA


def split_nals(data):
    i, starts = 0, []
    while True:
        j = data.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        s = j - 1 if j > 0 and data[j - 1] == 0 else j
        starts.append(s)
        i = j + 3
    return [data[a:b] for a, b in zip(starts, starts[1:] + [len(data)])]


def access_units(nals):
    """Gabung NAL per frame: SPS/PPS menempel ke slice berikutnya (pola CPC200: [SPS PPS IDR] 1 paket)."""
    au, out = b"", []
    for n in nals:
        t = n[n.index(b"\x00\x00\x01") + 3] & 0x1F
        au += n
        if t in (1, 5):
            out.append(au)
            au = b""
    return out


def make_replay(out, w, h):
    h264 = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", f"testsrc2=size={w}x{h}:rate=30", "-t", "6",
         "-c:v", "libx264", "-profile:v", "high", "-bf", "0", "-g", "600", "-pix_fmt", "yuv420p",
         "-f", "h264", "-"],
        check=True, capture_output=True).stdout
    aus = access_units(split_nals(h264))
    nal_types = [n[n.index(b"\x00\x00\x01") + 3] & 0x1F for n in split_nals(aus[0])]
    assert 7 in nal_types and 8 in nal_types and 5 in nal_types, f"frame pertama harus SPS+PPS+IDR, dapat {nal_types}"
    with open(out, "wb") as f:
        for i, au in enumerate(aus):
            payload = struct.pack("<5I", w, h, 7, i * 33, 0) + au
            f.write(struct.pack("<4I", MAGIC, len(payload), 6, (~6) & 0xFFFFFFFF) + payload)
    print(f"replay {out}: {len(aus)} frame {w}x{h}, {sum(map(len, aus))} B H.264")


def cluster(base, out):
    def ctrl(c):
        c.sendall(struct.pack("<IIH", 1, 14, 9) + struct.pack("<IQ", 1, 12345))
        c.sendall(struct.pack("<IIH", 1, 93, 0x31) + bytes.fromhex(
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004") + bytes(64))
        c.sendall(struct.pack("<IIH", 7, 2 + 16, 37) + b'{"mode":"night"}')
        try:
            while True:
                hdr = c.recv(8, socket.MSG_WAITALL)
                if len(hdr) < 8:
                    break
                ch, ln = struct.unpack("<II", hdr)
                body = c.recv(ln, socket.MSG_WAITALL)
                print(f"[ctrl] ch={ch} msg={struct.unpack('<H', body[:2])[0]} {body[2:40]!r}", flush=True)
        except OSError:
            pass

    def video(c, f):
        frames = nbytes = 0
        try:
            while True:
                hdr = c.recv(8, socket.MSG_WAITALL)
                if len(hdr) < 8:
                    break
                ch, ln = struct.unpack("<II", hdr)
                assert ch == 2, f"channel id {ch} != 2"
                nal = c.recv(ln, socket.MSG_WAITALL)
                assert nal[:3] == b"\x00\x00\x01" or nal[:4] == b"\x00\x00\x00\x01", "bukan Annex-B"
                f.write(nal)
                f.flush()
                frames += 1
                nbytes += ln
                if frames == 1 or frames % 30 == 0:
                    print(f"[video] {frames} frame / {nbytes} B", flush=True)
        except (OSError, AssertionError) as e:
            print(f"[video] STOP: {e}", flush=True)
        print(f"[video] total {frames} frame / {nbytes} B", flush=True)

    f = open(out, "wb")

    def serve(port, fn):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("127.0.0.1", port))
        s.listen()
        while True:
            c, _ = s.accept()
            print(f"[{port}] connect", flush=True)
            threading.Thread(target=fn, args=(c,), daemon=True).start()

    threading.Thread(target=serve, args=(base, ctrl), daemon=True).start()
    serve(base + 1, lambda c: video(c, f))


if __name__ == "__main__":
    if sys.argv[1] == "make-replay":
        make_replay(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
    else:
        cluster(int(sys.argv[2]), sys.argv[3])
