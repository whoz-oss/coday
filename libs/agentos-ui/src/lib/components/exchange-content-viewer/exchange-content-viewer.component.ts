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
import { DomSanitizer } from '@angular/platform-browser'
import { fromEvent } from 'rxjs'
import {
  ConfirmDialogComponent,
  EmptyStateComponent,
  IconButtonComponent,
  SpinnerComponent,
} from '@whoz-oss/design-system'
import { MarkdownPipe } from '../../pipes/markdown.pipe'
import { detectFormat, isEditableFormat, reindentJson, selectionLineRange } from '../../services/exchange-content.utils'
import { injectCopyFeedback } from '../../services/clipboard.utils'
import { ExchangeHtmlPreviewService } from '../../services/exchange-html-preview.service'
import {
  EXCHANGE_SELECTION_MESSAGE_TYPE,
  trustPdfBlobUrl,
  trustSandboxedSrcdoc,
  withSelectionBridge,
} from '../../services/exchange-html-srcdoc'
import { ExchangeFileRef, ExchangeSaveStatus, ExchangeSelectionAction } from '../../services/exchange-state.service'
import {
  EXCHANGE_FULL_VIEW_PANEL_CLASS,
  EXCHANGE_HTML_FULL_VIEW_DOWNLOAD,
  ExchangeHtmlFullViewComponent,
  ExchangeHtmlFullViewData,
} from '../exchange-html-full-view/exchange-html-full-view.component'

/** Longest selection taken from the HTML preview, whatever the page posts. */
const MAX_PREVIEW_SELECTION = 100_000

/** Which side of a markdown / HTML file is shown while it is editable. */
type ViewMode = 'source' | 'preview'

/** A non-empty selection of the document, with its position when it comes from the editor. */
interface ViewerSelection {
  text: string
  startLine?: number
  endLine?: number
}

/**
 * ExchangeContentViewerComponent — presentational preview and editor pane for a single file.
 *
 * Renders markdown via the `agentosMarkdown` pipe, JSON re-indented, YAML/text in a <pre>,
 * and HTML in a sandboxed <iframe> (with a title — fixing the legacy a11y gap) that runs the file's scripts in
 * an opaque origin (see `trustSandboxedSrcdoc`), with a full-viewport view and an "open in new tab" action. Non-viewable
 * files (binary or > 20 MB) and load errors show a "Download instead" CTA. Images and PDFs are shown from an
 * object URL prepared by the parent (`blobUrl`, with its `blob`): an image is scaled to fit the pane, a PDF is
 * rendered by the browser's PDF viewer in an iframe. If the browser cannot decode an image, the same "Preview not
 * available" state is shown rather than a broken-image icon.
 *
 * A text file the parent allows to write (`canEdit`) opens directly in a plain textarea on its RAW content
 * (not the re-indented JSON), saved through `saveRequested`. Markdown and HTML also have a rendered view,
 * switched with a Source / Preview toggle (Source by default when editable; the preview shows the draft).
 * Nothing is written here: the parent saves and reports back through `saveStatus` / `saveError` / `content`.
 *
 * A selection (in the editor, in a markdown / text preview, or in the HTML preview through a postMessage
 * bridge — the sandboxed iframe cannot be read directly) offers to run an agent on it:
 * `selectionActionRequested` carries the selection, its lines when known, and the user's instruction.
 *
 * I/O in signals (decision #8). The smart parent owns content loading, saving and the canView decision.
 */
