import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { MatDialog } from '@angular/material/dialog'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { fromEvent } from 'rxjs'
import {
  BlueprintDirective,
  ConfirmDialogComponent,
  EmptyStateComponent,
  IconButtonComponent,
  SpinnerComponent,
} from '@whoz-oss/design-system'
import { detectFormat, isEditableFormat, reindentJson, selectionLineRange } from '../../services/exchange-content.utils'
import { injectCopyFeedback } from '../../services/clipboard.utils'
import { ExchangeHtmlPreviewService } from '../../services/exchange-html-preview.service'
import {
  EXCHANGE_ANCHOR_MESSAGE_TYPE,
  EXCHANGE_EDIT_MESSAGE_TYPE,
  EXCHANGE_HIGHLIGHT_MESSAGE_TYPE,
  EXCHANGE_HTML_MESSAGE_TYPE,
  EXCHANGE_READY_MESSAGE_TYPE,
  EXCHANGE_REPLACE_MESSAGE_TYPE,
  EXCHANGE_REPLACE_RESULT_MESSAGE_TYPE,
  EXCHANGE_SAVE_MESSAGE_TYPE,
  EXCHANGE_SELECTION_MESSAGE_TYPE,
  trustPdfBlobUrl,
  trustSandboxedSrcdoc,
  withSelectionBridge,
} from '../../services/exchange-html-srcdoc'
import {
  htmlStringToMarkdown,
  htmlToMarkdown,
  rangeToMarkdown,
  renderMarkdownFragment,
} from '../../services/exchange-markdown-convert'
import {
  insertLink,
  linkAtSelection,
  MarkdownAction,
  markdownActionFor,
  removeLinks,
  runMarkdownAction,
  sanitizeLinkUrl,
} from '../../services/exchange-markdown-commands'
import {
  applyBlockInputRule,
  applyCodeFenceRule,
  applyInlineInputRule,
} from '../../services/exchange-markdown-input-rules'
import { replaceRangeWithFragment } from '../../services/exchange-markdown-insert'
import { MarkdownRendererService } from '../../services/markdown-renderer.service'
import { locatePassage, rangeFromTextOffsets, rangeTextOffsets, squash } from '../../services/exchange-passage'
import {
  anchorFromRects,
  computePopoverPosition,
  POPOVER_MARGIN,
  PopoverRect,
  popoverWidth,
  POPOVER_MAX_WIDTH,
} from '../../services/exchange-popover-position'
import { ExchangeSelectionRequest } from '../../services/exchange-selection-request.service'
import {
  ExchangeFileRef,
  ExchangeSaveStatus,
  ExchangeSelectionAction,
  ExchangeSelectionContextEntry,
} from '../../services/exchange-state.service'
import { diffWords } from '../../services/exchange-word-diff'
import {
  EXCHANGE_FULL_VIEW_PANEL_CLASS,
  EXCHANGE_HTML_FULL_VIEW_DOWNLOAD,
  ExchangeHtmlFullViewComponent,
  ExchangeHtmlFullViewData,
} from '../exchange-html-full-view/exchange-html-full-view.component'
import { SelectionComposerComponent } from '../selection-composer/selection-composer.component'

/** Longest selection taken from the HTML preview, whatever the page posts. */
const MAX_PREVIEW_SELECTION = 100_000
/** Longest markup of a selection, and longest chain / attribute of its context, taken from the HTML preview. */
const MAX_SELECTION_HTML = 50_000
const MAX_CONTEXT_ENTRIES = 12
const MAX_CONTEXT_CLASS = 200
const MAX_CONTEXT_STYLE = 300
/** Largest edited document accepted from the HTML preview (the file endpoint refuses more anyway). */
const MAX_EDITED_HTML = 20 * 1024 * 1024

/** Name of the CSS Custom Highlight of the frozen selection, and class of its `<mark>` fallback. */
const HIGHLIGHT_NAME = 'agentos-selection'
const HIGHLIGHT_STYLE_ID = 'agentos-selection-highlight-style'

const PASSAGE_CHANGED = 'The passage changed, re-select it'

/** Pause after the last key of a keyboard selection before the composer opens. */
const KEY_SETTLE_MS = 300

/** Gap between the selection and the popover under (or above) it, and height assumed before it is measured. */
const POPOVER_GAP = 10
const POPOVER_ESTIMATED_HEIGHT = 56
/** Keeps the caret of the popover away from its rounded corners. */
const CARET_INSET = 16

/** Where the popover of the selection sits in the body of the viewer, and what it looks like there. */
interface PopoverState {
  top: number
  left: number
  width: number
  placement: 'below' | 'above'
  hidden: boolean
  /** Offset of the caret from the left of the popover: it points at the start of the selection. */
  caret: number
}

/** The markup of a selection posted by the bridge, when it is a string (capped). Never inserted in the DOM. */
function toSelectionHtml(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.slice(0, MAX_SELECTION_HTML) : undefined
}

/** The chain of elements posted by the bridge: only well-formed entries (strings, capped), at most a dozen. */
function toSelectionContext(value: unknown): ExchangeSelectionContextEntry[] | undefined {
  if (!Array.isArray(value)) return undefined
  const entries: ExchangeSelectionContextEntry[] = []
  for (const item of value.slice(0, MAX_CONTEXT_ENTRIES)) {
    if (!item || typeof item !== 'object') continue
    const { tag, class: cls, style } = item as Record<string, unknown>
    if (typeof tag !== 'string' || !/^[a-z][a-z0-9-]{0,30}$/i.test(tag)) continue
    entries.push({
      tag: tag.toLowerCase(),
      ...(typeof cls === 'string' && cls ? { class: cls.slice(0, MAX_CONTEXT_CLASS) } : {}),
      ...(typeof style === 'string' && style ? { style: style.slice(0, MAX_CONTEXT_STYLE) } : {}),
    })
  }
  return entries.length ? entries : undefined
}

/** Whether a proposal carries markup (a tag, a comment or a doctype), as opposed to plain text. */
function hasMarkup(proposal: string): boolean {
  return /<[a-z!/][^>]*>/i.test(proposal)
}

/**
 * Text content and sequence of tags of an HTML string, read through an inert DOMParser document (no script runs, no
 * resource loads, and nothing of it is ever inserted in the DOM of the app: only `textContent` and the tag names are
 * read).
 */
function readMarkup(html: string): { text: string; tags: string } {
  const body = new DOMParser().parseFromString(html, 'text/html').body
  return {
    text: body.textContent ?? '',
    tags: Array.from(body.querySelectorAll('*'))
      .map((element) => element.tagName.toLowerCase())
      .join(','),
  }
}

/** A rectangle posted by the bridge of the HTML preview, when it is made of finite numbers. */
function toRect(value: unknown): PopoverRect | null {
  if (!value || typeof value !== 'object') return null
  const { left, top, right, bottom } = value as Record<string, unknown>
  return [left, top, right, bottom].every((n) => typeof n === 'number' && Number.isFinite(n))
    ? ({ left, top, right, bottom } as PopoverRect)
    : null
}

/** The client rects of a Range or an element: one per line, or its bounding box when the browser gives none. */
function rectsOf(source: Pick<Range, 'getBoundingClientRect' | 'getClientRects'>): PopoverRect[] {
  const list = typeof source.getClientRects === 'function' ? Array.from(source.getClientRects()) : []
  if (list.length) return list
  const box = typeof source.getBoundingClientRect === 'function' ? source.getBoundingClientRect() : null
  return box && (box.width || box.height) ? [box] : []
}

/** How the document is edited in place: the rendered markdown, the sandboxed HTML page, or a plain textarea. */
type EditMode = 'markdown' | 'html' | 'text'

