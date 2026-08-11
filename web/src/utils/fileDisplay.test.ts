import { describe, it, expect } from 'vitest'
import { inferFileType, formatSize, formatDate, fileIconMap, parentPathName } from './fileDisplay'
import type { FileTypeSource } from './fileDisplay'

describe('fileDisplay', () => {
  describe('inferFileType', () => {
    it('returns folder for folders', () => {
      expect(inferFileType({ type: 'folder', name: 'dir' })).toBe('folder')
    })

    it('infers image from mime type', () => {
      const source: FileTypeSource = { type: 'file', name: 'x', mimeType: 'image/png' }
      expect(inferFileType(source)).toBe('image')
    })

    it('infers video from mime type', () => {
      const source: FileTypeSource = { type: 'file', name: 'x', mimeType: 'video/mp4' }
      expect(inferFileType(source)).toBe('video')
    })

    it('infers audio from mime type', () => {
      const source: FileTypeSource = { type: 'file', name: 'x', mimeType: 'audio/mpeg' }
      expect(inferFileType(source)).toBe('audio')
    })

    it('infers type from extension when mime is missing', () => {
      expect(inferFileType({ type: 'file', name: 'photo.JPG' })).toBe('image')
      expect(inferFileType({ type: 'file', name: 'movie.MP4' })).toBe('video')
      expect(inferFileType({ type: 'file', name: 'sound.mp3' })).toBe('audio')
    })

    it('defaults to doc for unknown files', () => {
      expect(inferFileType({ type: 'file', name: 'report.pdf' })).toBe('doc')
    })
  })

  describe('formatSize', () => {
    it('returns dash for zero or undefined', () => {
      expect(formatSize(0)).toBe('-')
      expect(formatSize(undefined)).toBe('-')
      expect(formatSize('')).toBe('-')
    })

    it('formats bytes', () => {
      expect(formatSize(512)).toBe('512.00 B')
    })

    it('formats kilobytes', () => {
      expect(formatSize(1024)).toBe('1.00 KB')
    })

    it('formats megabytes', () => {
      expect(formatSize(2 * 1024 * 1024)).toBe('2.00 MB')
    })

    it('formats string numbers', () => {
      expect(formatSize('1073741824')).toBe('1.00 GB')
    })
  })

  describe('formatDate', () => {
    it('returns dash for empty value', () => {
      expect(formatDate(undefined)).toBe('-')
      expect(formatDate('')).toBe('-')
    })

    it('extracts date part from datetime string', () => {
      expect(formatDate('2026-07-10 08:50:14')).toBe('2026-07-10')
    })

    it('returns raw string when no time part', () => {
      expect(formatDate('2026-07-10')).toBe('2026-07-10')
    })
  })

  describe('parentPathName', () => {
    it('returns parent dir for nested path', () => {
      expect(parentPathName('/test/test2/a.png')).toBe('/test/test2')
      expect(parentPathName('/a/b/c/')).toBe('/a/b')
    })

    it('returns root slash when path is a root-level item', () => {
      expect(parentPathName('/test')).toBe('/')
      expect(parentPathName('/')).toBe('/')
    })

    it('returns root slash for path without slash', () => {
      expect(parentPathName('a.txt')).toBe('/')
    })

    it('returns dash for empty or undefined', () => {
      expect(parentPathName('')).toBe('-')
      expect(parentPathName(undefined)).toBe('-')
    })
  })

  describe('fileIconMap', () => {
    it('contains all display types', () => {
      expect(Object.keys(fileIconMap).sort()).toEqual(['audio', 'doc', 'folder', 'image', 'video'])
    })
  })
})