@Component({
  selector: 'agentos-exchange-content-viewer',
  standalone: true,
  imports: [MarkdownPipe, SpinnerComponent, EmptyStateComponent, IconButtonComponent, ConfirmDialogComponent],
  templateUrl: './exchange-content-viewer.component.html',
  styleUrl: './exchange-content-viewer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:selectionchange)': 'onDocumentSelectionChange()',
    // Also on mouse / key release, so the action bar does not wait for the next selectionchange.
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

  readonly closeRequested = output<void>()
  readonly downloadRequested = output<ExchangeFileRef>()
  /** The edited text to write to the file. */
  readonly saveRequested = output<{ ref: ExchangeFileRef; content: string }>()
  /** The file changed since it was opened and the user chose to load its latest version. */
  readonly reloadRequested = output<void>()
  /** The user asked an agent to act on a selection. */
  readonly selectionActionRequested = output<ExchangeSelectionAction>()
  /** Whether the editor holds changes that are not saved: the parent must not replace the content then. */
  readonly dirtyChanged = output<boolean>()

  private readonly injector = inject(Injector)
  // read:ElementRef so the host element (not the ds-icon-button instance) is returned.
  private readonly backButton = viewChild('backButton', { read: ElementRef })
  private readonly editor = viewChild<ElementRef<HTMLTextAreaElement>>('editor')
  private readonly previewFrame = viewChild<ElementRef<HTMLIFrameElement>>('previewFrame')
  private readonly body = viewChild<ElementRef<HTMLElement>>('body')
  private readonly instructionInput = viewChild<ElementRef<HTMLTextAreaElement>>('instructionInput')

  /** The text being edited, loaded from the raw content. */
  protected readonly draft = signal('')
  /** The side chosen with the toggle; null follows the default (source when editable, preview otherwise). */
  private readonly viewChoice = signal<ViewMode | null>(null)
  /** Selection currently held in the document, null when there is none. */
  protected readonly selection = signal<ViewerSelection | null>(null)
  /** Selection captured when the action form was opened: the focus move clears the live one. */
  private readonly actionSelection = signal<(ViewerSelection & { unsaved: boolean }) | null>(null)
  protected readonly actionOpen = computed(() => this.actionSelection() !== null)
  protected readonly instruction = signal('')
  /** Whether the confirmation to leave the file with unsaved changes is showing. */
  protected readonly pendingDiscard = signal(false)

  constructor() {
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

    // The editor is loaded from the raw content. A different file starts from a clean view (no selection,
    // half-typed instruction or chosen side). New content of the same file replaces the draft, unless the
    // user edited it: a dirty draft is kept, and saving it is refused as stale by the parent.
    let previous: { key: string; content: string | null } | null = null
    effect(() => {
      const key = this.fileKey()
      const content = this.content()
      untracked(() => {
        if (!previous || previous.key !== key) {
          this.draft.set(content ?? '')
          this.viewChoice.set(null)
          this.selection.set(null)
          this.actionSelection.set(null)
          this.instruction.set('')
          this.pendingDiscard.set(false)
        } else if (content !== previous.content && this.draft() === (previous.content ?? '')) {
          this.draft.set(content ?? '')
        }
        previous = { key, content }
      })
    })

    // The HTML preview reports its selection by message: only its own window is believed.
    fromEvent<MessageEvent>(window, 'message')
      .pipe(takeUntilDestroyed())
      .subscribe((event) => this.onPreviewMessage(event))

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

  /**
   * The HTML file as the `srcdoc` of the sandboxed iframe, with the selection bridge. Trusting it is safe ONLY
   * because that iframe is sandboxed without `allow-same-origin` (static attribute in the template): see
   * `trustSandboxedSrcdoc`. It shows the draft when the file is editable, so that the preview follows the edits.
   */
  protected readonly htmlSrcdoc = computed(() =>
    trustSandboxedSrcdoc(this.sanitizer, withSelectionBridge(this.previewSource()))
  )

  /** What the rendered views show: the edited draft when the file is editable, else the loaded content. */
  protected readonly previewSource = computed(() => {
    const raw = this.editable() ? this.draft() : (this.content() ?? '')
    return this.format() === 'json' ? reindentJson(raw) : raw
  })

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

  /** Markdown and HTML have a rendered view besides their source. */
  protected readonly hasRenderedView = computed(() => this.format() === 'markdown' || this.format() === 'html')

  protected readonly viewMode = computed<ViewMode>(() => this.viewChoice() ?? (this.editable() ? 'source' : 'preview'))
  protected readonly showEditor = computed(
    () => this.editable() && (!this.hasRenderedView() || this.viewMode() === 'source')
  )

  protected readonly dirty = computed(() => this.editable() && this.draft() !== (this.content() ?? ''))
  protected readonly saving = computed(() => this.saveStatus() === 'saving')
  protected readonly canSave = computed(() => this.dirty() && !this.saving())
  protected readonly hasSaveProblem = computed(() => this.saveStatus() === 'error' || this.saveStatus() === 'conflict')

  // ── Navigation / download ──────────────────────────────────────────────────────────────
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

  // ── Editing ──────────────────────────────────────────────────────────────────
  protected onDraftInput(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value)
    this.captureEditorSelection()
  }

  protected onEditorKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault()
      this.onSave()
    } else if (event.key === 'Escape') {
      // Escape never closes the panel around unsaved edits.
      event.stopPropagation()
    }
  }

  protected onSave(): void {
    if (!this.canSave()) return
    this.saveRequested.emit({ ref: this.file(), content: this.draft() })
  }

  /** Restores the loaded content in the editor. */
  protected onRevert(): void {
    this.draft.set(this.content() ?? '')
    this.selection.set(null)
  }

  protected onSetView(mode: ViewMode): void {
    this.viewChoice.set(mode)
    this.selection.set(null)
  }

  protected onConfirmDiscard(): void {
    this.pendingDiscard.set(false)
    this.draft.set(this.content() ?? '')
    this.closeRequested.emit()
  }

  protected onKeepEditing(): void {
    this.pendingDiscard.set(false)
  }

  /** Drops the stale draft, to see the file as the server has it. */
  protected onReload(): void {
    this.draft.set(this.content() ?? '')
    this.selection.set(null)
    this.reloadRequested.emit()
  }

  // ── Selection → agent ────────────────────────────────────────────────────────────────
  /** Selection made in the editor (textarea offsets are exact, so the lines are known). */
  protected captureEditorSelection(): void {
    const element = this.editor()?.nativeElement
    if (!element || this.actionOpen()) return
    const { selectionStart, selectionEnd, value } = element
    const text = value.slice(selectionStart, selectionEnd)
    if (!text.trim()) {
      if (this.selection()) this.selection.set(null)
      return
    }
    this.selection.set({ text, ...selectionLineRange(value, selectionStart, selectionEnd) })
  }

  /** Selection made in a markdown / text preview, when it lies within the viewer body. */
  protected onDocumentSelectionChange(): void {
    if (this.actionOpen()) return
    if (this.showEditor()) {
      this.captureEditorSelection()
      return
    }
    // The HTML preview is a sandboxed iframe: its selection arrives through onPreviewMessage.
    if (this.format() === 'html') return
    const selection = window.getSelection()
    const text = selection?.toString() ?? ''
    const body = this.body()?.nativeElement
    const anchor = selection?.rangeCount ? selection.getRangeAt(0).commonAncestorContainer : null
    const next = text.trim() && body && anchor && body.contains(anchor) ? { text } : null
    // selectionchange fires for the whole document (the composer included): ignore what did not change.
    if (next?.text !== this.selection()?.text) this.selection.set(next)
  }

  /**
   * Selection reported by the bridge of the HTML preview (see `withSelectionBridge`). Only a message from the
   * preview's own window, with the expected shape, counts; an empty text clears the selection, except while the
   * instruction form is open (focusing its input collapses the iframe selection).
   */
  private onPreviewMessage(event: MessageEvent): void {
    const frame = this.previewFrame()?.nativeElement
    if (!frame || event.source !== frame.contentWindow) return
    const data = event.data as { type?: unknown; text?: unknown } | null
    if (data?.type !== EXCHANGE_SELECTION_MESSAGE_TYPE || typeof data.text !== 'string') return
    if (this.actionOpen()) return
    const text = data.text.trim().slice(0, MAX_PREVIEW_SELECTION)
    this.selection.set(text ? { text } : null)
  }

  protected openAction(): void {
    const selection = this.selection()
    if (!selection) return
    this.actionSelection.set({ ...selection, unsaved: this.dirty() })
    this.instruction.set('')
    afterNextRender(() => this.instructionInput()?.nativeElement.focus(), { injector: this.injector })
  }

  protected onInstructionInput(event: Event): void {
    this.instruction.set((event.target as HTMLTextAreaElement).value)
  }

  protected onInstructionKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault()
      this.runAction()
    } else if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      this.closeAction()
    }
  }

  protected runAction(): void {
    const selection = this.actionSelection()
    const instruction = this.instruction().trim()
    if (!selection || !instruction) return
    this.selectionActionRequested.emit({
      ref: this.file(),
      selection: selection.text,
      ...(selection.startLine !== undefined && selection.endLine !== undefined
        ? { startLine: selection.startLine, endLine: selection.endLine }
        : {}),
      instruction,
      unsavedChanges: selection.unsaved,
    })
    this.closeAction()
  }

  protected closeAction(): void {
    this.actionSelection.set(null)
    this.instruction.set('')
    this.selection.set(null)
  }
}
