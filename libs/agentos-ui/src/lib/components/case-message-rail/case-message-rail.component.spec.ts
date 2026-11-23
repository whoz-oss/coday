import { ComponentFixture, TestBed } from '@angular/core/testing'
import { CaseMessageRailComponent, RailMessage } from './case-message-rail.component'
import { MESSAGE_ANCHOR_ATTRIBUTE } from './message-anchor'
import { RAIL_PREVIEW_LENGTH } from './message-preview'

const LONG = 'Please refactor the authentication module so that it uses the new token service everywhere'

const MESSAGES: RailMessage[] = [
  { id: 'm-1', text: 'First **question**' },
  { id: 'm-2', text: LONG },
  { id: 'm-3', text: 'Third one' },
]

/**
 * A scroll container laid out by hand (jsdom has no layout): message i is 100px tall at i × 400px, seen through a
 * 500px viewport of a `scrollHeight` conversation. `scrollTo` moves the reading position.
 */
function fakeConversation(
  messages: readonly RailMessage[],
  scrollHeight = 2000
): {
  container: HTMLElement
  scrollTo: (top: number) => void
} {
  let scrollTop = 0
  const container = document.createElement('div')
  Object.defineProperties(container, {
    scrollTop: { get: () => scrollTop },
    clientHeight: { value: 500 },
    scrollHeight: { value: scrollHeight },
  })
  container.getBoundingClientRect = () => new DOMRect(0, 0, 600, 500)
  messages.forEach((message, index) => {
    const anchor = document.createElement('div')
    anchor.setAttribute(MESSAGE_ANCHOR_ATTRIBUTE, message.id)
    Object.defineProperty(anchor, 'offsetHeight', { value: 100 })
    anchor.getBoundingClientRect = () => new DOMRect(0, index * 400 - scrollTop, 600, 100)
    container.appendChild(anchor)
  })
  document.body.appendChild(container)
  return {
    container,
    scrollTo: (top: number) => {
      scrollTop = top
      container.dispatchEvent(new Event('scroll'))
    },
  }
}

