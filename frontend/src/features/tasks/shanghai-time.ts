const WALL_CLOCK_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/

export function toShanghaiInstant(wallClock: string): string {
  if (!WALL_CLOCK_PATTERN.test(wallClock)) {
    throw new Error('Invalid Shanghai wall-clock time')
  }
  const withSeconds = wallClock.length === 16 ? `${wallClock}:00` : wallClock
  return `${withSeconds}+08:00`
}

export function shanghaiDate(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now)
}
