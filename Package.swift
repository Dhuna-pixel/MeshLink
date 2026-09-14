// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MeshLinkCore",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(
            name: "MeshLinkCore",
            targets: ["MeshLinkCore"]
        )
    ],
    dependencies: [],
    targets: [
        .target(
            name: "MeshLinkCore",
            dependencies: [],
            path: "ios/MeshLinkCore"
        ),
        .testTarget(
            name: "MeshLinkCoreTests",
            dependencies: ["MeshLinkCore"],
            path: "ios/MeshLinkCoreTests"
        )
    ]
)
