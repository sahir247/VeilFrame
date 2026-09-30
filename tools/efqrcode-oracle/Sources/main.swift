import Foundation
import QRCodeSwift

struct OracleCorpus: Codable {
    let oracle: OracleMetadata
    let vectors: [OracleVector]
}

struct OracleMetadata: Codable {
    let efqrcode_version: String
    let qrcode_swift_version: String
    let qrcode_swift_revision: String
    let generator_commit: String
    let description: String
    let scope: String
}

struct OracleVector: Codable {
    let name: String
    let text: String
    let level: String
    let version: Int
    let size: Int
    let bitString: String
}

/// Capacity limit length table matching QRCodeType.QRCodeLimitLength from QRCodeSwift (swift_qrcodejs).
/// Declared locally because QRCodeType is internal to the QRCodeSwift module.
private struct QRCodeType {
    static let QRCodeLimitLength: [[Int]] = [
        [17, 14, 11, 7],
        [32, 26, 20, 14],
        [53, 42, 32, 24],
        [78, 62, 46, 34],
        [106, 84, 60, 44],
        [134, 106, 74, 58],
        [154, 122, 86, 64],
        [192, 152, 108, 84],
        [230, 180, 130, 98],
        [271, 213, 151, 119],
        [321, 251, 177, 137],
        [367, 287, 203, 155],
        [425, 331, 241, 177],
        [458, 362, 258, 194],
        [520, 412, 292, 220],
        [586, 450, 322, 250],
        [644, 504, 364, 280],
        [718, 560, 394, 310],
        [792, 624, 442, 338],
        [858, 666, 482, 382],
        [929, 711, 509, 403],
        [1003, 779, 565, 439],
        [1091, 857, 611, 461],
        [1171, 911, 661, 511],
        [1273, 997, 715, 535],
        [1367, 1059, 751, 593],
        [1465, 1125, 805, 625],
        [1528, 1190, 868, 658],
        [1628, 1264, 908, 698],
        [1732, 1370, 982, 742],
        [1840, 1452, 1030, 790],
        [1952, 1538, 1112, 842],
        [2068, 1628, 1168, 898],
        [2188, 1722, 1228, 958],
        [2303, 1809, 1283, 983],
        [2431, 1911, 1351, 1051],
        [2563, 1989, 1423, 1093],
        [2699, 2099, 1499, 1139],
        [2809, 2213, 1579, 1219],
        [2953, 2331, 1663, 1273]
    ]
}

let namedVectors: [(name: String, text: String, level: QRErrorCorrectLevel, levelStr: String)] = [
    // 1. Canonical Upstream QRCodeSwiftTests
    ("UPSTREAM_SIMPLE", "https://gist.github.com/agentgt/1700331", .H, "H"),
    ("UPSTREAM_LOW_EC", "https://passport.bilibili.com/qrcode/h5/login?oauthKey=2f3ab118e214e7ad69683df50918a481", .L, "L"),
    ("UPSTREAM_BORDERLESS", "https://github.com/ApolloZhu", .H, "H"),
    ("UPSTREAM_EFQRCODE", "https://github.com/EyreFree/EFQRCode", .H, "H"),
    ("UPSTREAM_EMPTY", "", .L, "L"),
    ("UPSTREAM_PI_STRESS_V26", "3.1415926535897932384626433832795028841971693993751058209749445923078164062862089986280348253421170679821480865132823066470938446095505822317253594081284811174502841027019385211055596446229489549303819644288109756659334461284756482337867831652712019091456485669234603486104543266482133936072602491412737245870066063155881748815209209628292540917153643678925903600113305305488204665213841469519415116094330572703657595919530921861173819326117931051185480744623799627495673518857527248912279381830119491298336733624406566430860213949463952247371907021798609437027705392171762931767523846748184676694051320005681271452635608277857713427577896091736371787214684409012249534301465495853710507922796892589235420199561121290219608640344181598136297747713099605187072113499999983729780499510597317328160963185950244594553469083026425223082533446850352619311881710100031378387528865875332083814206171776691473035982534904287554687311595628638823537875937519577818577805321712268066130019278766111959092164201989", .M, "M"),

    // 2. Named Semantic Domains
    ("V1_L_A", "A", .L, "L"),
    ("V1_M_HELLO_WORLD", "HELLO WORLD", .M, "M"),
    ("V1_Q_NUMERIC", "12345678", .Q, "Q"),
    ("V1_H_SHORT", "TEST", .H, "H"),
    ("V2_L_URL", "https://veilframe.app", .L, "L"),
    ("V2_M_ALPHANUM", "VEILFRAME PRIVACY CLEANER", .M, "M"),
    ("V3_Q_TEXT", "Privacy & Security QR", .Q, "Q"),
    ("V5_H_URL", "https://github.com/sahir247/VeilFrame", .H, "H"),
    ("V4_L_LONG_URL", "https://example.com/deep/path/to/resource?param1=value1&param2=value2", .L, "L"),
    ("V4_M_MESSAGE", "The quick brown fox jumps over the lazy dog 1234567890!", .M, "M"),
    ("V7_M_TYPE_TABLE", "This payload is deliberately crafted to be long enough to exceed Version 6 capacity and trigger the Version 7+ type number format table in QR code generation.", .M, "M"),
    ("V10_Q_PAYLOAD", "VeilFrame high-density QR code payload testing Version 10 module matrix with Error Correction Level Q for exhaustive bit-for-bit parity validation against upstream QRCodeSwift oracle. 1234567890!@#$%^&*()_+", .Q, "Q"),

    // 3. UTF-8 & Multilingual
    ("UTF8_BENGALI_H", "বাংলা ভাষার কিউআর কোড", .H, "H"),
    ("UTF8_CHINESE_M", "中文测试二维码", .M, "M"),
    ("UTF8_JAPANESE_Q", "日本語のQRコードテスト", .Q, "Q"),
    ("UTF8_EMOJI_H", "🔒🛡️ VeilFrame Privacy 🚀", .H, "H")
]

