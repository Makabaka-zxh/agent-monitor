// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AgentMonitor",
    platforms: [.macOS(.v14)],
    products: [.executable(name: "Monitor", targets: ["Monitor"])],
    targets: [
        .executableTarget(name: "Monitor", resources: [.copy("Resources")]),
        .testTarget(name: "MonitorTests", dependencies: ["Monitor"])
    ]
)
