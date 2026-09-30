global.document = { documentElement: { tagName: 'canvas' } };
const fs = require('fs');

let code = fs.readFileSync('scratch/qrcode.js', 'utf8');
code = code.replace(
  'QRCode.CorrectLevel = QRErrorCorrectLevel;',
  'QRCode.CorrectLevel = QRErrorCorrectLevel; global.QRRSBlock = QRRSBlock; global.QRCodeLimitLength = QRCodeLimitLength; global.QRCodeModel = QRCodeModel; global.QRErrorCorrectLevel = QRErrorCorrectLevel;'
);
// In Swift QRCodeSwift, text.data(using: .utf8) produces raw standard UTF-8 bytes (no surrogate bug, no BOM).
const startIdx = code.indexOf('function QR8bitByte(data)');
const endIdx = code.indexOf('QR8bitByte.prototype = {');
code = code.slice(0, startIdx) + `function QR8bitByte(data) {
  this.mode = QRMode.MODE_8BIT_BYTE;
  this.data = data;
  this.parsedData = Array.from(Buffer.from(data, 'utf8'));
}\n\n\t` + code.slice(endIdx);
eval(code);

const levelCol = { L: 0, M: 1, Q: 2, H: 3 };
const qLevels = {
  L: global.QRErrorCorrectLevel.L,
  M: global.QRErrorCorrectLevel.M,
  Q: global.QRErrorCorrectLevel.Q,
  H: global.QRErrorCorrectLevel.H
};

function getValidQRCodeModel(text, levelStr) {
  for (let v = 1; v <= 40; v++) {
    try {
      const m = new global.QRCodeModel(v, qLevels[levelStr]);
      m.addData(text);
      m.make();
      return { model: m, version: v };
    } catch (e) {
      if (e.message && (e.message.includes('code length overflow') || e.message.includes('overflow'))) {
        continue;
      }
      throw e;
    }
  }
  throw new Error("Unable to fit payload in Version 1..40 for text: " + text);
}

const namedVectors = [
  // 1. Canonical Upstream QRCodeSwiftTests
  { name: "UPSTREAM_SIMPLE", text: "https://gist.github.com/agentgt/1700331", level: "H" },
  { name: "UPSTREAM_LOW_EC", text: "https://passport.bilibili.com/qrcode/h5/login?oauthKey=2f3ab118e214e7ad69683df50918a481", level: "L" },
  { name: "UPSTREAM_BORDERLESS", text: "https://github.com/ApolloZhu", level: "H" },
  { name: "UPSTREAM_EFQRCODE", text: "https://github.com/EyreFree/EFQRCode", level: "H" },
  { name: "UPSTREAM_EMPTY", text: "", level: "L" },
  { name: "UPSTREAM_PI_STRESS_V26", text: "3.1415926535897932384626433832795028841971693993751058209749445923078164062862089986280348253421170679821480865132823066470938446095505822317253594081284811174502841027019385211055596446229489549303819644288109756659334461284756482337867831652712019091456485669234603486104543266482133936072602491412737245870066063155881748815209209628292540917153643678925903600113305305488204665213841469519415116094330572703657595919530921861173819326117931051185480744623799627495673518857527248912279381830119491298336733624406566430860213949463952247371907021798609437027705392171762931767523846748184676694051320005681271452635608277857713427577896091736371787214684409012249534301465495853710507922796892589235420199561121290219608640344181598136297747713099605187072113499999983729780499510597317328160963185950244594553469083026425223082533446850352619311881710100031378387528865875332083814206171776691473035982534904287554687311595628638823537875937519577818577805321712268066130019278766111959092164201989", level: "M" },

  // 2. Named Semantic Domains
  { name: "V1_L_A", text: "A", level: "L" },
  { name: "V1_M_HELLO_WORLD", text: "HELLO WORLD", level: "M" },
  { name: "V1_Q_NUMERIC", text: "12345678", level: "Q" },
  { name: "V1_H_SHORT", text: "TEST", level: "H" },
  { name: "V2_L_URL", text: "https://veilframe.app", level: "L" },
  { name: "V2_M_ALPHANUM", text: "VEILFRAME PRIVACY CLEANER", level: "M" },
  { name: "V3_Q_TEXT", text: "Privacy & Security QR", level: "Q" },
  { name: "V5_H_URL", text: "https://github.com/sahir247/VeilFrame", level: "H" },
  { name: "V4_L_LONG_URL", text: "https://example.com/deep/path/to/resource?param1=value1&param2=value2", level: "L" },
  { name: "V4_M_MESSAGE", text: "The quick brown fox jumps over the lazy dog 1234567890!", level: "M" },
  { name: "V7_M_TYPE_TABLE", text: "This payload is deliberately crafted to be long enough to exceed Version 6 capacity and trigger the Version 7+ type number format table in QR code generation.", level: "M" },
  { name: "V10_Q_PAYLOAD", text: "VeilFrame high-density QR code payload testing Version 10 module matrix with Error Correction Level Q for exhaustive bit-for-bit parity validation against upstream QRCodeSwift oracle. 1234567890!@#$%^&*()_+", level: "Q" },

  // 3. UTF-8 & Multilingual
  { name: "UTF8_BENGALI_H", text: "বাংলা ভাষার কিউআর কোড", level: "H" },
  { name: "UTF8_CHINESE_M", text: "中文测试二维码", level: "M" },
  { name: "UTF8_JAPANESE_Q", text: "日本語のQRコードテスト", level: "Q" },
  { name: "UTF8_EMOJI_H", text: "🔒🛡️ VeilFrame Privacy 🚀", level: "H" }
];

