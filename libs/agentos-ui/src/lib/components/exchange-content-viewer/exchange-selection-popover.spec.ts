import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { Subject } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { ExchangeSelectionRequest } from '../../services/exchange-selection-request.service'
import { ExchangeFileRef } from '../../services/exchange-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { ExchangeContentViewerComponent } from './exchange-content-viewer.component'

jest.mock('../../services/exchange-markdown-convert', () => ({
  ...jest.requireActual('../../services/exchange-markdown-convert'),
  htmlToMarkdown: jest.fn(),
}))

interface Box {
  left: number
  top: number
  right: number
  bottom: number
}

const toDomRect = (box: Box) => ({
  ...box,
  x: box.left,
  y: box.top,
  width: box.right - box.left,
  height: box.bottom - box.top,
})

/** The popover under, above and hidden from the selection, positioned from mocked rects (jsdom has no layout). */
describe('ExchangeContentViewerComponent selection popover', () => {
  let fixture: ComponentFixture<ExchangeContentViewerComponent>
  let frames: FrameRequestCallback[]
  // Where each element reports to be: the viewer body, the highlighted passage (every line), the iframe.
  let bodyBox: Box
  let markLines: Box[]
  let frameBox: Box

  const ref = (path: string): ExchangeFileRef => ({ scope: ExchangeFileEntryScopeEnum.CASE, path })
  const el = (): HTMLElement => fixture.nativeElement
  const md = (): HTMLElement => el().querySelector('.exchange-content-viewer__markdown') as HTMLElement
  const textarea = (): HTMLTextAreaElement => el().querySelector('textarea.exchange-content-viewer__editor')!
  const iframe = (): HTMLIFrameElement => el().querySelector('iframe')!
  const popover = (): HTMLElement | null => el().querySelector('.exchange-content-viewer__popover')
  const composerInput = (): HTMLTextAreaElement | null => el().querySelector('.selection-composer__input')
  const body = (): HTMLElement => el().querySelector('.exchange-content-viewer__body') as HTMLElement

  function create(path = 'notes/todo.md', content = 'one two three', canEdit = true): void {
    fixture = TestBed.createComponent(ExchangeContentViewerComponent)
    fixture.componentRef.setInput('file', ref(path))
    fixture.componentRef.setInput('content', content)
    fixture.componentRef.setInput('status', 'ready')
    fixture.componentRef.setInput('canEdit', canEdit)
    fixture.componentRef.setInput('namespaceId', 'ns-1')
    fixture.detectChanges()
  }

  /** Lets the effects and the render callbacks run (the popover is placed after the next render). */
  function settle(): void {
    fixture.detectChanges()
    TestBed.flushEffects?.()
    fixture.detectChanges()
  }

  /** Runs the animation frames the component asked for. */
  function flushFrames(): void {
    const pending = frames
    frames = []
    pending.forEach((callback) => callback(0))
    fixture.detectChanges()
  }

  function selectInMarkdown(text: string, release = true): void {
    const walker = document.createTreeWalker(md(), NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const at = (node.nodeValue ?? '').indexOf(text)
      if (at < 0) continue
      const range = document.createRange()
      range.setStart(node, at)
      range.setEnd(node, at + text.length)
      const selection = window.getSelection()!
      selection.removeAllRanges()
      selection.addRange(range)
      document.dispatchEvent(new Event('selectionchange'))
      if (release) md().dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
      settle()
      return
    }
    throw new Error(`"${text}" not found`)
  }

  const post = (data: unknown, source: MessageEventSource | null = iframe().contentWindow): void => {
    window.dispatchEvent(new MessageEvent('message', { data, source }))
    settle()
  }

  const press = (key: string, target: EventTarget = document.body, init: KeyboardEventInit = {}): KeyboardEvent => {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init })
    target.dispatchEvent(event)
    settle()
    return event
  }

  beforeEach(() => {
    frames = []
    bodyBox = { left: 0, top: 0, right: 900, bottom: 400 }
    markLines = [{ left: 150, top: 100, right: 200, bottom: 120 }]
    frameBox = { left: 50, top: 30, right: 850, bottom: 380 }
    jest.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      frames.push(callback)
      return frames.length
    })
    jest.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
    jest.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains('exchange-content-viewer__body')) return toDomRect(bodyBox)
      if (this.tagName === 'IFRAME') return toDomRect(frameBox)
      if (this.tagName === 'MARK') return toDomRect(markLines[markLines.length - 1]!)
      return toDomRect({ left: 0, top: 0, right: 0, bottom: 0 })
    })
    jest.spyOn(Element.prototype, 'getClientRects').mockImplementation(function (this: Element) {
      return (this.tagName === 'MARK' ? markLines.map(toDomRect) : []) as unknown as DOMRectList
    })
    TestBed.configureTestingModule({
      imports: [ExchangeContentViewerComponent],
      providers: [
        { provide: USER_PREFERENCES_PORT, useValue: { shouldSend: (e: KeyboardEvent) => e.key === 'Enter' } },
        { provide: PromptStateService, useValue: { listEffective: jest.fn() } },
        { provide: AgentStateService, useValue: { listEffective: jest.fn().mockReturnValue(new Subject()) } },
      ],
    })
  })

  afterEach(() => {
    window.getSelection()?.removeAllRanges()
    jest.restoreAllMocks()
  })

  describe('opening', () => {
    it('does not open while the selection is still being dragged, only once it is settled', () => {
      create()
      selectInMarkdown('two', false)
      expect(popover()).toBeNull()
      md().dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
      settle()
      expect(popover()).not.toBeNull()
      expect(composerInput()).not.toBeNull()
    })

    it('opens after a keyboard selection, once the keys stop', () => {
      jest.useFakeTimers()
      try {
        create()
        selectInMarkdown('two', false)
        md().dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowRight', bubbles: true }))
        md().dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowRight', bubbles: true }))
        settle()
        expect(popover()).toBeNull()
        jest.advanceTimersByTime(400)
        settle()
        expect(composerInput()).not.toBeNull()
      } finally {
        jest.useRealTimers()
      }
    })

    it('does not take the focus when it opens', () => {
      create()
      selectInMarkdown('two')
      expect(composerInput()).not.toBeNull()
      expect(document.activeElement).not.toBe(composerInput())
    })

    it('has no quote chip, while the highlight is applied', () => {
      create()
      selectInMarkdown('two')
      expect(el().querySelector('.exchange-content-viewer__quote')).toBeNull()
      expect(el().querySelector('.exchange-content-viewer__dock')).toBeNull()
      expect(popover()!.textContent).not.toContain('two')
      expect(md().querySelector('mark.agentos-selection')?.textContent).toBe('two')
    })
  })

  describe('position', () => {
    it('goes under the last line of the selection, left-aligned with its start', () => {
      markLines = [
        { left: 150, top: 100, right: 300, bottom: 120 },
        { left: 20, top: 120, right: 90, bottom: 140 },
      ]
      create()
      selectInMarkdown('two')
      const style = popover()!.style
      expect(style.top).toBe('150px') // bottom of the last line + the gap
      expect(style.left).toBe('150px') // start of the first line
      expect(style.width).toBe('520px')
      expect(popover()!.getAttribute('data-placement')).toBe('below')
      expect(popover()!.classList.contains('exchange-content-viewer__popover--hidden')).toBe(false)
    })

    it('is clamped horizontally inside the body, and narrower in a narrow body', () => {
      bodyBox = { left: 0, top: 0, right: 300, bottom: 400 }
      markLines = [{ left: 280, top: 100, right: 295, bottom: 120 }]
      create()
      selectInMarkdown('two')
      expect(popover()!.style.width).toBe('284px')
      expect(popover()!.style.left).toBe('8px')
    })

    it('flips above the selection near the bottom of the body', () => {
      markLines = [{ left: 150, top: 340, right: 200, bottom: 360 }]
      create()
      selectInMarkdown('two')
      expect(popover()!.getAttribute('data-placement')).toBe('above')
      expect(popover()!.classList.contains('exchange-content-viewer__popover--above')).toBe(true)
      expect(parseFloat(popover()!.style.top)).toBeLessThan(340)
    })

    it('hides when the anchor scrolls out of view, and comes back with it', () => {
      create()
      selectInMarkdown('two')
      const hidden = (): boolean => popover()!.classList.contains('exchange-content-viewer__popover--hidden')
      expect(hidden()).toBe(false)

      markLines = [{ left: 150, top: -100, right: 200, bottom: -80 }]
      body().dispatchEvent(new Event('scroll'))
      flushFrames()
      expect(hidden()).toBe(true)

      markLines = [{ left: 150, top: 100, right: 200, bottom: 120 }]
      body().dispatchEvent(new Event('scroll'))
      flushFrames()
      expect(hidden()).toBe(false)
    })

    it('moves with the content when the body scrolls, and is throttled by animation frames', () => {
      create()
      selectInMarkdown('two')
      expect(popover()!.style.top).toBe('130px')
      frames = []

      markLines = [{ left: 150, top: 60, right: 200, bottom: 80 }]
      body().scrollTop = 40
      body().dispatchEvent(new Event('scroll'))
      body().dispatchEvent(new Event('scroll'))
      body().dispatchEvent(new Event('scroll'))
      expect(frames).toHaveLength(1)
      flushFrames()
      // 80 + gap, relative to the body, plus the scroll offset: it follows the content, it is not fixed on screen.
      expect(popover()!.style.top).toBe('130px')
    })

    it('is placed again when the window is resized', () => {
      create()
      selectInMarkdown('two')
      bodyBox = { left: 0, top: 0, right: 300, bottom: 400 }
      window.dispatchEvent(new Event('resize'))
      flushFrames()
      expect(popover()!.style.width).toBe('284px')
    })

    it('is placed again when the body is resized (ResizeObserver)', () => {
      const observers: (() => void)[] = []
      class FakeResizeObserver {
        constructor(callback: () => void) {
          observers.push(callback)
        }
        observe(): void {
          /* nothing to watch in a test */
        }
        disconnect(): void {
          /* nothing to release */
        }
      }
      const globals = globalThis as { ResizeObserver?: unknown }
      globals.ResizeObserver = FakeResizeObserver
      try {
        create()
        selectInMarkdown('two')
        expect(observers.length).toBeGreaterThan(0)
        bodyBox = { left: 0, top: 0, right: 500, bottom: 400 }
        observers.forEach((callback) => callback())
        flushFrames()
        expect(popover()!.style.width).toBe('484px')
        expect(popover()!.style.left).toBe('8px')
      } finally {
        delete globals.ResizeObserver
      }
    })

    it('is anchored at the end of the selection of a textarea, through its mirror layer', () => {
      markLines = [{ left: 60, top: 200, right: 120, bottom: 220 }]
      create('a.txt', 'one two three')
      textarea().setSelectionRange(4, 7)
      textarea().dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
      settle()
      expect(el().querySelector('.exchange-content-viewer__mirror mark')?.textContent).toBe('two')
      expect(popover()!.style.top).toBe('230px')
      expect(popover()!.style.left).toBe('60px')
    })
  })

  describe('keyboard and clicks', () => {
    it('closes on Escape, and clears the selection', () => {
      create()
      selectInMarkdown('two')
      press('Escape')
      expect(popover()).toBeNull()
      expect(window.getSelection()!.toString()).toBe('')
      expect(md().querySelector('mark.agentos-selection')).toBeNull()
    })

    it('moves focus into the composer, with the key, when a printable key is pressed and nothing is focused', () => {
      create('notes/todo.md', 'one two three', false)
      selectInMarkdown('two')
      const event = press('h')
      expect(event.defaultPrevented).toBe(true)
      expect(composerInput()!.value).toBe('h')
      expect(document.activeElement).toBe(composerInput())
    })

    it.each([
      ['a modified key', { key: 'c', init: { ctrlKey: true } }],
      ['a non printable key', { key: 'ArrowDown', init: {} }],
    ])('leaves %s alone', (_label, { key, init }) => {
      create()
      selectInMarkdown('two')
      const event = press(key, document.body, init)
      expect(event.defaultPrevented).toBe(false)
      expect(composerInput()!.value).toBe('')
    })

    it('closes on a click elsewhere when the instruction is empty, clearing the selection', () => {
      create()
      selectInMarkdown('two')
      document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      settle()
      expect(popover()).toBeNull()
      expect(md().querySelector('mark.agentos-selection')).toBeNull()
    })

    it('stays open on a click elsewhere when an instruction is written', () => {
      create()
      selectInMarkdown('two')
      const input = composerInput()!
      input.value = 'rewrite'
      input.dispatchEvent(new Event('input'))
      settle()
      document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      settle()
      expect(composerInput()).not.toBeNull()
      expect(composerInput()!.value).toBe('rewrite')
    })

    it('stays open on a click inside the popover', () => {
      create()
      selectInMarkdown('two')
      popover()!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      settle()
      expect(composerInput()).not.toBeNull()
    })
  })

  describe('waiting and proposal, at the same anchor', () => {
    const request = (overrides: Partial<ExchangeSelectionRequest> = {}): ExchangeSelectionRequest => ({
      id: 'r1',
      ref: ref('notes/todo.md'),
      from: 4,
      to: 7,
      selection: 'two',
      instruction: '@Writer fix it',
      status: 'waiting',
      ...overrides,
    })

    function send(): void {
      const input = composerInput()!
      input.value = '@Writer fix it'
      input.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
      settle()
    }

    it('shows a slim waiting line at the anchor: spinner, agent, dismiss', () => {
      create()
      selectInMarkdown('two')
      const composerTop = popover()!.style.top
      const composerLeft = popover()!.style.left
      send()
      fixture.componentRef.setInput('request', request())
      settle()
      const line = popover()!.querySelector('.exchange-content-viewer__waiting')!
      expect(line.textContent).toContain('Waiting for @Writer')
      expect(line.querySelector('ds-spinner')).not.toBeNull()
      expect(line.querySelector('ds-icon-button[title="Dismiss the selection"]')).not.toBeNull()
      expect(composerInput()).toBeNull()
      expect(popover()!.style.top).toBe(composerTop)
      expect(popover()!.style.left).toBe(composerLeft)
      expect(md().querySelector('mark.agentos-selection')?.textContent).toBe('two')
    })

    it('opens the annotation card at the same anchor, flipped like the composer', () => {
      markLines = [{ left: 150, top: 340, right: 200, bottom: 360 }]
      create()
      selectInMarkdown('two')
      send()
      fixture.componentRef.setInput('request', request())
      settle()
      fixture.componentRef.setInput('request', request({ status: 'proposed', proposal: 'TWO', answer: 'Clearer.' }))
      settle()
      expect(popover()!.querySelector('.exchange-content-viewer__card')!.textContent).toContain('Clearer.')
      expect(popover()!.getAttribute('data-placement')).toBe('above')
      expect(popover()!.style.left).toBe('150px')
    })

    it('reopens the composer at the anchor on Ask again', () => {
      create()
      selectInMarkdown('two')
      send()
      fixture.componentRef.setInput('request', request({ status: 'proposed', proposal: 'TWO', answer: 'Clearer.' }))
      settle()
      const ask = Array.from(popover()!.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Ask again')!
      ask.click()
      fixture.componentRef.setInput('request', null)
      settle()
      expect(composerInput()!.value).toBe('@Writer fix it')
      expect(popover()!.style.left).toBe('150px')
    })
  })

  describe('html preview', () => {
    const rect = { left: 10, top: 20, right: 60, bottom: 40 }

    it('is placed from the rect of the bridge, translated by the position of the iframe', () => {
      create('page.html', '<p>hello world</p>', false)
      post({ type: 'agentos-exchange-selection', text: 'hello', rect })
      expect(composerInput()).not.toBeNull()
      // 40 + 30 (frame top) + gap 10 ; left 10 + 50 (frame left)
      expect(popover()!.style.top).toBe('80px')
      expect(popover()!.style.left).toBe('60px')
    })

    it('follows the anchor the bridge posts on scroll, and hides when it leaves the frame', () => {
      create('page.html', '<p>hello world</p>', false)
      post({ type: 'agentos-exchange-selection', text: 'hello', rect })
      post({ type: 'agentos-exchange-anchor', rect: { left: 10, top: 120, right: 60, bottom: 140 } })
      expect(popover()!.style.top).toBe('180px')
      post({ type: 'agentos-exchange-anchor', rect: { left: 10, top: -300, right: 60, bottom: -280 } })
      expect(popover()!.classList.contains('exchange-content-viewer__popover--hidden')).toBe(true)
    })

    it('accepts the selection and its rect from the window of the iframe only', () => {
      create('page.html', '<p>hello world</p>', false)
      post({ type: 'agentos-exchange-selection', text: 'hello', rect }, window)
      expect(popover()).toBeNull()
      post({ type: 'agentos-exchange-anchor', rect }, window)
      post({ type: 'agentos-exchange-selection', text: 'hello', rect })
      expect(popover()).not.toBeNull()
    })

    it('ignores a rect that is not made of numbers', () => {
      create('page.html', '<p>hello world</p>', false)
      post({ type: 'agentos-exchange-selection', text: 'hello', rect: { left: 'a', top: 1, right: 2, bottom: 3 } })
      expect(popover()).not.toBeNull()
      // No anchor: it waits at the top of the body instead of at NaN.
      expect(popover()!.style.top).toBe('8px')
    })

    it('keeps the sandbox without same-origin', () => {
      create('page.html', '<p>hello world</p>', false)
      expect(iframe().getAttribute('sandbox')).toBe('allow-scripts allow-popups allow-popups-to-escape-sandbox')
    })
  })
})
