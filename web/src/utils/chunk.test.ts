import { describe, expect, it } from 'vitest'
import { calculateTotalChunks, CHUNK_SIZE, createChunks } from './chunk'

function createFile(name: string, size: number): File {
  const content = new Uint8Array(size)
  for (let i = 0; i < size; i++) {
    content[i] = i % 256
  }
  return new File([content], name)
}

describe('chunk utils', () => {
  it('should split file into expected chunks', () => {
    const chunkSize = 1024
    const file = createFile('test.bin', chunkSize + 1)
    const chunks = createChunks(file, chunkSize)

    expect(chunks).toHaveLength(2)
    expect(chunks[0].index).toBe(0)
    expect(chunks[0].size).toBe(chunkSize)
    expect(chunks[1].index).toBe(1)
    expect(chunks[1].size).toBe(1)
  })

  it('should return empty array for empty file', () => {
    const file = createFile('empty.bin', 0)
    const chunks = createChunks(file)
    expect(chunks).toHaveLength(0)
  })

  it('should calculate total chunks correctly', () => {
    expect(calculateTotalChunks(0)).toBe(0)
    expect(calculateTotalChunks(1)).toBe(1)
    expect(calculateTotalChunks(CHUNK_SIZE)).toBe(1)
    expect(calculateTotalChunks(CHUNK_SIZE + 1)).toBe(2)
  })
})