/** A non-empty selection of the document, with where it lies in the editable text. */
interface ViewerSelection {
  text: string
  /** Offsets in the editable text: textarea value, or text of the rendered document. 0 and 0 for the HTML page. */
  from: number
  to: number
  startLine?: number
  endLine?: number
  /** Markup of the selection and chain of its ancestors, reported by the bridge of an HTML page. */
  html?: string
  context?: ExchangeSelectionContextEntry[]
}

/** A selection frozen when the composer was opened. */
interface FrozenSelection extends ViewerSelection {
  unsaved: boolean
  /** Markdown source of the selection (turndown on the selected DOM), once computed: rendered markdown only. */
  markdown?: string
}

/** Looks like markdown: a heading, list or quote marker at the start of a line, emphasis, code or a link. */
const LOOKS_LIKE_MARKDOWN =
  /^(?: {0,3}(?:#{1,6}[ \t]|[-*+][ \t]|\d+[.)][ \t]|>[ \t]?|```))|\*\*[^*\n]+\*\*|(?<![*\w])\*[^*\s][^*\n]*\*(?![*\w])|`[^`\n]+`|\[[^\]\n]+\]\([^)\s]+\)/m

/** The buttons of the formatting toolbar of the markdown editor. */
const FORMAT_BUTTONS: { icon: string; title: string; action: MarkdownAction }[] = [
  { icon: 'title', title: 'Heading 1 (Ctrl+Alt+1)', action: { kind: 'block', block: 'h1' } },
  { icon: 'text_fields', title: 'Heading 2 (Ctrl+Alt+2)', action: { kind: 'block', block: 'h2' } },
  { icon: 'format_bold', title: 'Bold (Ctrl+B)', action: { kind: 'inline', tag: 'strong' } },
  { icon: 'format_italic', title: 'Italic (Ctrl+I)', action: { kind: 'inline', tag: 'em' } },
  { icon: 'format_list_bulleted', title: 'Bulleted list (Ctrl+Shift+8)', action: { kind: 'list', list: 'ul' } },
  { icon: 'format_list_numbered', title: 'Numbered list (Ctrl+Shift+7)', action: { kind: 'list', list: 'ol' } },
  { icon: 'link', title: 'Link (Ctrl+K)', action: { kind: 'link' } },
  { icon: 'code', title: 'Code (Ctrl+E)', action: { kind: 'inline', tag: 'code' } },
  { icon: 'subject', title: 'Paragraph (Ctrl+Alt+0)', action: { kind: 'block', block: 'p' } },
]

interface CssHighlights {
  set(name: string, highlight: unknown): void
  delete(name: string): void
}

/** The CSS Custom Highlight registry, when the browser has it. */
function cssHighlights(): CssHighlights | null {
  const registry = (globalThis as { CSS?: { highlights?: CssHighlights } }).CSS?.highlights
  return registry && typeof (globalThis as { Highlight?: unknown }).Highlight === 'function' ? registry : null
}

/** Wraps every text node of [range] (or the part of it inside the range) in `<mark class="agentos-selection">`. */
function wrapRangeInMarks(range: Range): void {
  const ancestor = range.commonAncestorContainer
  const scope = ancestor.nodeType === Node.TEXT_NODE ? ancestor.parentNode : ancestor
  if (!scope) return
  const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT)
  const texts: Text[] = []
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (range.intersectsNode(node)) texts.push(node as Text)
  }
  for (const text of texts) {
    const start = text === range.startContainer ? range.startOffset : 0
    const end = text === range.endContainer ? range.endOffset : text.length
    if (end <= start || !text.parentNode) continue
    let target = text
    if (end < target.length) target.splitText(end)
    if (start > 0) target = target.splitText(start)
    const mark = document.createElement('mark')
    mark.className = HIGHLIGHT_NAME
    target.parentNode?.insertBefore(mark, target)
    mark.appendChild(target)
  }
}

/** Plain text as DOM, for a paste that is not markdown: blank lines separate paragraphs, a line break stays one. */
function plainTextFragment(text: string): DocumentFragment {
  const fragment = document.createDocumentFragment()
  const paragraphs = text
    .replace(/\r\n?/g, '\n')
    .split(/\n{2,}/)
    .filter((paragraph) => paragraph.length > 0)
  const inline = (target: Node, paragraph: string): void => {
    paragraph.split('\n').forEach((line, index) => {
      if (index > 0) target.appendChild(document.createElement('br'))
      if (line) target.appendChild(document.createTextNode(line))
    })
  }
  if (paragraphs.length <= 1) {
    inline(fragment, paragraphs[0] ?? '')
    return fragment
  }
  for (const paragraph of paragraphs) {
    const block = document.createElement('p')
    inline(block, paragraph)
    fragment.appendChild(block)
  }
  return fragment
}

function unwrapMarks(root: Element): void {
  const marks = Array.from(root.querySelectorAll('mark.agentos-selection'))
  for (const mark of marks) {
    const parent = mark.parentNode
    if (!parent) continue
    while (mark.firstChild) parent.insertBefore(mark.firstChild, mark)
    parent.removeChild(mark)
  }
  if (marks.length) root.normalize()
}

/**
 * ExchangeContentViewerComponent — presentational preview and editor pane for a single file.
 *
 * Renders markdown, JSON re-indented, YAML/text in a <pre>, and HTML in a sandboxed <iframe> (with a title) that runs
 * the file's scripts in an opaque origin (see `trustSandboxedSrcdoc`), with a full-viewport view and an "open in new
 * tab" action. Non-viewable files (binary or > 20 MB) and load errors show a "Download instead" CTA. Images and PDFs
 * are shown from an object URL prepared by the parent (`blobUrl`, with its `blob`).
 *
 * A file the parent allows to write (`canEdit`) is edited IN PLACE, in what it looks like:
 * - markdown: the rendered document is `contenteditable`; it is converted back to markdown (turndown) on save only,
 *   and is re-rendered when the file or its saved content changes, never on a keystroke (the caret must not jump);
 * - HTML: the sandboxed iframe is edited through its bridge (`designMode`); the bridge posts the serialized page,
 *   which is only saved on the user's explicit Save (the payload is the user's own file content, the sandbox is
 *   unchanged, and nothing from it is ever injected in the DOM of the app);
 * - other text formats: a plain textarea styled as the document.
 * Nothing is written here: the parent saves and reports back through `saveStatus` / `saveError` / `content`.
 *
 * A selection opens a small popover right under it (see `computePopoverPosition`), as soon as the selection is
 * settled (mouse or key released): the composer, with the same look as the chat composer, the agent picked by typing
 * `@`. The selection is not quoted: it is shown by its highlight in the document, frozen when the user clicks into
 * the popover, and kept until the agent's proposal (the `request` input) is accepted or rejected, or the user
 * dismisses it. While the agent works the popover becomes a waiting line at the same place, then the annotation card
 * with a word diff; accepting replaces the passage in the draft, it never saves.
 *
 * I/O in signals (decision #8). The smart parent owns content loading, saving and the canView decision.
 */
