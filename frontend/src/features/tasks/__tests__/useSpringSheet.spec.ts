import { ref } from 'vue'
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

  it('reopens from the current presentation value without a synchronous jump', () => {
    const requestFrame = vi.spyOn(window, 'requestAnimationFrame').mockImplementation(() => 1)
    const sheet = useSpringSheet({ extent: 460, reducedMotion: false })
    sheet.position.value = 40

    sheet.open()

    expect(sheet.position.value).toBe(40)
    sheet.stop()
    requestFrame.mockRestore()
  })

  it('releases pointer capture and clears dragging after a normal pointerup', () => {
    const grabZone = document.createElement('div')
    const setPointerCapture = vi.fn()
    const releasePointerCapture = vi.fn()
    Object.assign(grabZone, { setPointerCapture, releasePointerCapture })
    const sheet = useSpringSheet({ extent: 460, reducedMotion: true })

    sheet.onPointerDown({
      button: 0, pointerId: 8, clientX: 500, currentTarget: grabZone,
    } as unknown as PointerEvent)
    sheet.onPointerMove({ pointerId: 8, clientX: 100 } as unknown as PointerEvent)
    sheet.onPointerUp({ pointerId: 8, clientX: 100, type: 'pointerup' } as unknown as PointerEvent)

    expect(setPointerCapture).toHaveBeenCalledWith(8)
    expect(releasePointerCapture).toHaveBeenCalledWith(8)
    expect(sheet.dragging.value).toBe(false)
    expect(sheet.position.value).toBe(0)
  })

  it('releases pointer capture and settles a cancelled outward drag closed', () => {
    const grabZone = document.createElement('div')
    const releasePointerCapture = vi.fn()
    Object.assign(grabZone, { setPointerCapture: vi.fn(), releasePointerCapture })
    const sheet = useSpringSheet({ extent: 460, reducedMotion: true })

    sheet.onPointerDown({
      button: 0, pointerId: 9, clientX: 500, currentTarget: grabZone,
    } as unknown as PointerEvent)
    sheet.onPointerMove({ pointerId: 9, clientX: 400 } as unknown as PointerEvent)
    sheet.onPointerUp({ pointerId: 9, clientX: 400, type: 'pointercancel' } as unknown as PointerEvent)

    expect(releasePointerCapture).toHaveBeenCalledWith(9)
    expect(sheet.dragging.value).toBe(false)
    expect(sheet.position.value).toBe(500)
  })

  it('switches pointer coordinates when a reactive axis changes', () => {
    const axis = ref<'x' | 'y'>('x')
    const grabZone = document.createElement('div')
    Object.assign(grabZone, { setPointerCapture: vi.fn() })
    const sheet = useSpringSheet({ axis, extent: 460, reducedMotion: true })

    sheet.onPointerDown({ button: 0, pointerId: 10, clientX: 500, clientY: 20, currentTarget: grabZone } as unknown as PointerEvent)
    sheet.onPointerMove({ pointerId: 10, clientX: 420, clientY: 20 } as unknown as PointerEvent)
    expect(sheet.position.value).toBe(420)

    axis.value = 'y'
    sheet.onPointerMove({ pointerId: 10, clientX: 420, clientY: 200 } as unknown as PointerEvent)
    expect(sheet.position.value).toBe(200)
  })
})
