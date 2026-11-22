import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core'
import { MessageBox, RailPin, resolveActiveMessage } from './active-message'
import { contentTop, messageAnchors } from './message-anchor'
import { messagePreview, RAIL_LABEL_LENGTH, RAIL_PREVIEW_LENGTH } from './message-preview'

/** A message the rail lists: its id (also its anchor value in the host) and its raw text. */
export interface RailMessage {
  id: string
  text: string
}

interface RailEntry {
  id: string
  /** Tooltip text: the first {@link RAIL_PREVIEW_LENGTH} characters. */
  preview: string
  /** Accessible name of the dot: the first {@link RAIL_LABEL_LENGTH} characters. */
  label: string
}

interface RailTooltip {
  text: string
  /** Centre of the dot, from the top of the rail (px). */
  top: number
}

/**
 * CaseMessageRailComponent — a column of dots beside a conversation, one per message the user sent.
 *
 * Usage:
 *   <agentos-case-message-rail [messages]="userMessages()" [scrollContainer]="scroller"
 *                              (messageSelected)="scrollToMessage($event)" />
 *
 * The rail follows the reading position of `scrollContainer` (the dot of the message being read is filled) and asks
 * the host to scroll when a dot is chosen: the host owns its scroll position and its auto-follow. Each message is
 * found in the container through its `[data-message-id]` anchor (see MESSAGE_ANCHOR_ATTRIBUTE).
 *
 * It renders nothing under two messages. It sits just outside the right edge of its positioned parent (in the
 * gutter), stays put while the conversation scrolls, and scrolls itself when its dots no longer fit.
 */
