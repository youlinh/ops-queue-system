import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const tokens = readFileSync(resolve(process.cwd(), 'src/styles/tokens.css'), 'utf8')
const base = readFileSync(resolve(process.cwd(), 'src/styles/base.css'), 'utf8')
const legacy = readFileSync(resolve(process.cwd(), 'src/styles/legacy.css'), 'utf8')

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

  it('retains legacy selectors until their consuming templates migrate', () => {
    expect(base).toContain("@import './legacy.css';")
    expect(legacy).toContain('var(--ui-ground)')
    expect(legacy).not.toContain('color: var(--ui-accent);')
    expect(tokens).not.toContain('--ui-legacy-color-')
    expect(legacy).not.toContain('var(--ui-legacy-color-')
    expect(legacy).not.toMatch(/var\(--(?:navy|navy-soft|blue|cyan|line|muted|surface)\)/)
    expect(legacy).not.toMatch(/#[0-9a-fA-F]{3,6}\b/)
    expect(legacy).not.toMatch(/rgba?\(/)
    expect(legacy).toContain('.auth-page')
    expect(legacy).toContain('.task-filters')
    expect(legacy).toContain('.dialog-backdrop')
  })

  it('maps links to the link accent while ordinary supporting text stays grayscale', () => {
    expect(legacy).toMatch(/\.inline-link\s*\{[^}]*color:\s*var\(--ui-accent-link\);/s)
    expect(legacy).toMatch(
      /\.compact-task-list a,\s*\.redistribution-results a,\s*\.manual-notice a\s*\{[^}]*color:\s*var\(--ui-accent-link\);/s,
    )
    expect(legacy).toMatch(
      /\.empty-panel > p:not\(\.eyebrow\)\s*\{[^}]*color:\s*var\(--ui-text-secondary\);/s,
    )
    expect(legacy).toMatch(
      /\.redistribution-section h4,\s*\.manual-notice strong\s*\{[^}]*color:\s*var\(--ui-text-secondary\);/s,
    )
  })
})