describe('CaseMessageRailComponent', () => {
  let fixture: ComponentFixture<CaseMessageRailComponent>
  let selected: string[]
  let conversation: ReturnType<typeof fakeConversation>

  function render(messages: readonly RailMessage[] = MESSAGES, scrollHeight?: number): HTMLElement {
    conversation = fakeConversation(messages, scrollHeight)
    fixture = TestBed.createComponent(CaseMessageRailComponent)
    fixture.componentRef.setInput('messages', messages)
    fixture.componentRef.setInput('scrollContainer', conversation.container)
    selected = []
    fixture.componentInstance.messageSelected.subscribe((id) => selected.push(id))
    fixture.detectChanges()
    // The first measure runs after the first render and sets the active dot: render that.
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  const dots = (host: HTMLElement): HTMLButtonElement[] => Array.from(host.querySelectorAll('button'))
  const tooltip = (host: HTMLElement): HTMLElement | null => host.querySelector('.message-rail__tooltip')
  const current = (host: HTMLElement): (string | null)[] => dots(host).map((dot) => dot.getAttribute('aria-current'))

  /** Scroll the conversation, let the rail measure on the next frame, then render. */
  async function scrollConversation(top: number): Promise<void> {
    conversation.scrollTo(top)
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    fixture.detectChanges()
  }

  afterEach(() => {
    fixture?.destroy()
    conversation?.container.remove()
    jest.restoreAllMocks()
    TestBed.resetTestingModule()
  })

  it('renders a navigation landmark with one dot button per user message, in order', () => {
    const host = render()

    expect(host.querySelector('nav')?.getAttribute('aria-label')).toBe('User messages')
    expect(host.querySelectorAll('nav li')).toHaveLength(3)
    // Native buttons: Enter and Space activate them like a click.
    expect(dots(host).map((dot) => dot.type)).toEqual(['button', 'button', 'button'])
    expect(dots(host).map((dot) => dot.getAttribute('aria-label'))).toEqual(['First question', LONG, 'Third one'])
  })

  it('renders nothing under two messages', () => {
    const host = render([MESSAGES[0]!])
    expect(host.querySelector('nav')).toBeNull()
  })

  it('names a dot after its message, up to 120 characters, and falls back to its position when it has no text', () => {
    const host = render([
      { id: 'm-1', text: 'x'.repeat(200) },
      { id: 'm-2', text: '   ' },
    ])
    const [long, empty] = dots(host).map((dot) => dot.getAttribute('aria-label'))

    expect(Array.from(long!)).toHaveLength(120)
    expect(long!.endsWith('…')).toBe(true)
    expect(empty).toBe('Message 2')
  })

  it('shows the plain-text preview of a dot at once on hover, and hides it on leave', () => {
    const host = render()
    expect(tooltip(host)).toBeNull()

    dots(host)[1]!.dispatchEvent(new Event('pointerenter'))
    fixture.detectChanges()
    expect(tooltip(host)?.textContent).toBe('Please refactor the authentication modu…')
    expect(Array.from(tooltip(host)!.textContent!)).toHaveLength(RAIL_PREVIEW_LENGTH)
    expect(tooltip(host)?.getAttribute('aria-hidden')).toBe('true')

    dots(host)[1]!.dispatchEvent(new Event('pointerleave'))
    fixture.detectChanges()
    expect(tooltip(host)).toBeNull()
  })

  it('shows the preview of the focused dot', () => {
    const host = render()

    dots(host)[0]!.focus()
    fixture.detectChanges()
    expect(tooltip(host)?.textContent).toBe('First question')

    dots(host)[0]!.blur()
    fixture.detectChanges()
    expect(tooltip(host)).toBeNull()
  })

  it('hides the preview when the list scrolls from under it', () => {
    const host = render()
    dots(host)[0]!.focus()
    dots(host)[1]!.dispatchEvent(new Event('pointerenter'))
    fixture.detectChanges()
    expect(tooltip(host)).not.toBeNull()

    host.querySelector('ol')!.dispatchEvent(new Event('scroll'))
    fixture.detectChanges()
    expect(tooltip(host)).toBeNull()
  })

  it('emits the id of a clicked dot, for the host to scroll to it', () => {
    const host = render()

    dots(host)[2]!.click()

    expect(selected).toEqual(['m-3'])
  })

  it('at the very end, keeps the clicked dot current over a later message on screen, until the messages change', async () => {
    // A 900px conversation scrolled to its end: m-2 (400) and m-3 (800) are both on screen, neither can reach the top.
    const host = render(MESSAGES, 900)
    await scrollConversation(400)
    expect(current(host)).toEqual([null, null, 'location'])

    // Nothing scrolls (the message is already on screen at the end): no scroll event, only the next frame.
    dots(host)[1]!.click()
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    fixture.detectChanges()
    expect(current(host)).toEqual([null, 'location', null])

    // A new message (same layout here) clears the pick: the usual rule applies again.
    fixture.componentRef.setInput('messages', [...MESSAGES])
    fixture.detectChanges()
    fixture.detectChanges()
    expect(current(host)).toEqual([null, null, 'location'])
  })

  it('marks the message being read as current, and follows the scroll', async () => {
    const host = render()
    expect(current(host)).toEqual(['location', null, null])

    await scrollConversation(600)
    expect(current(host)).toEqual([null, 'location', null])

    await scrollConversation(790)
    expect(current(host)).toEqual([null, null, 'location'])
  })

  it('stops following the scroll once destroyed', () => {
    render()
    const container = conversation.container
    const removed = jest.spyOn(container, 'removeEventListener')

    fixture.destroy()

    expect(removed.mock.calls.map(([type]) => type)).toEqual(['scroll'])
  })

  it('moves focus between dots with ArrowUp / ArrowDown, Home and End, without wrapping', () => {
    const host = render()
    const [first, second, third] = dots(host)
    const press = (dot: HTMLButtonElement, key: string): KeyboardEvent => {
      const event = new KeyboardEvent('keydown', { key, cancelable: true })
      dot.dispatchEvent(event)
      return event
    }

    first!.focus()
    expect(press(first!, 'ArrowDown').defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(second)
    press(second!, 'ArrowDown')
    expect(document.activeElement).toBe(third)
    press(third!, 'ArrowDown')
    expect(document.activeElement).toBe(third)
    press(third!, 'ArrowUp')
    expect(document.activeElement).toBe(second)
    press(second!, 'Home')
    expect(document.activeElement).toBe(first)
    press(first!, 'End')
    expect(document.activeElement).toBe(third)
    expect(press(third!, 'a').defaultPrevented).toBe(false)
  })

  it('leaves a single dot in the Tab order: the focused one, else the current one', async () => {
    const host = render()
    const tabStops = (): number[] => dots(host).map((dot) => dot.tabIndex)
    expect(tabStops()).toEqual([0, -1, -1])

    dots(host)[1]!.focus()
    fixture.detectChanges()
    expect(tabStops()).toEqual([-1, 0, -1])

    dots(host)[1]!.blur()
    await scrollConversation(790)
    expect(tabStops()).toEqual([-1, -1, 0])
  })
})
