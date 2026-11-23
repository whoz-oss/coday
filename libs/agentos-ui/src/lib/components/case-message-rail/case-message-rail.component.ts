import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  NgZone,
  output,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core'
import { MessageBox, resolveActiveMessage } from './active-message'
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
 * The reading position is measured on scroll and when the messages change. A layout change that neither scrolls nor
 * changes the messages (a tool group expanded, the window resized) can leave the filled dot stale until the next
 * scroll: accepted, since the next scroll corrects it.
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
  private readonly zone = inject(NgZone)
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

  /** The message last picked on the rail: see `resolveActiveMessage`. Forgotten when the messages change. */
  private pinnedId: string | null = null
  private frame: number | null = null

  constructor() {
    // Follow the reading position: one measure per frame at most, only while the rail is shown. Scroll events and
    // frames stay outside Angular; setting the active id still schedules a render.
    effect((onCleanup) => {
      const container = this.scrollContainer()
      if (!container || !this.visible()) return
      const onScroll = (): void => this.scheduleMeasure()
      this.zone.runOutsideAngular(() => container.addEventListener('scroll', onScroll, { passive: true }))
      onCleanup(() => {
        container.removeEventListener('scroll', onScroll)
        if (this.frame !== null) cancelAnimationFrame(this.frame)
        this.frame = null
      })
    })

    // Messages added or removed move the others: measure again once they are rendered.
    afterRenderEffect({
      read: () => {
        this.messages()
        this.scrollContainer()
        this.pinnedId = null
        this.measure()
      },
    })

    // Keep the active dot in sight when the rail scrolls: measure first, then scroll the list if needed.
    afterRenderEffect({
      earlyRead: () => {
        const index = this.entries().findIndex((entry) => entry.id === this.activeId())
        const dot = this.dots()[index]?.nativeElement
        const list = this.list()?.nativeElement
        const top = dot && list ? revealScrollTop(list, dot) : null
        return list && top !== null ? { list, top } : null
      },
      write: (reveal) => {
        const target = reveal()
        if (target) target.list.scrollTop = target.top
      },
    })
  }

  protected select(id: string): void {
    this.pinnedId = id
    this.messageSelected.emit(id)
    // At the end a message already on screen does not scroll, so no scroll event re-measures.
    this.scheduleMeasure()
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
    const top = revealScrollTop(list, dot)
    if (top !== null) list.scrollTop = top
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

  /** The list scrolled from under the tooltip: it no longer points at its dot. */
  protected onListScroll(): void {
    this.hoverTooltip.set(null)
    this.focusTooltip.set(null)
  }

  private dotCentre(dot: HTMLElement): number {
    const rect = dot.getBoundingClientRect()
    return rect.top + rect.height / 2 - this.host.nativeElement.getBoundingClientRect().top
  }

  private scheduleMeasure(): void {
    if (this.frame !== null) return
    this.frame = this.zone.runOutsideAngular(() =>
      requestAnimationFrame(() => {
        this.frame = null
        this.measure()
      })
    )
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
    const viewport = {
      scrollTop: container.scrollTop,
      clientHeight: container.clientHeight,
      scrollHeight: container.scrollHeight,
    }
    this.activeId.set(resolveActiveMessage(boxes, viewport, this.pinnedId))
  }
}

/**
 * The scroll position of `list` that just shows `item`, or null when it is already in view (layout reads only).
 * `item` must be laid out against the list, which is positioned.
 */
function revealScrollTop(list: HTMLElement, item: HTMLElement): number | null {
  const top = item.offsetTop
  const bottom = top + item.offsetHeight
  if (top < list.scrollTop) return top
  if (bottom > list.scrollTop + list.clientHeight) return bottom - list.clientHeight
  return null
}

function isFocusVisible(element: HTMLElement): boolean {
  try {
    return element.matches(':focus-visible')
  } catch {
    // A selector engine without :focus-visible: show the tooltip rather than hide it from keyboard users.
    return true
  }
}
