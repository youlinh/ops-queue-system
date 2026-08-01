const focusableSelector = [
  'a[href]',
  'area[href]',
  'button',
  'input',
  'select',
  'textarea',
  'iframe',
  'object',
  'embed',
  '[contenteditable]:not([contenteditable="false"])',
  '[tabindex]',
].join(',')

function getFocusableChildren(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(focusableSelector))
    .filter((element) => !element.matches(':disabled'))
    .filter((element) => element.tabIndex >= 0)
    .filter(isRenderedAndInteractive)
}

function isRenderedAndInteractive(element: HTMLElement): boolean {
  for (let current: HTMLElement | null = element; current; current = current.parentElement) {
    const style = window.getComputedStyle(current)
    if (
      current.hidden
      || current.hasAttribute('inert')
      || current.getAttribute('aria-hidden') === 'true'
      || style.display === 'none'
      || style.visibility === 'hidden'
      || style.visibility === 'collapse'
    ) {
      return false
    }
  }

  return true
}

function focus(element: HTMLElement) {
  element.focus({ preventScroll: true })
}

export function containFocus(container: HTMLElement, returnTarget: HTMLElement | null): () => void {
  const focusFirst = () => {
    focus(getFocusableChildren(container)[0] ?? container)
  }

  const onKeydown = (event: KeyboardEvent) => {
    if (event.key !== 'Tab') return

    const focusableChildren = getFocusableChildren(container)
    if (focusableChildren.length === 0) {
      event.preventDefault()
      focus(container)
      return
    }

    const first = focusableChildren[0]
    const last = focusableChildren[focusableChildren.length - 1]
    const activeElement = document.activeElement
    if (event.shiftKey && (activeElement === first || !container.contains(activeElement))) {
      event.preventDefault()
      focus(last)
    } else if (!event.shiftKey && (activeElement === last || !container.contains(activeElement))) {
      event.preventDefault()
      focus(first)
    }
  }

  container.addEventListener('keydown', onKeydown)
  focusFirst()

  return () => {
    container.removeEventListener('keydown', onKeydown)
    returnTarget?.focus({ preventScroll: true })
  }
}
