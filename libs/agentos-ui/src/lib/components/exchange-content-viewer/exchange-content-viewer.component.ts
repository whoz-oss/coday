import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
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
import { htmlToMarkdown } from '../../services/exchange-markdown-convert'
import { MarkdownRendererService } from '../../services/markdown-renderer.service'
import { locatePassage, rangeFromTextOffsets, rangeTextOffsets, squash } from '../../services/exchange-passage'
import { ExchangeSelectionRequest } from '../../services/exchange-selection-request.service'
import { ExchangeFileRef, ExchangeSaveStatus, ExchangeSelectionAction } from '../../services/exchange-state.service'
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
/** Largest edited document accepted from the HTML preview (the file endpoint refuses more anyway). */
const MAX_EDITED_HTML = 20 * 1024 * 1024

/** Name of the CSS Custom Highlight of the frozen selection, and class of its `<mark>` fallback. */
const HIGHLIGHT_NAME = 'agentos-selection'
const HIGHLIGHT_STYLE_ID = 'agentos-selection-highlight-style'

const PASSAGE_CHANGED = 'The passage changed, re-select it'

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
}

/** A selection frozen when the composer was opened. */
interface FrozenSelection extends ViewerSelection {
  unsaved: boolean
}

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
 * A selection offers a compact composer (same look as the chat composer, agent picked by typing `@`). The selection
 * is frozen and highlighted when the composer takes focus, and stays so, with a quote, until the agent's proposal
 * (the `request` input) is accepted or rejected, or the user dismisses it. The proposal is an annotation card with a
 * word diff; accepting replaces the passage in the draft, it never saves.
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
    // Also on mouse / key release, so the composer does not wait for the next selectionchange.
    '(mouseup)': 'onDocumentSelectionChange()',
    '(keyup)': 'onDocumentSelectionChange()',
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
  // read:ElementRef so the host element (not the ds-icon-button instance) is returned.
  private readonly backButton = viewChild('backButton', { read: ElementRef })
  private readonly editor = viewChild<ElementRef<HTMLTextAreaElement>>('editor')
  private readonly mirror = viewChild<ElementRef<HTMLElement>>('mirror')
  private readonly previewFrame = viewChild<ElementRef<HTMLIFrameElement>>('previewFrame')
  private readonly body = viewChild<ElementRef<HTMLElement>>('body')
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
  /** Selection captured when the composer took the focus: moving the focus clears the live one. */
  protected readonly frozen = signal<FrozenSelection | null>(null)
  protected readonly activeSelection = computed<ViewerSelection | null>(() => this.frozen() ?? this.selection())
  /** Text the composer reopens with ("Ask again"). */
  protected readonly prefill = signal('')
  /** Where the passage ends in the page of the HTML preview, as reported by its bridge. */
  private readonly frameAnchor = signal<number | null>(null)
  /** Offset of the annotation card in the body. */
  protected readonly anchorTop = signal<number | null>(null)
  /** Why the proposal cannot be applied, when it cannot. */
  protected readonly acceptError = signal<string | null>(null)
  /** Whether the confirmation to leave the file with unsaved changes is showing. */
  protected readonly pendingDiscard = signal(false)

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
          this.prefill.set('')
          this.acceptError.set(null)
          this.pendingDiscard.set(false)
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
          this.frozen.set({ text: request.selection, from: request.from, to: request.to, unsaved: false })
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
  protected onMarkdownInput(): void {
    if (this.editMode() === 'markdown') this.mdEdited.set(true)
  }

  protected onEditorKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault()
      void this.onSave()
    } else if (event.key === 'Escape') {
      // Escape never closes the panel around unsaved edits.
      event.stopPropagation()
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
    if (next?.text !== this.selection()?.text || next?.from !== this.selection()?.from) this.selection.set(next)
  }

  /**
   * Messages of the bridge of the HTML preview (see `withSelectionBridge`). Only a message from the preview's own
   * window, with the expected shape, counts. An empty selection clears the selection, except once it is frozen
   * (focusing the composer collapses the iframe selection).
   */
  private onPreviewMessage(event: MessageEvent): void {
    const frame = this.previewFrame()?.nativeElement
    if (!frame || event.source !== frame.contentWindow) return
    const data = event.data as { type?: unknown; text?: unknown; html?: unknown; ok?: unknown; bottom?: unknown } | null
    if (!data || typeof data.type !== 'string') return
    switch (data.type) {
      case EXCHANGE_SELECTION_MESSAGE_TYPE: {
        if (typeof data.text !== 'string' || this.frozen()) return
        const text = data.text.trim().slice(0, MAX_PREVIEW_SELECTION)
        this.selection.set(text ? { text, from: 0, to: 0 } : null)
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
      case EXCHANGE_ANCHOR_MESSAGE_TYPE:
        if (typeof data.bottom === 'number' && Number.isFinite(data.bottom)) {
          this.frameAnchor.set(data.bottom)
          this.measureAnchor()
        }
        return
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
  }

  protected onComposerSent(instruction: string): void {
    this.freeze()
    const selection = this.frozen()
    if (!selection) return
    this.prefill.set('')
    this.selectionActionRequested.emit({
      ref: this.file(),
      selection: selection.text,
      ...(selection.startLine !== undefined && selection.endLine !== undefined
        ? { startLine: selection.startLine, endLine: selection.endLine }
        : {}),
      from: selection.from,
      to: selection.to,
      instruction,
      unsavedChanges: selection.unsaved,
    })
  }

  /** Escape in the composer: nothing is asked, the selection and its highlight go. */
  protected closeComposer(): void {
    this.dismiss()
  }

  /** The user lets go of the selection (and of the request about it, if any). */
  protected dismiss(): void {
    const request = this.request()
    if (request) this.requestCleared.emit(request.id)
    this.frozen.set(null)
    this.selection.set(null)
    this.prefill.set('')
    this.acceptError.set(null)
  }

  /** Name of the agent the instruction mentions, for the waiting line. */
  protected readonly requestedAgent = computed(() => {
    const mention = /@([\w-]+)/.exec(this.request()?.instruction ?? '')
    return mention ? `@${mention[1]}` : 'the agent'
  })

  /** The dock (quote + composer or waiting line) shows with a selection or a request, on a loaded document. */
  protected readonly dockVisible = computed(
    () => this.status() === 'ready' && this.canView() && (this.activeSelection() !== null || this.request() !== null)
  )

  // ── Proposal (annotation card) ─────────────────────────────────────────────────────────────────────
  protected readonly cardVisible = computed(() => {
    const status = this.request()?.status
    return status === 'proposed' || status === 'answered' || status === 'failed'
  })

  /** Word-level diff of the selected passage against the proposal. */
  protected readonly proposalDiff = computed(() => {
    const request = this.request()
    return request?.status === 'proposed' ? diffWords(request.selection, request.proposal ?? '') : []
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
      // The bridge finds the passage again by its text, replaces it and reports whether it could.
      this.postToFrame({ type: EXCHANGE_REPLACE_MESSAGE_TYPE, from: text.trim(), to: proposal })
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
    // Rendered markdown: the passage is a range of the document, replaced by the proposal as text.
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
    range.deleteContents()
    range.insertNode(document.createTextNode(proposal))
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
    this.measureAnchor()
  }

  /** Where the annotation card goes: under the end of the highlighted passage. */
  private measureAnchor(): void {
    const body = this.body()?.nativeElement
    if (!body || !this.frozen()) {
      this.anchorTop.set(null)
      return
    }
    const bodyTop = body.getBoundingClientRect().top
    let bottom: number | null = null
    if (this.format() === 'html') {
      const frame = this.previewFrame()?.nativeElement
      const anchor = this.frameAnchor()
      if (frame && anchor !== null) bottom = frame.getBoundingClientRect().top + anchor
    } else {
      const mark = body.querySelector('mark.agentos-selection')
      const rect =
        mark?.getBoundingClientRect() ??
        (typeof this.highlightRange?.getBoundingClientRect === 'function'
          ? this.highlightRange.getBoundingClientRect()
          : null)
      if (rect && (rect.bottom || rect.top)) bottom = rect.bottom
    }
    this.anchorTop.set(bottom === null ? null : Math.max(0, bottom - bodyTop + body.scrollTop + 6))
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
    this.measureAnchor()
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
