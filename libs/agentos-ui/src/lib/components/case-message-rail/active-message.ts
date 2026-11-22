/** Where a message sits in the scroll container, in container content coordinates (px). */
export interface MessageBox {
  id: string
  top: number
  bottom: number
}

/** The part of a scroll container's geometry that tells what is on screen. */
export interface ScrollViewport {
  scrollTop: number
  clientHeight: number
  scrollHeight: number
}

/**
 * A message the reader jumped to from the rail.
 *
 * It stays the active one while it is on screen, even when the scroll ends clamped at the bottom with a later
 * message in view too; `seen` tells a jump still on its way to the message from one the reader has scrolled away from.
 */
export interface RailPin {
  id: string
  seen: boolean
}

export interface ActiveMessage {
  activeId: string | null
  pin: RailPin | null
}

/**
 * Space (px) left above a message the rail scrolls to, so its sender label does not touch the edge.
 *
 * The reading line sits twice as deep: a message just jumped to is always above it.
 */
export const MESSAGE_SCROLL_MARGIN = 12

/**
 * Which message the reader is on: the last one whose top is above the reading line (just under the top of the
 * viewport), or the first one when none is yet. Scrolled to the very end, the line drops to the bottom of the
 * viewport, so the last message on screen is active even when it can never reach the top.
 *
 * A pin overrides that while its message is on screen or still being scrolled to; once the reader has seen it and
 * moved it out of view, the pin is dropped. `boxes` are in conversation order.
 */
export function resolveActiveMessage(
  boxes: readonly MessageBox[],
  viewport: ScrollViewport,
  pin: RailPin | null
): ActiveMessage {
  const viewportBottom = viewport.scrollTop + viewport.clientHeight

  const pinned = pin ? boxes.find((box) => box.id === pin.id) : undefined
  if (pin && pinned) {
    const inView = pinned.bottom > viewport.scrollTop && pinned.top < viewportBottom
    if (inView || !pin.seen) return { activeId: pinned.id, pin: { id: pin.id, seen: pin.seen || inView } }
  }

  const atEnd = viewportBottom >= viewport.scrollHeight - 1
  const readingLine = atEnd ? viewportBottom - 1 : viewport.scrollTop + 2 * MESSAGE_SCROLL_MARGIN
  let active = boxes[0]
  for (const box of boxes) {
    if (box.top > readingLine) break
    active = box
  }
  return { activeId: active?.id ?? null, pin: null }
}
