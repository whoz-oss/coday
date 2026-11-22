/** A rectangle in viewport coordinates (what `getBoundingClientRect()` gives). */
export interface PopoverRect {
  left: number
  top: number
  right: number
  bottom: number
}

export interface PopoverSize {
  width: number
  height: number
}

export interface PopoverPosition {
  /** Offset of the popover from the top of the container, in the visible area of the container. */
  top: number
  /** Offset of the popover from the left of the container. */
  left: number
  /** Whether the popover sits under the anchor or above it (flipped for lack of room below). */
  placement: 'below' | 'above'
  /** The anchor is out of the container's view: the popover is hidden until it comes back. */
  hidden: boolean
}

/** Space kept between the popover and the edges of its container. */
export const POPOVER_MARGIN = 8

/** Preferred width of the floating popovers (composer, waiting line, proposal). */
export const POPOVER_MAX_WIDTH = 520

/** Narrowest width a popover takes while the container has the room for it. */
export const POPOVER_MIN_WIDTH = 360

/**
 * The anchor of a popover from the client rects of a selection (a Range, or the mark of a mirror layer): vertically
 * the LAST line (so the popover sits under the end of the passage), horizontally the start of the FIRST line (so it
 * is left-aligned with the start of the selection). Empty rects (line breaks) are ignored. Null without any rect.
 */
export function anchorFromRects(rects: ArrayLike<PopoverRect>): PopoverRect | null {
  const all = Array.from(rects)
  const visible = all.filter((r) => r.right > r.left || r.bottom > r.top)
  const list = visible.length ? visible : all
  if (!list.length) return null
  const first = list[0]!
  const last = list[list.length - 1]!
  return { left: first.left, top: last.top, right: last.right, bottom: last.bottom }
}

/**
 * Width of a popover inside [container]: preferred 520px, at least 360px, never wider than the container minus the
 * margins. In a container narrower than 360px, the full container width minus the margins. Never negative.
 */
export function popoverWidth(container: PopoverRect, margin = POPOVER_MARGIN): number {
  const available = Math.max(0, container.right - container.left - 2 * margin)
  if (container.right - container.left < POPOVER_MIN_WIDTH) return available
  return Math.min(Math.max(POPOVER_MIN_WIDTH, Math.min(POPOVER_MAX_WIDTH, available)), available)
}

/**
 * Where a popover goes relative to the passage it is about.
 *
 * Under the anchor (its bottom plus [gap]), left-aligned with it and clamped horizontally inside the container; above
 * the anchor when it does not fit below and does fit above (otherwise on the side with the most room, kept inside
 * the container). When the anchor lies entirely outside the vertical extent of the container, `hidden` is true.
 * Whenever the popover fits on its side it follows the anchor exactly, so that it moves with the content on scroll.
 *
 * Pure: every input is a rectangle in the same (viewport) coordinates; the result is relative to the container's
 * top-left corner, so that the caller only adds the scroll offsets of the container to place it inside it.
 */
export function computePopoverPosition(
  anchor: PopoverRect,
  container: PopoverRect,
  size: PopoverSize,
  gap = 8,
  margin = POPOVER_MARGIN
): PopoverPosition {
  const hidden = anchor.bottom < container.top || anchor.top > container.bottom

  const belowTop = anchor.bottom + gap
  const aboveTop = anchor.top - gap - size.height
  const fitsBelow = belowTop + size.height <= container.bottom - margin
  const fitsAbove = aboveTop >= container.top + margin
  let placement: 'below' | 'above'
  if (fitsBelow) placement = 'below'
  else if (fitsAbove) placement = 'above'
  else placement = anchor.top - container.top > container.bottom - anchor.bottom ? 'above' : 'below'

  const top =
    fitsBelow || fitsAbove
      ? placement === 'below'
        ? belowTop
        : aboveTop
      : clamp(placement === 'below' ? belowTop : aboveTop, container.top, container.bottom - size.height)
  const left = clamp(anchor.left, container.left + margin, container.right - margin - size.width)
  return { top: top - container.top, left: left - container.left, placement, hidden }
}

/** `value` kept in [min, max]; `min` wins when the range is empty (a container smaller than the popover). */
function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, max))
}
