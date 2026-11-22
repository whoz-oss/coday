import { MESSAGE_SCROLL_MARGIN } from './active-message'

/**
 * Attribute a host puts on each message the rail lists, valued with the message id: `[attr.data-message-id]`.
 *
 * The rail reads it to find where each message sits; the host reads it to scroll to one.
 */
export const MESSAGE_ANCHOR_ATTRIBUTE = 'data-message-id'

/** The anchored messages of a scroll container, by message id. */
export function messageAnchors(container: HTMLElement): Map<string, HTMLElement> {
  const anchors = new Map<string, HTMLElement>()
  container.querySelectorAll<HTMLElement>(`[${MESSAGE_ANCHOR_ATTRIBUTE}]`).forEach((element) => {
    const id = element.getAttribute(MESSAGE_ANCHOR_ATTRIBUTE)
    if (id) anchors.set(id, element)
  })
  return anchors
}

/** Top of an element in the content coordinates of its scroll container, whatever its offset parent. */
export function contentTop(container: HTMLElement, element: HTMLElement): number {
  return element.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop
}

/** The scroll position that brings a message just under the top of its container, clamped to what can scroll. */
export function scrollTopFor(container: HTMLElement, anchor: HTMLElement): number {
  const maxScrollTop = Math.max(0, container.scrollHeight - container.clientHeight)
  return Math.min(maxScrollTop, Math.max(0, contentTop(container, anchor) - MESSAGE_SCROLL_MARGIN))
}

/** Whether the reader asked the system to minimise motion (no smooth scroll, no flash then). */
export function prefersReducedMotion(): boolean {
  // matchMedia is missing from some non-browser environments (jsdom).
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * Briefly outlines an element the rail jumped to, so the eye finds it after the scroll.
 *
 * Web Animations rather than a class: the element belongs to the host's template and stylesheet, and the animation
 * restarts cleanly on a second click and removes itself when done.
 */
export function flashMessage(element: HTMLElement): void {
  const ring = { outlineStyle: 'solid', outlineWidth: '2px', outlineOffset: '3px' }
  element.animate?.(
    [
      { ...ring, outlineColor: 'var(--color-accent)' },
      { ...ring, outlineColor: 'var(--color-accent)', offset: 0.4 },
      { ...ring, outlineColor: 'transparent' },
    ],
    { duration: 1000, easing: 'ease-out' }
  )
}
