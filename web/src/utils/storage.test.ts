import { describe, expect, it } from 'vitest'
import { bytesToUnitValue, getStorageUnitFactor, normalizeStorageUnit, unitValueToBytes } from './storage'

describe('storage', () => {
  describe('normalizeStorageUnit', () => {
    it('returns the unit itself for valid units', () => {
      expect(normalizeStorageUnit('MB')).toBe('MB')
      expect(normalizeStorageUnit('gb')).toBe('GB')
      expect(normalizeStorageUnit('  Tb ')).toBe('TB')
    })

    it('defaults to GB for unknown or empty units', () => {
      expect(normalizeStorageUnit('')).toBe('GB')
      expect(normalizeStorageUnit('PB')).toBe('GB')
    })
  })

  describe('getStorageUnitFactor', () => {
    it('returns correct byte factor', () => {
      expect(getStorageUnitFactor('MB')).toBe(1024 * 1024)
      expect(getStorageUnitFactor('GB')).toBe(1024 * 1024 * 1024)
      expect(getStorageUnitFactor('TB')).toBe(1024 * 1024 * 1024 * 1024)
    })

    it('defaults to GB factor for unknown units', () => {
      expect(getStorageUnitFactor('PB')).toBe(1024 * 1024 * 1024)
    })
  })

  describe('bytesToUnitValue', () => {
    it('converts bytes to the target unit', () => {
      expect(bytesToUnitValue(1024 * 1024, 'MB')).toBe('1')
      expect(bytesToUnitValue(1024 * 1024 * 1024, 'GB')).toBe('1')
      expect(bytesToUnitValue(1024 * 1024 * 1024 * 1024, 'TB')).toBe('1')
    })

    it('returns 0 for zero, undefined or invalid input', () => {
      expect(bytesToUnitValue(0, 'GB')).toBe('0')
      expect(bytesToUnitValue(undefined, 'GB')).toBe('0')
      expect(bytesToUnitValue('invalid', 'GB')).toBe('0')
    })
  })

  describe('unitValueToBytes', () => {
    it('converts unit value to bytes', () => {
      expect(unitValueToBytes(1, 'MB')).toBe(String(1024 * 1024))
      expect(unitValueToBytes(1, 'GB')).toBe(String(1024 * 1024 * 1024))
      expect(unitValueToBytes(1, 'TB')).toBe(String(1024 * 1024 * 1024 * 1024))
    })

    it('returns 0 for zero or invalid input', () => {
      expect(unitValueToBytes(0, 'GB')).toBe('0')
      expect(unitValueToBytes('invalid', 'GB')).toBe('0')
    })
  })
})
