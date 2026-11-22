import { MESSAGE_SCROLL_MARGIN, MessageBox, resolveActiveMessage, ScrollViewport } from './active-message'

// Three 100px-tall user messages at 0, 400 and 800, in a 2000px conversation seen through a 500px viewport.
const BOXES: MessageBox[] = [
  { id: 'a', top: 0, bottom: 100 },
  { id: 'b', top: 400, bottom: 500 },
  { id: 'c', top: 800, bottom: 900 },
]

const at = (scrollTop: number, scrollHeight = 2000): ScrollViewport => ({ scrollTop, clientHeight: 500, scrollHeight })

describe('resolveActiveMessage', () => {
  it('has no active message when there is none', () => {
    expect(resolveActiveMessage([], at(0), null)).toEqual({ activeId: null, pin: null })
  })

  it('makes the first message active at the top of the conversation', () => {
    expect(resolveActiveMessage(BOXES, at(0), null).activeId).toBe('a')
  })

  it('keeps the last message above the viewport active while its answer is read', () => {
    expect(resolveActiveMessage(BOXES, at(600), null).activeId).toBe('b')
  })

  it('switches to a message once its top passes just under the top of the viewport', () => {
    expect(resolveActiveMessage(BOXES, at(800 - 2 * MESSAGE_SCROLL_MARGIN - 1), null).activeId).toBe('b')
    expect(resolveActiveMessage(BOXES, at(800 - MESSAGE_SCROLL_MARGIN), null).activeId).toBe('c')
  })

  it('makes the last message on screen active at the very end, even when it cannot reach the top', () => {
    // Scrolled to the end of a 900px conversation: b (400) and c (800) are both on screen.
    expect(resolveActiveMessage(BOXES, at(400, 900), null).activeId).toBe('c')
  })

  it('keeps a pinned message active while the scroll is on its way to it', () => {
    const result = resolveActiveMessage(BOXES, at(1200), { id: 'a', seen: false })
    expect(result).toEqual({ activeId: 'a', pin: { id: 'a', seen: false } })
  })

  it('keeps a pinned message active while it is on screen, and marks it seen', () => {
    // At the end, b was jumped to: c is on screen too but b stays active.
    const result = resolveActiveMessage(BOXES, at(400, 900), { id: 'b', seen: false })
    expect(result).toEqual({ activeId: 'b', pin: { id: 'b', seen: true } })
  })

  it('drops a pin once its message has been seen and scrolled out of view', () => {
    const result = resolveActiveMessage(BOXES, at(1200), { id: 'a', seen: true })
    expect(result).toEqual({ activeId: 'c', pin: null })
  })

  it('drops a pin whose message is gone', () => {
    expect(resolveActiveMessage(BOXES, at(0), { id: 'gone', seen: false })).toEqual({ activeId: 'a', pin: null })
  })
})