var results: [OracleVector] = []

print("==> Generating canonical named vectors...")
for v in namedVectors {
    let qrcode = try QRCode(v.text, errorCorrectLevel: v.level)
    let model = qrcode.model
    let n = model.moduleCount
    var bits = ""
    bits.reserveCapacity(n * n)
    for r in 0..<n {
        for c in 0..<n {
            bits.append(model.isDark(r, c) ? "1" : "0")
        }
    }
    results.append(OracleVector(
        name: v.name,
        text: v.text,
        level: v.levelStr,
        version: model.typeNumber,
        size: n,
        bitString: bits
    ))
}

print("==> Generating systematic matrix across Versions 1..26 x L/M/Q/H...")
let levels: [(QRErrorCorrectLevel, String, Int)] = [
    (.L, "L", 0),
    (.M, "M", 1),
    (.Q, "Q", 2),
    (.H, "H", 3)
]

for v in 1...26 {
    for (lvl, lvlStr, col) in levels {
        let minLen = (v == 1) ? 1 : (QRCodeType.QRCodeLimitLength[v - 2][col] + 1)
        let maxLen = QRCodeType.QRCodeLimitLength[v - 1][col]
        let targetLen = min(maxLen, minLen + (maxLen - minLen) / 2)

        var text = ""
        for i in 0..<targetLen {
            let charCode = 65 + ((i * 7 + 3) % 26)
            text.append(Character(UnicodeScalar(charCode)!))
        }

        let qrcode = try QRCode(text, errorCorrectLevel: lvl)
        let model = qrcode.model
        let n = model.moduleCount
        var bits = ""
        bits.reserveCapacity(n * n)
        for r in 0..<n {
            for c in 0..<n {
                bits.append(model.isDark(r, c) ? "1" : "0")
            }
        }

        results.append(OracleVector(
            name: "V\(v)_\(lvlStr)_EXHAUSTIVE",
            text: text,
            level: lvlStr,
            version: model.typeNumber,
            size: n,
            bitString: bits
        ))
    }
}

print("==> Total oracle vectors generated: \(results.count)")

let metadata = OracleMetadata(
    efqrcode_version: "7.0.3",
    qrcode_swift_version: "2.3.1",
    qrcode_swift_revision: "d1605333f7edac39b4518538ef4f2638fdd2e4d6",
    generator_commit: "8ddc531",
    description: "Upstream QRCodeSwift matrix oracle vectors for EFQRCode 7.0.3 parity verification",
    scope: "Tier 4A (Versions 1-26, L/M/Q/H, UTF-8, URLs, ApolloZhu tests)"
)

let corpus = OracleCorpus(oracle: metadata, vectors: results)

let encoder = JSONEncoder()
encoder.outputFormatting = [.prettyPrinted]
let jsonData = try encoder.encode(corpus)

let outputPath = CommandLine.arguments.count > 1
    ? CommandLine.arguments[1]
    : "android/app/src/test/resources/tier4_upstream_oracle_matrices.json"

let url = URL(fileURLWithPath: outputPath)
try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
try jsonData.write(to: url)
print("==> Successfully wrote upstream oracle matrices to \(outputPath) (\(jsonData.count) bytes)")
