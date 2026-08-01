import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const tokens = readFileSync(resolve(process.cwd(), 'src/styles/tokens.css'), 'utf8')
const base = readFileSync(resolve(process.cwd(), 'src/styles/base.css'), 'utf8')

describe('Apple Design token contract', () => {
  it('defines the approved color, type, radius, shadow, and motion values', () => {
    expect(tokens).toContain('--ui-ground: #f5f5f7')
    expect(tokens).toContain('--ui-surface: #ffffff')
    expect(tokens).toContain('--ui-text: #1d1d1f')
    expect(tokens).toContain('--ui-accent: #0071e3')
    expect(tokens).toContain('--ui-radius-panel: 22px')
    expect(tokens).toContain('--ui-radius-sheet: 26px')
    expect(tokens).toContain('--ui-ease-spring: cubic-bezier(.32, .72, 0, 1)')
  })

  it('defines all three accessibility preference fallbacks', () => {
    expect(base).toContain('@media (prefers-reduced-motion: reduce)')
    expect(base).toContain('@media (prefers-reduced-transparency: reduce)')
    expect(base).toContain('@media (prefers-contrast: more)')
  })
})