@Component({
  selector: 'agentos-case-message-rail',
  templateUrl: './case-message-rail.component.html',
  styleUrl: './case-message-rail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseMessageRailComponent {
  /** The user's messages, in conversation order. */
  readonly messages = input.required<readonly RailMessage[]>()

  /** The element that scrolls the conversation and holds the anchored messages. */
  readonly scrollContainer = input<HTMLElement | undefined>(undefined)

  /** Emits the id of the message the user chose, for the host to scroll to it. */
  readonly messageSelected = output<string>()

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef)
  private readonly list = viewChild<ElementRef<HTMLOListElement>>('list')
  private readonly dots = viewChildren<ElementRef<HTMLButtonElement>>('dot')

  protected readonly entries = computed<RailEntry[]>(() =>
    this.messages().map((message, index) => {
      const fallback = `Message ${index + 1}`
      return {
        id: message.id,
        preview: messagePreview(message.text, RAIL_PREVIEW_LENGTH) || fallback,
        label: messagePreview(message.text, RAIL_LABEL_LENGTH) || fallback,
      }
    })
  )

  protected readonly visible = computed(() => this.entries().length > 1)

  /** Id of the message being read; null until the first measure. */
  protected readonly activeId = signal<string | null>(null)

  /** Id of the dot holding keyboard focus. */
  private readonly focusedId = signal<string | null>(null)

  /** The one dot reached by Tab (roving tabindex): the focused one, else the active one, else the latest. */
  protected readonly tabStopId = computed(
    () => this.focusedId() ?? this.activeId() ?? this.entries().at(-1)?.id ?? null
  )

  private readonly hoverTooltip = signal<RailTooltip | null>(null)
  private readonly focusTooltip = signal<RailTooltip | null>(null)

  /** One tooltip at a time, the pointer first: it is outside the scrolling list, so the list never clips it. */
  protected readonly tooltip = computed(() => this.hoverTooltip() ?? this.focusTooltip())

  private pin: RailPin | null = null
  private frame: number | null = null

  constructor() {
    // Follow the reading position: one measure per frame at most, only while the rail is shown.
    effect((onCleanup) => {
      const container = this.scrollContainer()
      if (!container || !this.visible()) return
      const onScroll = (): void => this.scheduleMeasure()
      // A wheel or a swipe means the reader took over: a jump in progress no longer decides the active dot.
      const onTakeOver = (): void => {
        this.pin = null
      }
      container.addEventListener('scroll', onScroll, { passive: true })
      container.addEventListener('wheel', onTakeOver, { passive: true })
      container.addEventListener('touchstart', onTakeOver, { passive: true })
      onCleanup(() => {
        container.removeEventListener('scroll', onScroll)
        container.removeEventListener('wheel', onTakeOver)
        container.removeEventListener('touchstart', onTakeOver)
        if (this.frame !== null) cancelAnimationFrame(this.frame)
        this.frame = null
      })
    })

    // Messages added or removed move the others: measure again once they are rendered.
    afterRenderEffect({
      read: () => {
        this.messages()
        this.scrollContainer()
        this.measure()
      },
    })

    // Keep the active dot in sight when the rail scrolls.
    afterRenderEffect({
      write: () => {
        const index = this.entries().findIndex((entry) => entry.id === this.activeId())
        const dot = this.dots()[index]?.nativeElement
        const list = this.list()?.nativeElement
        if (dot && list) revealInList(list, dot)
      },
    })
  }

  protected select(id: string): void {
    this.pin = { id, seen: false }
    this.activeId.set(id)
    this.messageSelected.emit(id)
  }

  protected onKeydown(event: KeyboardEvent, index: number): void {
    const last = this.entries().length - 1
    const targets: Partial<Record<string, number>> = { ArrowUp: index - 1, ArrowDown: index + 1, Home: 0, End: last }
    const target = targets[event.key]
    if (target === undefined) return
    event.preventDefault()
    const dot = this.dots()[Math.min(last, Math.max(0, target))]?.nativeElement
    const list = this.list()?.nativeElement
    if (!dot || !list) return
    revealInList(list, dot)
    dot.focus({ preventScroll: true })
  }

  protected onPointerEnter(dot: HTMLElement, entry: RailEntry): void {
    this.hoverTooltip.set({ text: entry.preview, top: this.dotCentre(dot) })
  }

  protected onPointerLeave(): void {
    this.hoverTooltip.set(null)
  }

  protected onFocus(dot: HTMLElement, entry: RailEntry): void {
    this.focusedId.set(entry.id)
    // A mouse click focuses the dot too: only a keyboard focus keeps a tooltip once the pointer has left.
    if (isFocusVisible(dot)) this.focusTooltip.set({ text: entry.preview, top: this.dotCentre(dot) })
  }

  protected onBlur(): void {
    this.focusedId.set(null)
    this.focusTooltip.set(null)
  }

  /** The list scrolled under the tooltip: the pointer is over another dot now, the focused one has moved. */
  protected onListScroll(): void {
    this.hoverTooltip.set(null)
    const focused = this.dots().find((dot) => dot.nativeElement === document.activeElement)?.nativeElement
    const tooltip = this.focusTooltip()
    if (focused && tooltip) this.focusTooltip.set({ ...tooltip, top: this.dotCentre(focused) })
  }

  private dotCentre(dot: HTMLElement): number {
    const rect = dot.getBoundingClientRect()
    return rect.top + rect.height / 2 - this.host.nativeElement.getBoundingClientRect().top
  }

  private scheduleMeasure(): void {
    if (this.frame !== null) return
    this.frame = requestAnimationFrame(() => {
      this.frame = null
      this.measure()
    })
  }

  /** Reads where the listed messages sit (layout reads only) and updates the active dot. */
  private measure(): void {
    const container = this.scrollContainer()
    if (!container || !this.visible()) return
    const anchors = messageAnchors(container)
    const boxes: MessageBox[] = this.messages().flatMap((message) => {
      const anchor = anchors.get(message.id)
      if (!anchor) return []
      const top = contentTop(container, anchor)
      return [{ id: message.id, top, bottom: top + anchor.offsetHeight }]
    })
    const { activeId, pin } = resolveActiveMessage(
      boxes,
      { scrollTop: container.scrollTop, clientHeight: container.clientHeight, scrollHeight: container.scrollHeight },
      this.pin
    )
    this.pin = pin
    this.activeId.set(activeId)
  }
}

/** Scrolls `list` just enough to show `item`, which must be laid out against it (the list is positioned). */
function revealInList(list: HTMLElement, item: HTMLElement): void {
  const top = item.offsetTop
  const bottom = top + item.offsetHeight
  if (top < list.scrollTop) list.scrollTop = top
  else if (bottom > list.scrollTop + list.clientHeight) list.scrollTop = bottom - list.clientHeight
}

function isFocusVisible(element: HTMLElement): boolean {
  try {
    return element.matches(':focus-visible')
  } catch {
    // A selector engine without :focus-visible: show the tooltip rather than hide it from keyboard users.
    return true
  }
}