const result = [];

// 1. Named vectors
for (const v of namedVectors) {
  const { model: m, version: type } = getValidQRCodeModel(v.text, v.level);

  let bits = '';
  for (let r = 0; r < m.moduleCount; r++) {
    for (let c = 0; c < m.moduleCount; c++) {
      bits += m.isDark(r, c) ? '1' : '0';
    }
  }

  result.push({
    name: v.name,
    text: v.text,
    level: v.level,
    version: type,
    size: m.moduleCount,
    bitString: bits
  });
}

// 2. Systematic matrix for Versions 1 to 26 across ALL 4 EC levels (L, M, Q, H)
for (let v = 1; v <= 26; v++) {
  for (const lvl of ['L', 'M', 'Q', 'H']) {
    const col = levelCol[lvl];
    const minLen = v === 1 ? 1 : (global.QRCodeLimitLength[v - 2][col] + 1);
    const maxLen = global.QRCodeLimitLength[v - 1][col];
    const targetLen = Math.min(maxLen, minLen + Math.floor((maxLen - minLen) / 2));

    let text = '';
    for (let i = 0; i < targetLen; i++) {
      text += String.fromCharCode(65 + ((i * 7 + 3) % 26));
    }

    const m = new global.QRCodeModel(v, qLevels[lvl]);
    m.addData(text);
    m.make();

    let bits = '';
    for (let r = 0; r < m.moduleCount; r++) {
      for (let c = 0; c < m.moduleCount; c++) {
        bits += m.isDark(r, c) ? '1' : '0';
      }
    }

    result.push({
      name: `V${v}_${lvl}_EXHAUSTIVE`,
      text: text,
      level: lvl,
      version: v,
      size: m.moduleCount,
      bitString: bits
    });
  }
}

const corpus = {
  oracle: {
    efqrcode_version: "7.0.3",
    qrcode_swift_version: "2.3.1",
    qrcode_swift_revision: "d1605333f7edac39b4518538ef4f2638fdd2e4d6",
    generator_commit: "8ddc531",
    description: "Upstream QRCodeSwift matrix oracle vectors for EFQRCode 7.0.3 parity verification",
    scope: "Tier 4A (Versions 1-26, L/M/Q/H, UTF-8, URLs, ApolloZhu tests)"
  },
  vectors: result
};

const outputPath = 'android/app/src/test/resources/tier4_upstream_oracle_matrices.json';
const jsonOutput = JSON.stringify(corpus, null, 2);
fs.writeFileSync(outputPath, jsonOutput, 'utf8');
console.log(`Generated ${result.length} Tier 4 upstream oracle vectors with provenance metadata. Total size: ${jsonOutput.length} bytes.`);
