import { md5 } from 'js-md5'
import { describe, it, expect } from 'vitest'
import { fullHash, identityHash } from './fileHash'

const SAMPLE_SIZE = 50 * 1024 * 1024

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

  it('computes sampled identity hash for large files', async () => {
    const size = 150 * 1024 * 1024
    const content = new Uint8Array(size)
    for (let i = 0; i < size; i++) {
      content[i] = i % 251
    }
    const file = new File([content], 'large.bin')

    const hash = md5.create()
    hash.update(content.subarray(0, SAMPLE_SIZE))
    hash.update(content.subarray(Math.floor(size / 2), Math.floor(size / 2) + SAMPLE_SIZE))
    hash.update(content.subarray(size - SAMPLE_SIZE))
    const expected = hash.hex()

    const identity = await identityHash(file)
    expect(identity).toBe(expected)
  })
})
