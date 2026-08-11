/**
 * 极简 MD5 实现（纯 TS，无依赖）
 *
 * 用途：分片上传的 fileMd5 与 X-Chunk-MD5（ChunkUploadController 要求）。
 * 浏览器 SubtleCrypto 不支持 MD5，故内联实现。
 * 参考标准 RFC 1321；对 50MB 以内文件足够快。
 */

function safeAdd(x: number, y: number): number {
  const lsw = (x & 0xffff) + (y & 0xffff)
  const msw = (x >> 16) + (y >> 16) + (lsw >> 16)
  return (msw << 16) | (lsw & 0xffff)
}

function rol(num: number, cnt: number): number {
  return (num << cnt) | (num >>> (32 - cnt))
}

function cmn(q: number, a: number, b: number, x: number, s: number, t: number) {
  return safeAdd(rol(safeAdd(safeAdd(a, q), safeAdd(x, t)), s), b)
}
function ff(a: number, b: number, c: number, d: number, x: number, s: number, t: number) {
  return cmn((b & c) | (~b & d), a, b, x, s, t)
}
function gg(a: number, b: number, c: number, d: number, x: number, s: number, t: number) {
  return cmn((b & d) | (c & ~d), a, b, x, s, t)
}
function hh(a: number, b: number, c: number, d: number, x: number, s: number, t: number) {
  return cmn(b ^ c ^ d, a, b, x, s, t)
}
function ii(a: number, b: number, c: number, d: number, x: number, s: number, t: number) {
  return cmn(c ^ (b | ~d), a, b, x, s, t)
}

function md5core(x: number[], len: number): number[] {
  x[len >> 5] |= 0x80 << len % 32
  x[(((len + 64) >>> 9) << 4) + 14] = len

  let a = 1732584193
  let b = -271733879
  let c = -1732584194
  let d = 271733878

  for (let i = 0; i < x.length; i += 16) {
    const oa = a, ob = b, oc = c, od = d
    const [x0, x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15] = x.slice(i, i + 16)

    a = ff(a, b, c, d, x0, 7, -680876936); d = ff(d, a, b, c, x1, 12, -389564586)
    c = ff(c, d, a, b, x2, 17, 606105819); b = ff(b, c, d, a, x3, 22, -1044525330)
    a = ff(a, b, c, d, x4, 7, -176418897); d = ff(d, a, b, c, x5, 12, 1200080426)
    c = ff(c, d, a, b, x6, 17, -1473231341); b = ff(b, c, d, a, x7, 22, -45705983)
    a = ff(a, b, c, d, x8, 7, 1770035416); d = ff(d, a, b, c, x9, 12, -1958414417)
    c = ff(c, d, a, b, x10, 17, -42063); b = ff(b, c, d, a, x11, 22, -1990404162)
    a = ff(a, b, c, d, x12, 7, 1804603682); d = ff(d, a, b, c, x13, 12, -40341101)
    c = ff(c, d, a, b, x14, 17, -1502002290); b = ff(b, c, d, a, x15, 22, 1236535329)

    a = gg(a, b, c, d, x1, 5, -165796510); d = gg(d, a, b, c, x6, 9, -1069501632)
    c = gg(c, d, a, b, x11, 14, 643717713); b = gg(b, c, d, a, x0, 20, -373897302)
    a = gg(a, b, c, d, x5, 5, -701558691); d = gg(d, a, b, c, x10, 9, 38016083)
    c = gg(c, d, a, b, x15, 14, -660478335); b = gg(b, c, d, a, x4, 20, -405537848)
    a = gg(a, b, c, d, x9, 5, 568446438); d = gg(d, a, b, c, x14, 9, -1019803690)
    c = gg(c, d, a, b, x3, 14, -187363961); b = gg(b, c, d, a, x8, 20, 1163531501)
    a = gg(a, b, c, d, x13, 5, -1444681467); d = gg(d, a, b, c, x2, 9, -51403784)
    c = gg(c, d, a, b, x7, 14, 1735328473); b = gg(b, c, d, a, x12, 20, -1926607734)

    a = hh(a, b, c, d, x5, 4, -378558); d = hh(d, a, b, c, x8, 11, -2022574463)
    c = hh(c, d, a, b, x11, 16, 1839030562); b = hh(b, c, d, a, x14, 23, -35309556)
    a = hh(a, b, c, d, x1, 4, -1530992060); d = hh(d, a, b, c, x4, 11, 1272893353)
    c = hh(c, d, a, b, x7, 16, -155497632); b = hh(b, c, d, a, x10, 23, -1094730640)
    a = hh(a, b, c, d, x13, 4, 681279174); d = hh(d, a, b, c, x0, 11, -358537222)
    c = hh(c, d, a, b, x3, 16, -722521979); b = hh(b, c, d, a, x6, 23, 76029189)
    a = hh(a, b, c, d, x9, 4, -640364487); d = hh(d, a, b, c, x12, 11, -421815835)
    c = hh(c, d, a, b, x15, 16, 530742520); b = hh(b, c, d, a, x2, 23, -995338651)

    a = ii(a, b, c, d, x0, 6, -198630844); d = ii(d, a, b, c, x7, 10, 1126891415)
    c = ii(c, d, a, b, x14, 15, -1416354905); b = ii(b, c, d, a, x5, 21, -57434055)
    a = ii(a, b, c, d, x12, 6, 1700485571); d = ii(d, a, b, c, x3, 10, -1894986606)
    c = ii(c, d, a, b, x10, 15, -1051523); b = ii(b, c, d, a, x1, 21, -2054922799)
    a = ii(a, b, c, d, x8, 6, 1873313359); d = ii(d, a, b, c, x15, 10, -30611744)
    c = ii(c, d, a, b, x6, 15, -1560198380); b = ii(b, c, d, a, x13, 21, 1309151649)
    a = ii(a, b, c, d, x4, 6, -145523070); d = ii(d, a, b, c, x11, 10, -1120210379)
    c = ii(c, d, a, b, x2, 15, 718787259); b = ii(b, c, d, a, x9, 21, -343485551)

    a = safeAdd(a, oa); b = safeAdd(b, ob); c = safeAdd(c, oc); d = safeAdd(d, od)
  }
  return [a, b, c, d]
}

function bytesToWords(bytes: Uint8Array): number[] {
  const out: number[] = []
  for (let i = 0; i < bytes.length * 8; i += 8) {
    out[i >> 5] |= bytes[i / 8] << i % 32
  }
  return out
}

function wordsToHex(words: number[]): string {
  const hex = '0123456789abcdef'
  let out = ''
  for (let i = 0; i < words.length * 4; i++) {
    out += hex.charAt((words[i >> 2] >> ((i % 4) * 8 + 4)) & 0x0f) + hex.charAt((words[i >> 2] >> ((i % 4) * 8)) & 0x0f)
  }
  return out
}

/** 计算二进制数据的 MD5（32 位小写 hex） */
export function md5(data: ArrayBuffer | Uint8Array): string {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data)
  return wordsToHex(md5core(bytesToWords(bytes), bytes.length * 8))
}
