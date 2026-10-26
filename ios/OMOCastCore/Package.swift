// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "OMOCastCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "OMOCastCore", targets: ["OMOCastCore"])],
    targets: [
        .target(name: "OMOCastCore"),
        // macOS-only check: protocol bytes, scaling, and a live stream to tools/carplay_sim.py's mock cluster.
        .executableTarget(name: "omocast-selftest", dependencies: ["OMOCastCore"]),
    ]
)