@Component({
  selector: 'agentos-exchange-content-viewer',
  standalone: true,
  imports: [
    SpinnerComponent,
    EmptyStateComponent,
    IconButtonComponent,
    ConfirmDialogComponent,
    BlueprintDirective,
    SelectionComposerComponent,
  ],
  templateUrl: './exchange-content-viewer.component.html',
  styleUrl: './exchange-content-viewer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:selectionchange)': 'onDocumentSelectionChange()',
    // The popover opens once the selection is settled: on mouse / key release, not while the user is dragging.
    '(mouseup)': 'onSelectionSettled($event)',
    '(keyup)': 'onSelectionSettled($event)',
    '(document:mousedown)': 'onDocumentMousedown($event)',
    '(document:keydown)': 'onDocumentKeydown($event)',
    '(click)': 'onPopoverClick($event)',
    '(window:resize)': 'schedulePosition()',
  },
})
export class ExchangeContentViewerComponent {
  readonly file = input.required<ExchangeFileRef>()
  readonly content = input<string | null>(null)
  /** Object URL of the image or PDF to display, owned (and revoked) by the parent. */
  readonly blobUrl = input<string | null>(null)
  /** The bytes behind `blobUrl`, typed: a new tab gets its own object URL from it. */
  readonly blob = input<Blob | null>(null)
  readonly status = input<'loading' | 'ready' | 'error'>('loading')
  /** False when the file is binary or too large to preview — only a download is offered. */
  readonly canView = input<boolean>(true)
  /** The user may write this file: the parent decides from the scope capability. */
  readonly canEdit = input<boolean>(false)
  readonly saveStatus = input<ExchangeSaveStatus>('idle')
  readonly saveError = input<string | null>(null)
  /** Namespace whose agents the composer suggests on `@`. */
  readonly namespaceId = input<string>('')
  /** The agent request about this file, if any (from the shared request service, through the shell). */
  readonly request = input<ExchangeSelectionRequest | null>(null)

  readonly closeRequested = output<void>()
  readonly downloadRequested = output<ExchangeFileRef>()
  /** The edited text to write to the file. */
  readonly saveRequested = output<{ ref: ExchangeFileRef; content: string }>()
  /** The file changed since it was opened and the user chose to load its latest version. */
  readonly reloadRequested = output<void>()
  /** The user asked an agent to act on a selection. */
  readonly selectionActionRequested = output<ExchangeSelectionAction>()
  /** The request of this file was settled (accepted, rejected, dismissed): its id, to forget it. */
  readonly requestCleared = output<string>()
  /** Whether the editor holds changes that are not saved: the parent must not replace the content then. */
  readonly dirtyChanged = output<boolean>()

  private readonly injector = inject(Injector)
  private readonly destroyRef = inject(DestroyRef)
  private readonly popoverEl = viewChild<ElementRef<HTMLElement>>('popoverBox')
  private readonly composer = viewChild(SelectionComposerComponent)
  // read:ElementRef so the host element (not the ds-icon-button instance) is returned.
  private readonly backButton = viewChild('backButton', { read: ElementRef })
  private readonly editor = viewChild<ElementRef<HTMLTextAreaElement>>('editor')
  private readonly mirror = viewChild<ElementRef<HTMLElement>>('mirror')
  private readonly previewFrame = viewChild<ElementRef<HTMLIFrameElement>>('previewFrame')
  private readonly body = viewChild<ElementRef<HTMLElement>>('body')
  private readonly linkField = viewChild<ElementRef<HTMLInputElement>>('linkField')
  /** The rendered markdown, or the <pre> of a read-only text file: where DOM selections and highlights live. */
  private readonly docRoot = viewChild<ElementRef<HTMLElement>>('docRoot')

  /** The text being edited in a textarea, loaded from the raw content. */
  protected readonly draft = signal('')
  /** The rendered markdown, replaced only when the file or its saved content changes (never on a keystroke). */
  protected readonly mdHtml = signal<SafeHtml>('')
  /** The user typed in the rendered markdown since it was rendered. */
  private readonly mdEdited = signal(false)
  /** The HTML source of the iframe, as an object so that rendering the same text again reloads the frame. */
  private readonly htmlSource = signal({ html: '' })
  /** The page as last serialized by the bridge after an edit, null until the user typed in it. */
  private readonly htmlDraft = signal<string | null>(null)
  /** What was last sent to be saved: the content that comes back is not a change to render again. */
  private lastSaved: string | null = null

  /** Selection currently held in the document, null when there is none. */
  protected readonly selection = signal<ViewerSelection | null>(null)
  /** Selection captured when the composer opened: moving the focus clears the live one. */
  protected readonly frozen = signal<FrozenSelection | null>(null)
  protected readonly activeSelection = computed<ViewerSelection | null>(() => this.frozen() ?? this.selection())
  /** Text the composer reopens with ("Ask again"). */
  protected readonly prefill = signal('')
  /** Where the passage ends in the page of the HTML preview, as its bridge reported it (iframe viewport). */
  private readonly frameRect = signal<PopoverRect | null>(null)
  /** The selection is settled (mouse or key released): the composer may open, with no extra click. */
  protected readonly composerOpen = signal(false)
  /** The live selection of the rendered document, to anchor the popover before the selection is frozen. */
  private liveRange: Range | null = null
  /** Where the popover sits, null until it is measured. */
  protected readonly popover = signal<PopoverState | null>(null)
  private frame: number | null = null
  /** Why the proposal cannot be applied, when it cannot. */
  protected readonly acceptError = signal<string | null>(null)
  /** Whether the confirmation to leave the file with unsaved changes is showing. */
  protected readonly pendingDiscard = signal(false)
  /** The buttons of the formatting toolbar (markdown edited in place). */
  protected readonly formatButtons = FORMAT_BUTTONS
  /** The small input that asks for the URL of a link (not `window.prompt`), null while closed. */
  protected readonly linkBar = signal<{ url: string; error: string | null } | null>(null)
  /** The selection of the document when the link input opened: the focus leaves it, the link goes there. */
  private linkRange: Range | null = null
  /** The markdown source of the frozen selection being computed (turndown is lazy-loaded). */
  private markdownPending: Promise<void> | null = null

  constructor() {
    ensureHighlightStyle()

    // A new image (or a new file) gets a fresh chance to decode.
    effect(() => {
      this.blobUrl()
      this.file()
      this.imageFailed.set(false)
    })

    // a11y: move focus into the preview when it opens so keyboard users land on the
    // back control rather than losing focus to <body>.
    afterNextRender(() => {
      const host = this.backButton()?.nativeElement as HTMLElement | undefined
      host?.querySelector('button')?.focus()
    })

    // The document is loaded from the raw content. A different file starts from a clean view. New content of the
    // same file replaces what is shown, unless the user edited it: an edited document is kept, and saving it is
    // refused as stale by the parent. The content that comes back after our own save is not rendered again.
    let previous: { key: string; content: string | null } | null = null
    effect(() => {
      const key = this.fileKey()
      const content = this.content()
      untracked(() => {
        if (!previous || previous.key !== key) {
          this.lastSaved = null
          this.draft.set(content ?? '')
          this.mdEdited.set(false)
          this.htmlDraft.set(null)
          this.render(content)
          this.selection.set(null)
          this.frozen.set(null)
          this.composerOpen.set(false)
          this.frameRect.set(null)
          this.liveRange = null
          this.prefill.set('')
          this.acceptError.set(null)
          this.pendingDiscard.set(false)
          this.linkBar.set(null)
          this.linkRange = null
          this.markdownPending = null
        } else if (content !== previous.content) {
          if (this.editMode() === 'markdown' || this.editMode() === 'html') {
            if (content === this.lastSaved) {
              // Our own save: the page already shows it.
              this.mdEdited.set(false)
              this.htmlDraft.set(null)
              this.draft.set(content ?? '')
            } else if (!this.mdEdited() && this.htmlDraft() === null) {
              this.draft.set(content ?? '')
              this.render(content)
            }
          } else {
            if (this.draft() === (previous.content ?? '')) this.draft.set(content ?? '')
            this.render(content)
          }
        }
        previous = { key, content }
      })
    })

    // A request that outlives the viewer (the file was closed and reopened) restores its frozen selection.
    effect(() => {
      const request = this.request()
      untracked(() => {
        if (request && !this.frozen()) {
          this.frozen.set({
            text: request.selection,
            from: request.from,
            to: request.to,
            unsaved: false,
            ...(request.selectionMarkdown !== undefined ? { markdown: request.selectionMarkdown } : {}),
            ...(request.selectionHtml !== undefined ? { html: request.selectionHtml } : {}),
          })
        }
      })
    })

    // The highlight follows the frozen selection and the rendered document.
    effect(() => {
      this.frozen()
      this.mdHtml()
      this.status()
      this.editMode()
      untracked(() => afterNextRender(() => this.applyHighlight(), { injector: this.injector }))
    })

    // The HTML preview reports by message: only its own window is believed.
    fromEvent<MessageEvent>(window, 'message')
      .pipe(takeUntilDestroyed())
      .subscribe((event) => this.onPreviewMessage(event))

    // The bridge is told whether to edit, and what to highlight, whenever that changes.
    effect(() => {
      const enabled = this.editMode() === 'html'
      const text = this.frozen()?.text.trim() || null
      untracked(() => this.postState(enabled, text))
    })

    effect(() => this.dirtyChanged.emit(this.dirty()))

    // The popover follows what it is about: it is placed again when the selection, the request or its card change.
    effect(() => {
      this.popoverVisible()
      this.selection()
      this.frozen()
      this.frameRect()
      this.request()
      this.acceptError()
      this.draft()
      untracked(() => afterNextRender(() => this.updatePopover(), { injector: this.injector }))
    })

    // ... and when the body or the popover itself change size (the drawer is resized, the composer grows).
    effect((onCleanup) => {
      const body = this.body()?.nativeElement
      const popover = this.popoverEl()?.nativeElement
      if (typeof ResizeObserver === 'undefined' || (!body && !popover)) return
      const observer = new ResizeObserver(() => this.schedulePosition())
      if (body) observer.observe(body)
      if (popover) observer.observe(popover)
      onCleanup(() => observer.disconnect())
    })

    this.destroyRef.onDestroy(() => {
      if (this.frame !== null) cancelAnimationFrame(this.frame)
      if (this.settleTimer !== null) clearTimeout(this.settleTimer)
    })
  }

