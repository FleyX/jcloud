import { describe, it, expect } from 'vitest'
import { fullHash, identityHash } from './fileHash'

async function buildFile(content: string): Promise<File> {
  return new File([content], 'test.txt', { type: 'text/plain' })
}

describe('fileHash', () => {
  it('computes full md5 for small files', async () => {
    const file = await buildFile('hello')
    const hash = await fullHash(file)
    expect(hash).toBe('5d41402abc4b2a76b9719d911017c592')
  })

  it('returns identity hash equal to full hash for small files', async () => {
    const file = await buildFile('hello')
    const identity = await identityHash(file)
    const full = await fullHash(file)
    expect(identity).toBe(full)
  })

  it('returns null for large files to fallback to regular upload', async () => {
    const buffer = new ArrayBuffer(150 * 1024 * 1024)
    const file = new File([buffer], 'large.bin')
    const identity = await identityHash(file)
    expect(identity).toBeNull()
  })
})
