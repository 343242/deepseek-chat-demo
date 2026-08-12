import { describe, it, expect } from 'vitest'
import { sha256Hex, CHECKSUM_ALGORITHM } from '../checksum-core'

// 标准 SHA-256 参考向量（NIST / 公开测试集）
const VECTORS: Array<{ input: string; digest: string }> = [
  { input: '', digest: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' },
  { input: 'abc', digest: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad' },
  {
    input: 'abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq',
    digest: '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1',
  },
  {
    input: 'The quick brown fox jumps over the lazy dog',
    digest: 'd7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592',
  },
]

describe('CHECKSUM_ALGORITHM', () => {
  it('声明为 SHA-256（前后端契约一致）', () => {
    expect(CHECKSUM_ALGORITHM).toBe('SHA-256')
  })
})

describe('sha256Hex — 标准向量等价校验', () => {
  for (const { input, digest } of VECTORS) {
    it(`sha256(${JSON.stringify(input)}) === ${digest.slice(0, 12)}…`, async () => {
      const buf = new TextEncoder().encode(input)
      expect(await sha256Hex(buf)).toBe(digest)
    })
  }

  it('Uint8Array 与 ArrayBuffer 输入等价', async () => {
    const u8 = new TextEncoder().encode('abc')
    const ab = u8.buffer.slice(0)
    expect(await sha256Hex(u8)).toBe(await sha256Hex(ab))
  })

  it('Blob 输入与 Uint8Array 输入等价（流式读取路径）', async () => {
    const u8 = new TextEncoder().encode('abc')
    const blob = new Blob([u8])
    expect(await sha256Hex(blob)).toBe(await sha256Hex(u8))
  })

  it('输出为 64 位小写 hex', async () => {
    const out = await sha256Hex(new TextEncoder().encode('x'))
    expect(out).toMatch(/^[0-9a-f]{64}$/)
  })
})
