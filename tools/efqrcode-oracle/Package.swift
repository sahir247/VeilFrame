// swift-tools-version:5.7
import PackageDescription

let package = Package(
    name: "EFQRCodeOracle",
    platforms: [
        .macOS(.v12)
    ],
    dependencies: [
        .package(url: "https://github.com/EFPrefix/swift_qrcodejs.git", from: "2.3.1")
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
