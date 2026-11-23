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
    expect(resolveActiveMessage([], at(0), null)).toBeNull()
  })

  it('makes the first message active at the top of the conversation', () => {
    expect(resolveActiveMessage(BOXES, at(0), null)).toBe('a')
  })

  it('keeps the last message above the viewport active while its answer is read', () => {
    expect(resolveActiveMessage(BOXES, at(600), null)).toBe('b')
  })

  it('switches to a message once its top passes just under the top of the viewport', () => {
    expect(resolveActiveMessage(BOXES, at(800 - 2 * MESSAGE_SCROLL_MARGIN - 1), null)).toBe('b')
    expect(resolveActiveMessage(BOXES, at(800 - MESSAGE_SCROLL_MARGIN), null)).toBe('c')
  })

  it('makes the last message on screen active at the very end, even when it cannot reach the top', () => {
    // Scrolled to the end of a 900px conversation: b (400) and c (800) are both on screen.
    expect(resolveActiveMessage(BOXES, at(400, 900), null)).toBe('c')
  })

  it('makes the pinned message active at the very end while it is on screen', () => {
    // b was picked on the rail: c is on screen too, but b is the one the reader asked for.
    expect(resolveActiveMessage(BOXES, at(400, 900), 'b')).toBe('b')
  })

  it('ignores the pin at the very end once its message is off screen', () => {
    expect(resolveActiveMessage(BOXES, at(400, 900), 'a')).toBe('c')
  })

  it('ignores the pin away from the end: the reading line decides', () => {
    expect(resolveActiveMessage(BOXES, at(600), 'c')).toBe('b')
  })
})
