import { describe, expect, it } from 'vitest'
import {
  projectEndpoint,
  rubberbandDistance,
  shouldDismissSheet,
  useSpringSheet,
  velocityFromSamples,
} from '../useSpringSheet'

describe('sheet physics', () => {
  it('derives release velocity from only the recent sample window', () => {
    expect(velocityFromSamples([
      { position: 0, time: 0 },
      { position: 20, time: 100 },
      { position: 60, time: 150 },
    ], 150, 90)).toBeCloseTo(800)
  })

  it('projects a fast outward gesture past the dismiss threshold', () => {
    const projected = projectEndpoint(120, 900, .99)
    expect(projected).toBeGreaterThan(200)
    expect(shouldDismissSheet(projected, 460, 900)).toBe(true)
  })

  it('resists inward overshoot without a hard stop', () => {
    expect(rubberbandDistance(-100, 460)).toBeLessThan(0)
    expect(Math.abs(rubberbandDistance(-100, 460))).toBeLessThan(100)
  })

  it('dismisses once a projected endpoint crosses 38% even at slow velocity', () => {
    expect(shouldDismissSheet(175, 460, 0)).toBe(true)
    expect(shouldDismissSheet(174, 460, 0)).toBe(false)
  })

  it('uses the grab offset and pointer capture while dragging', () => {
    const grabZone = document.createElement('div')
    const setPointerCapture = vi.fn()
    Object.assign(grabZone, { setPointerCapture })
    const sheet = useSpringSheet({ extent: 460, reducedMotion: true })

    sheet.onPointerDown({
      button: 0, pointerId: 7, clientX: 500, currentTarget: grabZone,
    } as unknown as PointerEvent)
    sheet.onPointerMove({ pointerId: 7, clientX: 420 } as unknown as PointerEvent)

    expect(setPointerCapture).toHaveBeenCalledWith(7)
    expect(sheet.position.value).toBe(420)
    expect(sheet.dragging.value).toBe(true)
  })

  it('settles immediately without a frame loop for reduced motion', () => {
    const requestFrame = vi.spyOn(window, 'requestAnimationFrame')
    const sheet = useSpringSheet({ extent: 460, reducedMotion: true })

    sheet.open()
    expect(sheet.position.value).toBe(0)
    sheet.close()

    expect(sheet.position.value).toBe(500)
    expect(requestFrame).not.toHaveBeenCalled()
  })
})
