// swift-tools-version:5.7
import PackageDescription

let package = Package(
    name: "EFQRCodeOracle",
    platforms: [
        .macOS(.v12)
    ],
    dependencies: [
        .package(url: "https://github.com/EFPrefix/swift_qrcodejs.git", revision: "d1605333f7edac39b4518538ef4f2638fdd2e4d6")
    ],
    targets: [
        .executableTarget(
            name: "oracle-generator",
            dependencies: [
                .product(name: "QRCodeSwift", package: "swift_qrcodejs")
            ],
            path: "Sources"
        )
    ]
)
