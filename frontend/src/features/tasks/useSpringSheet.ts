import { computed, ref, unref, type Ref } from 'vue'

export interface PointerSample {
  position: number
  time: number
}

type MaybeRefOrGetter<T> = T | Ref<T> | (() => T)

export interface SpringSheetOptions {
  /** The measured width (desktop) or height (mobile) of the sheet. */
  extent: MaybeRefOrGetter<number>
  /** Desktop sheets travel horizontally; mobile sheets travel vertically. */
  axis?: MaybeRefOrGetter<'x' | 'y'>
  /** Allows callers and tests to provide a reactive reduced-motion preference. */
  reducedMotion?: MaybeRefOrGetter<boolean>
  /** Called after the close settle completes. */
  onDismiss?: () => void
}

const CLOSED_GUTTER = 40
const STIFFNESS = 420
const DAMPING = 42
const REST_VELOCITY = 3
const REST_DISTANCE = .6

export function projectEndpoint(position: number, velocity: number, rate = .99) {
  return position + (velocity / 1000) * rate / (1 - rate)
}

export function rubberbandDistance(overshoot: number, dimension: number, constant = .55) {
  return (overshoot * dimension * constant)
    / (dimension + constant * Math.abs(overshoot))
}

export function velocityFromSamples(
  samples: readonly PointerSample[],
  now: number,
  windowMs = 90,
) {
  const recent = samples.filter(sample => sample.time >= now - windowMs)
  const first = recent[0]
  const last = recent.at(-1)

  if (!first || !last || last.time <= first.time) return 0
  return (last.position - first.position) / ((last.time - first.time) / 1000)
}

export function shouldDismissSheet(projected: number, extent: number, velocity: number) {
  return projected > extent * .38 || velocity > 620
}

function resolve<T>(value: MaybeRefOrGetter<T>): T {
  return typeof value === 'function'
    ? (value as () => T)()
    : unref(value)
}

function eventPosition(event: PointerEvent, axis: 'x' | 'y') {
  return axis === 'y' ? event.clientY : event.clientX
}

function performanceNow() {
  return typeof performance === 'undefined' ? Date.now() : performance.now()
}

/**
 * Owns the transform/opacity-facing state of a task detail sheet. It deliberately
 * has no DOM writes so the sheet component can choose the appropriate desktop or
 * mobile presentation while retaining one gesture model.
 */
export function useSpringSheet(options: SpringSheetOptions) {
  const axis = () => resolve(options.axis ?? 'x')
  const extent = () => Math.max(0, resolve(options.extent))
  const closedPosition = () => extent() + CLOSED_GUTTER
  const position = ref(closedPosition())
  const dragging = ref(false)
  const progress = computed(() => {
    const travel = closedPosition()
    return travel === 0 ? 1 : 1 - Math.min(1, Math.max(0, position.value / travel))
  })

  let frameId: number | undefined
  let springVelocity = 0
  let activePointerId: number | undefined
  let grabOffset = 0
  let samples: PointerSample[] = []
  let captureTarget: Element | undefined

  const reducedMotion = () => {
    if (options.reducedMotion !== undefined) return resolve(options.reducedMotion)
    return typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  }

  function stop() {
    if (frameId !== undefined && typeof window !== 'undefined') {
      window.cancelAnimationFrame(frameId)
    }
    frameId = undefined
    springVelocity = 0
  }

  function settle(target: number, initialVelocity = 0, onDone?: () => void) {
    stop()

    if (reducedMotion() || typeof window === 'undefined' || typeof window.requestAnimationFrame !== 'function') {
      position.value = target
      onDone?.()
      return
    }

    springVelocity = initialVelocity
    let last = performanceNow()

    const tick = (now: number) => {
      const dt = Math.min(.032, Math.max(0, (now - last) / 1000))
      last = now
      const acceleration = -STIFFNESS * (position.value - target) - DAMPING * springVelocity
      springVelocity += acceleration * dt
      position.value += springVelocity * dt

      if (Math.abs(springVelocity) < REST_VELOCITY && Math.abs(position.value - target) < REST_DISTANCE) {
        position.value = target
        frameId = undefined
        springVelocity = 0
        onDone?.()
        return
      }
      frameId = window.requestAnimationFrame(tick)
    }

    frameId = window.requestAnimationFrame(tick)
  }

  function releasePointer(event: PointerEvent) {
    if (!captureTarget || activePointerId === undefined) return
    if (typeof captureTarget.releasePointerCapture === 'function') {
      captureTarget.releasePointerCapture(activePointerId)
    }
    captureTarget = undefined
  }

  function open() {
    settle(0, -260)
  }

  function close(releaseVelocity = 0) {
    settle(closedPosition(), Math.max(releaseVelocity, 180), options.onDismiss)
  }

  function onPointerDown(event: PointerEvent) {
    if (event.button !== 0) return

    stop()
    activePointerId = event.pointerId
    const coordinate = eventPosition(event, axis())
    grabOffset = coordinate - position.value
    samples = [{ position: coordinate, time: performanceNow() }]
    dragging.value = true
    captureTarget = event.currentTarget instanceof Element ? event.currentTarget : undefined
    if (captureTarget && typeof captureTarget.setPointerCapture === 'function') {
      captureTarget.setPointerCapture(event.pointerId)
    }
  }

  function onPointerMove(event: PointerEvent) {
    if (!dragging.value || activePointerId !== event.pointerId) return

    const coordinate = eventPosition(event, axis())
    const next = coordinate - grabOffset
    position.value = next < 0 ? rubberbandDistance(next, extent()) : next

    const now = performanceNow()
    samples.push({ position: coordinate, time: now })
    samples = samples.filter(sample => sample.time >= now - 90)
  }

  function onPointerUp(event: PointerEvent) {
    if (!dragging.value || activePointerId !== event.pointerId) return

    const now = performanceNow()
    const coordinate = eventPosition(event, axis())
    samples.push({ position: coordinate, time: now })
    const velocity = velocityFromSamples(samples, now)
    dragging.value = false
    releasePointer(event)
    activePointerId = undefined
    samples = []

    if (event.type === 'pointercancel') {
      settle(position.value <= extent() * .5 ? 0 : closedPosition())
      return
    }

    const projected = projectEndpoint(position.value, velocity)
    if (shouldDismissSheet(projected, extent(), velocity)) close(velocity)
    else settle(0, velocity)
  }

  return {
    position,
    progress,
    dragging,
    onPointerDown,
    onPointerMove,
    onPointerUp,
    open,
    close,
    stop,
  }
}
