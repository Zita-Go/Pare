// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "Pare",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "Pare", targets: ["Pare"]),
        .library(name: "PareCore", targets: ["PareCore"]),
    ],
    targets: [
        .target(name: "PareCore"),
        .executableTarget(
            name: "Pare",
            dependencies: ["PareCore"],
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("Carbon"),
                .linkedFramework("UserNotifications"),
            ]
        ),
        .testTarget(
            name: "PareCoreTests",
            dependencies: ["PareCore"]
        ),
    ],
    swiftLanguageModes: [.v5]
)
