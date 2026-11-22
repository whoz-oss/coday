import { anchorFromRects, computePopoverPosition, popoverWidth } from './exchange-popover-position'

describe('anchorFromRects', () => {
  it('takes the vertical extent of the last line and the left of the first one', () => {
    const rects = [
      { left: 50, top: 10, right: 300, bottom: 30 },
      { left: 10, top: 30, right: 120, bottom: 50 },
    ]
    expect(anchorFromRects(rects)).toEqual({ left: 50, top: 30, right: 120, bottom: 50 })
  })

  it('ignores empty rects, and is null without any', () => {
    const rects = [
      { left: 5, top: 10, right: 80, bottom: 30 },
      { left: 80, top: 30, right: 80, bottom: 30 },
    ]
    expect(anchorFromRects(rects)).toEqual({ left: 5, top: 10, right: 80, bottom: 30 })
    expect(anchorFromRects([])).toBeNull()
  })
})

describe('computePopoverPosition', () => {
  const container = { left: 100, top: 50, right: 700, bottom: 450 }
  const size = { width: 420, height: 80 }
  const anchor = (left: number, top: number, right = left + 60, bottom = top + 20) => ({ left, top, right, bottom })

  it('goes under the anchor, left-aligned with it, relative to the container', () => {
    const position = computePopoverPosition(anchor(200, 100), container, size, 8)
    expect(position).toEqual({ top: 100 + 20 + 8 - 50, left: 200 - 100, placement: 'below', hidden: false })
  })

  it('is clamped inside the container on the right', () => {
    const position = computePopoverPosition(anchor(650, 100), container, size, 8, 8)
    expect(position.left).toBe(700 - 8 - 420 - 100)
  })

  it('is clamped inside the container on the left', () => {
    const position = computePopoverPosition(anchor(20, 100), container, size, 8, 8)
    expect(position.left).toBe(8)
  })

  it('flips above the anchor when there is not enough room below', () => {
    const position = computePopoverPosition(anchor(200, 400), container, size, 8)
    expect(position.placement).toBe('above')
    expect(position.top).toBe(400 - 8 - 80 - 50)
    expect(position.hidden).toBe(false)
  })

  it('stays below when it fits neither side but there is more room below', () => {
    const small = { left: 0, top: 0, right: 600, bottom: 100 }
    const position = computePopoverPosition(anchor(10, 5), small, size, 8)
    expect(position.placement).toBe('below')
    expect(position.top).toBe(100 - 80)
  })

  it('is hidden when the anchor is above or below the visible area, and back when it returns', () => {
    expect(computePopoverPosition(anchor(200, -100), container, size).hidden).toBe(true)
    expect(computePopoverPosition(anchor(200, 500), container, size).hidden).toBe(true)
    expect(computePopoverPosition(anchor(200, 440), container, size).hidden).toBe(false)
    expect(computePopoverPosition(anchor(200, 40, 260, 60), container, size).hidden).toBe(false)
  })

  it('keeps the left margin when the container is narrower than the popover', () => {
    const narrow = { left: 0, top: 0, right: 200, bottom: 400 }
    expect(computePopoverPosition(anchor(150, 10), narrow, { width: 184, height: 50 }, 8, 8).left).toBe(8)
  })
})

describe('popoverWidth', () => {
  it('is 420px in a wide container', () => {
    expect(popoverWidth({ left: 0, top: 0, right: 900, bottom: 10 })).toBe(420)
  })

  it('is the container width minus the margins in a narrow one, never negative', () => {
    expect(popoverWidth({ left: 100, top: 0, right: 400, bottom: 10 })).toBe(284)
    expect(popoverWidth({ left: 0, top: 0, right: 4, bottom: 10 })).toBe(0)
  })
})
