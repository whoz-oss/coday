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
 * At the very end, several messages can be on screen and none can reach the top: the one the reader picked on the
 * rail (`pinnedId`) is then the active one while it is on screen. Elsewhere the reading line decides. `boxes` are in
 * conversation order.
 */
export function resolveActiveMessage(
  boxes: readonly MessageBox[],
  viewport: ScrollViewport,
  pinnedId: string | null
): string | null {
  const viewportBottom = viewport.scrollTop + viewport.clientHeight
  const atEnd = viewportBottom >= viewport.scrollHeight - 1

  const pinned = atEnd && pinnedId !== null ? boxes.find((box) => box.id === pinnedId) : undefined
  if (pinned && pinned.bottom > viewport.scrollTop && pinned.top < viewportBottom) return pinned.id

  const readingLine = atEnd ? viewportBottom - 1 : viewport.scrollTop + 2 * MESSAGE_SCROLL_MARGIN
  let active = boxes[0]
  for (const box of boxes) {
    if (box.top > readingLine) break
    active = box
  }
  return active?.id ?? null
}