  protected readonly filename = computed(() => {
    const path = this.file().path
    return path.split('/').pop() ?? path
  })

  private readonly fileKey = computed(() => `${this.file().scope}:${this.file().path}`)

  protected readonly format = computed(() => detectFormat(this.filename()))

  /** Set when the browser could not decode the image, to fall back to the download CTA. */
  protected readonly imageFailed = signal(false)

  protected onImageError(): void {
    this.imageFailed.set(true)
  }

  /** JSON is re-indented for readability; other text formats render as-is. */
  protected readonly displayContent = computed(() => {
    const raw = this.content()
    if (raw == null) return ''
    return this.format() === 'json' ? reindentJson(raw) : raw
  })

  private readonly sanitizer = inject(DomSanitizer)
  private readonly dialog = inject(MatDialog)
  private readonly htmlPreview = inject(ExchangeHtmlPreviewService)
  private readonly markdown = inject(MarkdownRendererService)

  /**
   * The HTML file as the `srcdoc` of the sandboxed iframe, with the bridge. Trusting it is safe ONLY because that
   * iframe is sandboxed without `allow-same-origin` (static attribute in the template): see `trustSandboxedSrcdoc`.
   * It follows the saved content, not the edits: the user types in the frame itself, which must not reload.
   */
  protected readonly htmlSrcdoc = computed(() =>
    trustSandboxedSrcdoc(this.sanitizer, withSelectionBridge(this.htmlSource().html))
  )

  /**
   * The PDF as the `src` of the (unsandboxed) iframe, from a `blob:` URL only: see `trustPdfBlobUrl`.
   * A single place decides what is trusted: the iframe of the template reads nothing else.
   */
  protected readonly pdfSrc = computed(() =>
    this.format() === 'pdf' ? trustPdfBlobUrl(this.sanitizer, this.blobUrl()) : null
  )

  /** The HTML actions (full view, new tab) need the content to be there and previewable. */
  protected readonly canOpenHtml = computed(
    () => this.format() === 'html' && this.status() === 'ready' && this.canView() && this.content() != null
  )

  /** Same for a PDF: its bytes must be loaded, and it must be typed as such. */
  protected readonly canOpenPdf = computed(
    () =>
      this.format() === 'pdf' &&
      this.status() === 'ready' &&
      this.canView() &&
      this.blobUrl() != null &&
      this.blob() != null
  )

  /** Whether the header offers the full view and the new tab. */
  protected readonly canOpenFull = computed(() => this.canOpenHtml() || this.canOpenPdf())

  /** The file opens in the editor: a loaded text file the parent allows to write. */
  protected readonly editable = computed(
    () => this.canEdit() && this.canView() && this.status() === 'ready' && isEditableFormat(this.filename())
  )

  protected readonly editMode = computed<EditMode | null>(() => {
    if (!this.editable()) return null
    const format = this.format()
    return format === 'markdown' ? 'markdown' : format === 'html' ? 'html' : 'text'
  })

  /** An editable file without a rendered view: a plain textarea, styled as the document. */
  protected readonly textEditor = computed(() => this.editMode() === 'text')

  protected readonly dirty = computed(() => {
    switch (this.editMode()) {
      case 'markdown':
        return this.mdEdited()
      case 'html': {
        const edited = this.htmlDraft()
        return edited !== null && edited !== this.content()
      }
      case 'text':
        return this.draft() !== (this.content() ?? '')
      default:
        return false
    }
  })
  protected readonly saving = computed(() => this.saveStatus() === 'saving')
  protected readonly canSave = computed(() => this.dirty() && !this.saving())
  protected readonly hasSaveProblem = computed(() => this.saveStatus() === 'error' || this.saveStatus() === 'conflict')

  // ── Rendering ────────────────────────────────────────────────────────────────────────
  /** Renders [content] again in the views that do not follow the keystrokes (markdown and HTML page). */
  private render(content: string | null): void {
    this.mdHtml.set(this.markdown.render(content ?? ''))
    this.htmlSource.set({ html: content ?? '' })
  }

  // ── Navigation / download ────────────────────────────────────────────────────────────────────────
  protected onClose(): void {
    if (this.dirty()) {
      this.pendingDiscard.set(true)
      return
    }
    this.closeRequested.emit()
  }

  /**
   * Opens the file in a full-viewport dialog. A dialog is an overlay attached to the body: a `position: fixed`
   * element inside the drawer would be broken by the transforms of mat-sidenav.
   * The app sets a smaller default size for dialogs, hence the explicit overrides.
   */
  protected onOpenFullView(): void {
    const data = this.fullViewData()
    if (!data) return
    this.dialog
      .open<ExchangeHtmlFullViewComponent, ExchangeHtmlFullViewData, string>(ExchangeHtmlFullViewComponent, {
        data,
        width: '100vw',
        height: '100vh',
        maxWidth: '100vw',
        maxHeight: '100vh',
        panelClass: EXCHANGE_FULL_VIEW_PANEL_CLASS,
        autoFocus: 'first-tabbable',
        restoreFocus: true,
      })
      .afterClosed()
      .subscribe((result) => {
        if (result === EXCHANGE_HTML_FULL_VIEW_DOWNLOAD) this.onDownload()
      })
  }

  private fullViewData(): ExchangeHtmlFullViewData | null {
    if (this.canOpenHtml()) {
      return { kind: 'html', content: this.displayContent(), filename: this.filename() }
    }
    const url = this.blobUrl()
    const blob = this.blob()
    if (this.canOpenPdf() && url && blob) return { kind: 'pdf', url, blob, filename: this.filename() }
    return null
  }

