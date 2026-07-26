import { describe, expect, it } from 'vitest'
import { toShanghaiInstant } from '../shanghai-time'

describe('Shanghai task time', () => {
  it('encodes datetime-local values with an explicit Shanghai offset', () => {
    expect(toShanghaiInstant('2026-07-25T20:00'))
      .toBe('2026-07-25T20:00:00+08:00')
  })
})
