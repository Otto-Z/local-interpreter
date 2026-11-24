// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LocalInterpreterMac",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "LocalInterpreterMac", targets: ["LocalInterpreterMac"])
    ],
    targets: [
        .binaryTarget(
            name: "local_interpreter_coreFFI",
            path: "Frameworks/LocalInterpreterCore.xcframework"
        ),
        .target(
            name: "LocalInterpreterCoreBindings",
            dependencies: ["local_interpreter_coreFFI"],
            path: "Sources/LocalInterpreterCoreBindings"
        ),
        .executableTarget(
            name: "LocalInterpreterMac",
            dependencies: ["LocalInterpreterCoreBindings"],
            path: "Sources/LocalInterpreterMac"
        ),
    ]
)