  /** Synchronous in the click handler, so that popup blockers let the tab open. */
  protected onOpenInNewTab(): void {
    if (this.canOpenHtml()) {
      this.htmlPreview.openInNewTab(this.displayContent(), this.filename())
      return
    }
    const blob = this.blob()
    if (this.canOpenPdf() && blob) this.htmlPreview.openBlobInNewTab(blob)
  }

  protected onDownload(): void {
    this.downloadRequested.emit(this.file())
  }

  private readonly copyFeedback = injectCopyFeedback()
  protected readonly copyState = this.copyFeedback.state

  /** Copies the path relative to the scope root, as a file reference to paste elsewhere. */
  protected onCopyPath(): void {
    void this.copyFeedback.copy(this.file().path)
  }

  // ── Editing ────────────────────────────────────────────────────────────────────────
  protected onDraftInput(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value)
    this.captureEditorSelection()
  }

  /** The user typed in the rendered markdown: it is dirty from now on, and converted back when saved. */
  protected onMarkdownInput(event?: Event): void {
    if (this.editMode() !== 'markdown') return
    // `**text**` / `*text*` / `code` turn into their formatting when the closing marker is typed.
    const root = this.docRoot()?.nativeElement
    const selection = window.getSelection()
    const typed = !(event instanceof InputEvent) || event.inputType === 'insertText'
    if (typed && root && selection) applyInlineInputRule(root, selection)
    this.mdEdited.set(true)
  }

  protected onEditorKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault()
      void this.onSave()
    } else if (event.key === 'Escape') {
      // Escape never closes the panel around unsaved edits; it closes the composer, when there is one.
      event.stopPropagation()
      if (this.linkBar()) this.closeLinkBar()
      else if (this.composerShown()) this.closeComposer()
    } else if (this.editMode() === 'markdown') {
      this.onMarkdownKeydown(event)
    }
  }

  /** Shortcuts of the markdown editor, and the block input rules (typed markers that become blocks). */
  private onMarkdownKeydown(event: KeyboardEvent): void {
    const root = this.docRoot()?.nativeElement
    const selection = window.getSelection()
    if (!root || !selection) return
    const action = markdownActionFor(event)
    if (action) {
      event.preventDefault()
      this.runFormat(action)
      return
    }
    if (event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return
    if (event.key === ' ' && applyBlockInputRule(root, selection)) {
      event.preventDefault()
      this.mdEdited.set(true)
    } else if (event.key === 'Enter' && !event.shiftKey && applyCodeFenceRule(root, selection)) {
      event.preventDefault()
      this.mdEdited.set(true)
    }
  }

  // ── Formatting (toolbar and shortcuts) ────────────────────────────────────────────────────────────────────
  /** Runs a formatting action on the selection of the editor; the draft is dirty when it changed the document. */
  protected runFormat(action: MarkdownAction): void {
    if (action.kind === 'link') {
      this.openLinkBar()
      return
    }
    const root = this.docRoot()?.nativeElement
    const selection = window.getSelection()
    if (this.editMode() !== 'markdown' || !root || !selection) return
    if (!this.selectionInEditor(root, selection)) root.focus()
    if (runMarkdownAction(root, selection, action)) this.mdEdited.set(true)
  }

  private selectionInEditor(root: HTMLElement, selection: Selection): boolean {
    return selection.rangeCount > 0 && root.contains(selection.getRangeAt(0).startContainer)
  }

  /** Opens the small URL input (never `window.prompt`), remembering where in the document the link goes. */
  protected openLinkBar(): void {
    const root = this.docRoot()?.nativeElement
    const selection = window.getSelection()
    if (this.editMode() !== 'markdown' || !root || !selection || !this.selectionInEditor(root, selection)) return
    this.linkRange = selection.getRangeAt(0).cloneRange()
    this.linkBar.set({ url: linkAtSelection(root, selection) ?? '', error: null })
    afterNextRender(() => this.linkField()?.nativeElement.focus(), { injector: this.injector })
  }

  protected onLinkInput(event: Event): void {
    this.linkBar.set({ url: (event.target as HTMLInputElement).value, error: null })
  }

  protected onLinkKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault()
      this.applyLink()
    } else if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      this.closeLinkBar()
    }
  }

  /** Links the selection to the typed URL, when it is acceptable: http(s), mailto, a relative path or a `#` anchor. */
  protected applyLink(): void {
    const bar = this.linkBar()
    const root = this.docRoot()?.nativeElement
    const selection = window.getSelection()
    if (!bar || !root || !selection || !this.linkRange) return
    if (bar.url.trim() && sanitizeLinkUrl(bar.url) === null) {
      this.linkBar.set({ ...bar, error: 'Only http(s), mailto, relative and # links are allowed' })
      return
    }
    root.focus()
    selection.removeAllRanges()
    selection.addRange(this.linkRange)
    const changed = bar.url.trim() ? insertLink(root, selection, bar.url) : removeLinks(root, selection)
    if (changed) this.mdEdited.set(true)
    this.closeLinkBar()
  }

  /** Closes the URL input and puts the caret back in the document. */
  protected closeLinkBar(): void {
    this.linkBar.set(null)
    const root = this.docRoot()?.nativeElement
    const selection = window.getSelection()
    if (root && selection && this.linkRange) {
      root.focus()
      selection.removeAllRanges()
      selection.addRange(this.linkRange)
    }
    this.linkRange = null
  }

  /**
   * Paste into the rendered markdown. Never raw HTML: pasted HTML goes through turndown (parsed inertly) and back
   * through the sanitized markdown pipeline, text that looks like markdown is rendered by that pipeline, and any
   * other text is inserted as plain text.
   */
  protected onMarkdownPaste(event: ClipboardEvent): void {
    if (this.editMode() !== 'markdown') return
    const root = this.docRoot()?.nativeElement
    const selection = window.getSelection()
    if (!root || !selection || !this.selectionInEditor(root, selection)) return
    event.preventDefault()
    const html = event.clipboardData?.getData('text/html') ?? ''
    const text = event.clipboardData?.getData('text/plain') ?? ''
    const range = selection.getRangeAt(0)
    const insert = (fragment: DocumentFragment): void => {
      replaceRangeWithFragment(root, range, fragment)
      selection.removeAllRanges()
      selection.addRange(range)
      this.mdEdited.set(true)
    }
    if (html.trim()) {
      void htmlStringToMarkdown(html).then(
        (markdown) => insert(markdown ? renderMarkdownFragment(markdown) : plainTextFragment(text)),
        () => insert(plainTextFragment(text))
      )
    } else if (text) {
      insert(LOOKS_LIKE_MARKDOWN.test(text) ? renderMarkdownFragment(text) : plainTextFragment(text))
    }
  }

  protected async onSave(): Promise<void> {
    if (!this.canSave()) return
    const ref = this.file()
    let content: string | null = null
    switch (this.editMode()) {
      case 'markdown': {
        const root = this.docRoot()?.nativeElement
        if (!root) return
        content = await htmlToMarkdown(root)
        break
      }
      case 'html':
        content = this.htmlDraft()
        break
      case 'text':
        content = this.draft()
        break
    }
    if (content === null) return
    this.lastSaved = content
    this.saveRequested.emit({ ref, content })
  }

  /** Restores the loaded content in the editor. */
  protected onRevert(): void {
    this.restoreContent()
    this.selection.set(null)
  }

  /** Drops the edits: the document is rendered again from the loaded content. */
  private restoreContent(): void {
    this.draft.set(this.content() ?? '')
    this.mdEdited.set(false)
    this.htmlDraft.set(null)
    this.lastSaved = null
    this.render(this.content())
  }

  protected onConfirmDiscard(): void {
    this.pendingDiscard.set(false)
    this.restoreContent()
    this.closeRequested.emit()
  }

  protected onKeepEditing(): void {
    this.pendingDiscard.set(false)
  }

  /** Drops the stale draft, to see the file as the server has it. */
  protected onReload(): void {
    this.restoreContent()
    this.selection.set(null)
    this.reloadRequested.emit()
  }

  // ── Selection → composer ────────────────────────────────────────────────────────────────────────
  /** Selection made in the textarea (offsets are exact, so the lines are known). */
  protected captureEditorSelection(): void {
    const element = this.editor()?.nativeElement
    if (!element || this.frozen()) return
    const { selectionStart, selectionEnd, value } = element
    const text = value.slice(selectionStart, selectionEnd)
    if (!text.trim()) {
      if (this.selection()) this.selection.set(null)
      return
    }
    this.selection.set({
      text,
      from: selectionStart,
      to: selectionEnd,
      ...selectionLineRange(value, selectionStart, selectionEnd),
    })
  }

  /** Selection made in the rendered markdown or a text preview, when it lies within the document. */
  protected onDocumentSelectionChange(): void {
    if (this.frozen()) return
    if (this.textEditor()) {
      this.captureEditorSelection()
      return
    }
    // The HTML preview is a sandboxed iframe: its selection arrives through onPreviewMessage.
    if (this.format() === 'html') return
    const selection = window.getSelection()
    const text = selection?.toString() ?? ''
    const root = this.docRoot()?.nativeElement
    const range = selection?.rangeCount ? selection.getRangeAt(0) : null
    const offsets = root && range ? rangeTextOffsets(root, range) : null
    const next = text.trim() && offsets ? { text, ...offsets } : null
    // selectionchange fires for the whole document (the composer included): ignore what did not change.
    if (next?.text !== this.selection()?.text || next?.from !== this.selection()?.from) {
      this.selection.set(next)
      this.liveRange = next && range ? range.cloneRange() : null
    }
  }

  /** Whether [target] lies in the floating popover (composer, waiting line or proposal card). */
  private inPopover(target: EventTarget | null): boolean {
    const popover = this.popoverEl()?.nativeElement
    return !!popover && target instanceof Node && popover.contains(target)
  }

  /**
   * The selection is settled (mouse or key released, never while the user is still dragging): the composer opens
   * under it, with no extra click and without taking the focus. The selection is frozen (highlighted) from then on.
   * The HTML preview does the same from its bridge.
   */
  protected onSelectionSettled(event?: Event): void {
    if (this.frozen() || this.status() !== 'ready' || !this.canView() || this.format() === 'html') return
    if (event instanceof KeyboardEvent && event.key === 'Escape') return
    if (this.inPopover(event?.target ?? null)) return
    // Selecting with the keyboard goes on key after key: wait for a pause. A mouse release is final.
    if (event?.type === 'keyup') {
      if (this.settleTimer !== null) clearTimeout(this.settleTimer)
      this.settleTimer = setTimeout(() => this.settle(), KEY_SETTLE_MS)
      return
    }
    this.settle()
  }

  private settleTimer: ReturnType<typeof setTimeout> | null = null

  private settle(): void {
    this.settleTimer = null
    if (this.frozen()) return
    this.onDocumentSelectionChange()
    if (this.selection()) {
      this.composerOpen.set(true)
      this.freeze()
    }
  }

  /** A click elsewhere closes the composer when nothing is written in it (and clears the selection). */
  protected onDocumentMousedown(event: MouseEvent): void {
    if (!this.composerShown() || this.inPopover(event.target) || this.composer()?.hasText()) return
    this.dismiss()
  }

  /** Escape closes the composer; a printable key, with nothing to type in, goes into it. */
  protected onDocumentKeydown(event: KeyboardEvent): void {
    if (!this.composerShown() || event.defaultPrevented) return
    if (this.inPopover(event.target)) return
    if (event.key === 'Escape') {
      this.closeComposer()
      return
    }
    if (event.key.length !== 1 || event.ctrlKey || event.metaKey || event.altKey || this.isTextEntry(event.target)) {
      return
    }
    event.preventDefault()
    this.freeze()
    this.composer()?.type(event.key)
  }

  /** An element the user types in or presses: a key typed there is for it, not for the composer. */
  private isTextEntry(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false
    return target.isContentEditable || ['TEXTAREA', 'INPUT', 'SELECT', 'BUTTON', 'A'].includes(target.tagName)
  }

  /** A click on the popover itself (not on its input or a button) puts the caret in the composer. */
  protected onPopoverClick(event: Event): void {
    const target = event.target as HTMLElement | null
    if (!this.composerShown() || !this.inPopover(target)) return
    if (!target || target.closest('textarea, button, agentos-agent-autocomplete')) return
    this.composer()?.focus()
  }

  /**
   * Messages of the bridge of the HTML preview (see `withSelectionBridge`). Only a message from the preview's own
   * window, with the expected shape, counts. An empty selection clears the selection, except once it is frozen
   * (focusing the composer collapses the iframe selection).
   */
  private onPreviewMessage(event: MessageEvent): void {
    const frame = this.previewFrame()?.nativeElement
    if (!frame || event.source !== frame.contentWindow) return
    const data = event.data as {
      type?: unknown
      text?: unknown
      html?: unknown
      ok?: unknown
      rect?: unknown
      click?: unknown
      context?: unknown
    } | null
    if (!data || typeof data.type !== 'string') return
    switch (data.type) {
      case EXCHANGE_SELECTION_MESSAGE_TYPE: {
        if (typeof data.text !== 'string') return
        if (this.frozen()) {
          // A click in the page, with nothing written in the composer, lets go of the frozen passage (the page moves
          // its own selection when the composer takes the focus: only a pointer release counts).
          if (data.click !== true || this.request() || this.composer()?.hasText()) return
          this.dismiss()
        }
        const text = data.text.trim().slice(0, MAX_PREVIEW_SELECTION)
        if (text) {
          // The bridge reports a settled selection: the popover opens under it, from the rect it sent.
          this.frameRect.set(toRect(data.rect))
          const html = toSelectionHtml(data.html)
          const context = toSelectionContext(data.context)
          this.selection.set({
            text,
            from: 0,
            to: 0,
            ...(html !== undefined ? { html } : {}),
            ...(context !== undefined ? { context } : {}),
          })
          this.composerOpen.set(true)
          this.freeze()
        } else {
          this.selection.set(null)
        }
        return
      }
      case EXCHANGE_READY_MESSAGE_TYPE:
        this.postState(this.editMode() === 'html', this.frozen()?.text.trim() || null)
        return
      case EXCHANGE_HTML_MESSAGE_TYPE:
        // SECURITY: the page is the user's own file; it is kept as a string, saved only on an explicit Save, and
        // never inserted in the DOM of the app.
        if (this.editMode() === 'html' && typeof data.html === 'string' && data.html.length <= MAX_EDITED_HTML) {
          this.htmlDraft.set(data.html)
        }
        return
      case EXCHANGE_SAVE_MESSAGE_TYPE:
        if (this.editMode() === 'html') void this.onSave()
        return
      case EXCHANGE_ANCHOR_MESSAGE_TYPE: {
        // The passage moved in the page (scroll, resize): the popover follows it.
        const rect = toRect(data.rect)
        if (rect) this.frameRect.set(rect)
        return
      }
      case EXCHANGE_REPLACE_RESULT_MESSAGE_TYPE:
        if (data.ok === true) this.finishRequest()
        else if (data.ok === false) this.acceptError.set(PASSAGE_CHANGED)
        return
    }
  }

  /** Tells the bridge whether to edit the page and what to highlight. */
  private postState(editEnabled: boolean, highlight: string | null): void {
    this.postToFrame({ type: EXCHANGE_EDIT_MESSAGE_TYPE, enabled: editEnabled })
    this.postToFrame({ type: EXCHANGE_HIGHLIGHT_MESSAGE_TYPE, text: highlight })
  }

  private postToFrame(message: Record<string, unknown>): void {
    // The target origin is '*' because the sandboxed frame has an opaque origin; the message is meant for it alone.
    this.previewFrame()?.nativeElement.contentWindow?.postMessage(message, '*')
  }

  /** Freezes the live selection: from now on it is the one the composer and the highlight are about. */
  protected freeze(): void {
    const selection = this.selection()
    if (this.frozen() || !selection) return
    this.frozen.set({ ...selection, unsaved: this.dirty() })
    this.computeSelectionMarkdown()
  }

  /**
   * The markdown source of the frozen selection of the rendered document (turndown on a clone of the selected DOM:
   * an h1 and a paragraph give `# Title\n\nparagraph`). The text stays for the highlight and the anchoring; this is
   * what the agent is shown and what the word diff compares. The clone is taken now, the conversion is async.
   */
  private computeSelectionMarkdown(): void {
    const range = this.liveRange
    if (this.format() !== 'markdown' || !range || this.textEditor()) return
    const frozen = this.frozen()
    if (!frozen) return
    const pending = rangeToMarkdown(range).then(
      (markdown) => {
        const current = this.frozen()
        if (current === frozen && markdown) this.frozen.set({ ...current, markdown })
      },
      () => undefined
    )
    this.markdownPending = pending
    void pending.finally(() => {
      if (this.markdownPending === pending) this.markdownPending = null
    })
  }

  protected onComposerSent(instruction: string): void {
    this.freeze()
    const selection = this.frozen()
    if (!selection) return
    this.prefill.set('')
    const emit = (): void => {
      const frozen = this.frozen() ?? selection
      this.selectionActionRequested.emit({
        ref: this.file(),
        selection: frozen.text,
        ...(frozen.markdown !== undefined ? { selectionMarkdown: frozen.markdown } : {}),
        ...(frozen.html !== undefined ? { selectionHtml: frozen.html } : {}),
        ...(frozen.context !== undefined ? { selectionContext: frozen.context } : {}),
        ...(frozen.startLine !== undefined && frozen.endLine !== undefined
          ? { startLine: frozen.startLine, endLine: frozen.endLine }
          : {}),
        from: frozen.from,
        to: frozen.to,
        instruction,
        unsavedChanges: frozen.unsaved,
      })
    }
    // The markdown source is still being computed: the agent gets it, not the flattened text.
    if (this.markdownPending) void this.markdownPending.then(emit)
    else emit()
  }

  /** Escape in the composer: nothing is asked, the selection and its highlight go. */
  protected closeComposer(): void {
    this.dismiss()
    // The selection of the document goes too, or the next key release would open the composer again.
    window.getSelection()?.removeAllRanges()
    const editor = this.editor()?.nativeElement
    if (editor) editor.setSelectionRange(editor.selectionEnd, editor.selectionEnd)
  }

  /** The user lets go of the selection (and of the request about it, if any). */
  protected dismiss(): void {
    const request = this.request()
    if (request) this.requestCleared.emit(request.id)
    this.frozen.set(null)
    this.selection.set(null)
    this.composerOpen.set(false)
    this.liveRange = null
    this.prefill.set('')
    this.acceptError.set(null)
  }

  /** Name of the agent the instruction mentions, for the waiting line. */
  protected readonly requestedAgent = computed(() => {
    const mention = /@([\w-]+)/.exec(this.request()?.instruction ?? '')
    return mention ? `@${mention[1]}` : 'the agent'
  })

  /** The composer is shown under the selection: there is one, no request about it, and the user did not close it. */
  protected readonly composerShown = computed(
    () =>
      this.status() === 'ready' &&
      this.canView() &&
      this.request() === null &&
      this.composerOpen() &&
      this.activeSelection() !== null
  )

  /** The waiting line: the agent is working on the request. */
  protected readonly waitingShown = computed(
    () => this.status() === 'ready' && this.canView() && this.request()?.status === 'waiting'
  )

  /** Whether the floating popover is there: the composer, the waiting line or the proposal card. */
  protected readonly popoverVisible = computed(() => this.composerShown() || this.waitingShown() || this.cardVisible())

  // ── Proposal (annotation card) ─────────────────────────────────────────────────────────────────────
  protected readonly cardVisible = computed(() => {
    const status = this.request()?.status
    return status === 'proposed' || status === 'answered' || status === 'failed'
  })

  /**
   * Word-level diff of the selected passage against the proposal, in markdown on both sides when the passage was
   * selected in the rendered document: a changed heading shows as `# Title` changing, not as flattened text.
   */
  protected readonly proposalDiff = computed(() => {
    const request = this.request()
    if (request?.status !== 'proposed') return []
    const proposal = request.proposal ?? ''
    if (this.format() === 'html') {
      // Readable diff of the text of the passage against the text content of the proposal (read through a DOMParser).
      return diffWords(request.selection, hasMarkup(proposal) ? readMarkup(proposal).text : proposal)
    }
    const before = this.frozen()?.markdown ?? request.selectionMarkdown ?? request.selection
    return diffWords(before, proposal)
  })

  /** The proposal for an HTML page changes its tags (not only its words): the card says so. */
  protected readonly formattingChanged = computed(() => {
    const request = this.request()
    if (request?.status !== 'proposed' || this.format() !== 'html') return false
    const proposal = request.proposal ?? ''
    if (!hasMarkup(proposal)) return false
    const html = this.frozen()?.html ?? request.selectionHtml
    return readMarkup(proposal).tags !== (html ? readMarkup(html).tags : '')
  })

  protected onReject(): void {
    this.dismiss()
  }

  /** "Ask again": the composer reopens with the instruction, on the same selection and highlight. */
  protected onAskAgain(): void {
    const request = this.request()
    if (!request) return
    this.prefill.set(request.instruction)
    this.acceptError.set(null)
    this.composerOpen.set(true)
    this.requestCleared.emit(request.id)
  }

  /** Replaces the frozen passage with the proposal in the current draft. It never saves. */
  protected onAccept(): void {
    const request = this.request()
    const mode = this.editMode()
    if (!request || request.status !== 'proposed' || request.proposal === undefined || !mode) return
    this.acceptError.set(null)
    const proposal = request.proposal
    const frozen = this.frozen()
    const from = frozen?.from ?? request.from
    const to = frozen?.to ?? request.to
    const text = frozen?.text ?? request.selection

    if (mode === 'html') {
      // The bridge finds the passage again, and applies the proposal in its own document, so that the styles of the
      // page are kept: an HTML fragment when the proposal has tags, plain text otherwise.
      this.postToFrame({
        type: EXCHANGE_REPLACE_MESSAGE_TYPE,
        ...(hasMarkup(proposal) ? { html: proposal } : { text: proposal }),
        selectionText: text.trim(),
      })
      return
    }
    if (mode === 'text') {
      const draft = this.draft()
      const found = locatePassage(draft, from, to, [text])
      if (!found) {
        this.acceptError.set(PASSAGE_CHANGED)
        return
      }
      this.draft.set(draft.slice(0, found.from) + proposal + draft.slice(found.to))
      this.finishRequest()
      return
    }
    // Rendered markdown: the passage is a range of the document, replaced by the proposal RENDERED (markdown through
    // the sanitized pipeline), never inserted as markdown text.
    const root = this.docRoot()?.nativeElement
    if (!root) return
    this.clearHighlight()
    const content = root.textContent ?? ''
    const found = locatePassage(content, from, to, [text, text.trim()], (a, b) => squash(a) === squash(b))
    const range = found ? rangeFromTextOffsets(root, found.from, found.to) : null
    if (!range) {
      this.acceptError.set(PASSAGE_CHANGED)
      this.applyHighlight()
      return
    }
    replaceRangeWithFragment(root, range, renderMarkdownFragment(proposal))
    root.normalize()
    this.mdEdited.set(true)
    this.finishRequest()
  }

  /** The request is settled: it and the highlight go away. */
  private finishRequest(): void {
    const request = this.request()
    if (request) this.requestCleared.emit(request.id)
    this.frozen.set(null)
    this.selection.set(null)
    this.composerOpen.set(false)
    this.acceptError.set(null)
  }

  // ── Highlight ────────────────────────────────────────────────────────────────────────
  /** The range of the CSS highlight, kept to measure where the annotation card goes. */
  private highlightRange: Range | null = null

  private clearHighlight(): void {
    cssHighlights()?.delete(HIGHLIGHT_NAME)
    this.highlightRange = null
    const root = this.docRoot()?.nativeElement
    if (root) unwrapMarks(root)
  }

  /**
   * Highlights the frozen passage in the document: a CSS Custom Highlight, or `<mark class="agentos-selection">`
   * around its text when the browser has none. The textarea shows it through a mirror layer, the HTML page through
   * its bridge (see `postState`).
   */
  private applyHighlight(): void {
    this.clearHighlight()
    const frozen = this.frozen()
    const root = this.docRoot()?.nativeElement
    if (frozen && root && this.editMode() !== 'text' && this.format() !== 'html') {
      const found = locatePassage(
        root.textContent ?? '',
        frozen.from,
        frozen.to,
        [frozen.text, frozen.text.trim()],
        (a, b) => squash(a) === squash(b)
      )
      const range = found ? rangeFromTextOffsets(root, found.from, found.to) : null
      if (range) {
        const registry = cssHighlights()
        if (registry) {
          const Highlight = (globalThis as unknown as { Highlight: new (...ranges: Range[]) => unknown }).Highlight
          registry.set(HIGHLIGHT_NAME, new Highlight(range))
          this.highlightRange = range
        } else {
          wrapRangeInMarks(range)
        }
      }
    }
    this.updatePopover()
  }

  // ── Popover position ────────────────────────────────────────────────────────────────────────
  /** Places the popover again on the next frame: scrolls, resizes and observers fire often, one measure is enough. */
  protected schedulePosition(): void {
    if (this.frame !== null || typeof requestAnimationFrame === 'undefined') return
    this.frame = requestAnimationFrame(() => {
      this.frame = null
      this.updatePopover()
    })
  }

  /**
   * Rectangle, in viewport coordinates, of the end of the selection the popover is about: the last line of the
   * highlighted range (rendered document), of the mirror layer's mark (textarea), or the rect the bridge posted,
   * translated by the frame's own position (HTML preview).
   */
  private selectionAnchor(body: HTMLElement): PopoverRect | null {
    if (this.format() === 'html') {
      const frame = this.previewFrame()?.nativeElement.getBoundingClientRect()
      const rect = this.frameRect()
      if (!frame || !rect) return null
      return {
        left: rect.left + frame.left,
        top: rect.top + frame.top,
        right: rect.right + frame.left,
        bottom: rect.bottom + frame.top,
      }
    }
    if (this.textEditor()) {
      const mark = body.querySelector('.exchange-content-viewer__mirror mark')
      return mark ? anchorFromRects(rectsOf(mark)) : null
    }
    const marks = Array.from(body.querySelectorAll('mark.agentos-selection'))
    const range = this.highlightRange ?? this.liveRange
    const rects = marks.length ? marks.flatMap((mark) => rectsOf(mark)) : range ? rectsOf(range) : []
    return anchorFromRects(rects)
  }

  /** The part of the body in which the anchor is visible: the body, and the frame or the textarea inside it. */
  private visibleArea(bodyRect: PopoverRect): PopoverRect {
    const inner =
      this.format() === 'html'
        ? this.previewFrame()?.nativeElement
        : this.textEditor()
          ? this.editor()?.nativeElement
          : null
    const rect = inner?.getBoundingClientRect()
    if (!rect || !(rect.width || rect.height)) return bodyRect
    return {
      left: Math.max(bodyRect.left, rect.left),
      top: Math.max(bodyRect.top, rect.top),
      right: Math.min(bodyRect.right, rect.right),
      bottom: Math.min(bodyRect.bottom, rect.bottom),
    }
  }

  /**
   * Positions the popover under (or above) the end of the selection, inside the body, with the one pure helper the
   * composer, the waiting line and the annotation card share. It moves with the content: the body scroll offsets are
   * part of the position, and a popover whose anchor scrolled out of view is hidden until it comes back.
   */
  private updatePopover(): void {
    const body = this.body()?.nativeElement
    if (!body || !this.popoverVisible()) {
      if (this.popover() !== null) this.popover.set(null)
      return
    }
    const bodyRect = body.getBoundingClientRect()
    const element = this.popoverEl()?.nativeElement
    const width = popoverWidth(bodyRect)
    const size = {
      width: width || element?.offsetWidth || POPOVER_MAX_WIDTH,
      height: element?.offsetHeight || POPOVER_ESTIMATED_HEIGHT,
    }
    const anchor = this.selectionAnchor(body)
    let next: PopoverState
    if (!anchor) {
      // Nothing to measure (no layout yet): the popover waits at the top of what is shown.
      next = {
        top: body.scrollTop + POPOVER_MARGIN,
        left: body.scrollLeft + POPOVER_MARGIN,
        width,
        placement: 'below',
        hidden: false,
        caret: CARET_INSET,
      }
    } else {
      const position = computePopoverPosition(anchor, bodyRect, size, POPOVER_GAP)
      const area = this.visibleArea(bodyRect)
      const hidden = position.hidden || anchor.bottom < area.top || anchor.top > area.bottom
      const caretMax = Math.max(CARET_INSET, size.width - CARET_INSET)
      const caret = Math.min(Math.max(anchor.left - bodyRect.left - position.left, CARET_INSET), caretMax)
      next = {
        top: position.top + body.scrollTop,
        left: position.left + body.scrollLeft,
        width,
        placement: position.placement,
        hidden,
        caret,
      }
    }
    const current = this.popover()
    if (!current || (Object.keys(next) as (keyof PopoverState)[]).some((key) => current[key] !== next[key])) {
      this.popover.set(next)
    }
  }

  /** The textarea has no highlight of its own: this mirror layer, behind it, marks the passage in the same text. */
  protected readonly mirrorParts = computed(() => {
    const frozen = this.frozen()
    if (!frozen || !this.textEditor()) return null
    const draft = this.draft()
    const found = locatePassage(draft, frozen.from, frozen.to, [frozen.text])
    return found
      ? { before: draft.slice(0, found.from), mid: draft.slice(found.from, found.to), after: draft.slice(found.to) }
      : null
  })

  protected onEditorScroll(): void {
    const mirror = this.mirror()?.nativeElement
    const editor = this.editor()?.nativeElement
    if (mirror && editor) mirror.scrollTop = editor.scrollTop
    this.schedulePosition()
  }
}

/** The `::highlight()` rule must be global: the highlighted text nodes carry no encapsulation attribute. */
function ensureHighlightStyle(): void {
  if (typeof document === 'undefined' || document.getElementById(HIGHLIGHT_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = HIGHLIGHT_STYLE_ID
  style.textContent =
    `::highlight(${HIGHLIGHT_NAME}){background-color:#ffd84d;color:#1d1d1f}` +
    `mark.${HIGHLIGHT_NAME}{background-color:#ffd84d;color:#1d1d1f}`
  document.head.appendChild(style)
}
