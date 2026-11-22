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
import { MatDialog } from '@angular/material/dialog'
import { DomSanitizer } from '@angular/platform-browser'
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
import { trustPdfBlobUrl, trustSandboxedSrcdoc } from '../../services/exchange-html-srcdoc'
import { ExchangeFileRef, ExchangeSaveStatus, ExchangeSelectionAction } from '../../services/exchange-state.service'
import {
  EXCHANGE_FULL_VIEW_PANEL_CLASS,
  EXCHANGE_HTML_FULL_VIEW_DOWNLOAD,
  ExchangeHtmlFullViewComponent,
  ExchangeHtmlFullViewData,
} from '../exchange-html-full-view/exchange-html-full-view.component'

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
 * A text file can be edited in place when the parent allows it (`canEdit`): a plain textarea on the RAW
 * content (not the re-indented JSON), saved through `saveRequested`. Nothing is written here: the parent
 * saves and reports back through `saveStatus` / `saveError` / `content`.
 *
 * A selection (in the editor, or in a markdown / text preview — the sandboxed HTML iframe cannot be read)
 * offers to run an agent on it: `selectionActionRequested` carries the selection, its lines when known, and
 * the user's instruction.
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
  host: { '(document:selectionchange)': 'onDocumentSelectionChange()' },
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
  private readonly body = viewChild<ElementRef<HTMLElement>>('body')
  private readonly instructionInput = viewChild<ElementRef<HTMLTextAreaElement>>('instructionInput')

  protected readonly editing = signal(false)
  protected readonly draft = signal('')
  /** Selection currently held in the document, null when there is none. */
  protected readonly selection = signal<ViewerSelection | null>(null)
  /** Selection captured when the action form was opened: the focus move clears the live one. */
  private readonly actionSelection = signal<(ViewerSelection & { unsaved: boolean }) | null>(null)
  protected readonly actionOpen = computed(() => this.actionSelection() !== null)
  protected readonly instruction = signal('')
  /** What a pending confirmation would discard: leaving the file, or only the edit mode. */
  protected readonly pendingDiscard = signal<'close' | 'cancel' | null>(null)

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

    // A different file starts from a clean view: no edit mode, selection or half-typed instruction.
    effect(() => {
      this.fileKey()
      untracked(() => {
        this.editing.set(false)
        this.draft.set('')
        this.selection.set(null)
        this.actionSelection.set(null)
        this.instruction.set('')
        this.pendingDiscard.set(null)
      })
    })

    // The edit mode ends once the content the parent holds is the text being edited: the save went
    // through. Content that moved under an untouched editor (the file was reloaded) is followed
    // rather than read as a change of the user's; a dirty editor keeps its text, and saving it is
    // refused as stale.
    let previousContent = this.content()
    effect(() => {
      const current = this.content()
      untracked(() => {
        if (this.editing() && current !== previousContent) {
          if (this.draft() === (current ?? '')) {
            this.leaveEditMode()
          } else if (this.draft() === (previousContent ?? '')) {
            this.draft.set(current ?? '')
          }
        }
        previousContent = current
      })
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

  /**
   * The HTML file as the `srcdoc` of the sandboxed iframe. Trusting it is safe ONLY because that iframe is
   * sandboxed without `allow-same-origin` (static attribute in the template): see `trustSandboxedSrcdoc`.
   */
  protected readonly htmlSrcdoc = computed(() => trustSandboxedSrcdoc(this.sanitizer, this.displayContent()))

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

  /** The edit entry point is offered on a loaded text file the parent allows to write. */
  protected readonly editable = computed(
    () => this.canEdit() && this.canView() && this.status() === 'ready' && isEditableFormat(this.filename())
  )

  protected readonly dirty = computed(() => this.editing() && this.draft() !== (this.content() ?? ''))
  protected readonly saving = computed(() => this.saveStatus() === 'saving')
  protected readonly canSave = computed(() => this.editing() && this.dirty() && !this.saving())
  protected readonly hasSaveProblem = computed(() => this.saveStatus() === 'error' || this.saveStatus() === 'conflict')

  // ── Navigation / download ──────────────────────────────────────────────────────────────
  protected onClose(): void {
    if (this.dirty()) {
      this.pendingDiscard.set('close')
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
  protected onEdit(): void {
    // The editor starts from the RAW content, not the re-indented JSON shown by the preview.
    this.draft.set(this.content() ?? '')
    this.editing.set(true)
    this.selection.set(null)
    afterNextRender(() => this.editor()?.nativeElement.focus(), { injector: this.injector })
  }

  protected onDraftInput(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value)
    this.captureEditorSelection()
  }

  protected onEditorKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault()
      this.onSave()
    } else if (event.key === 'Escape') {
      // Escape never drops edits silently, nor closes the panel around them: Cancel asks first.
      event.stopPropagation()
    }
  }

  protected onSave(): void {
    if (!this.canSave()) return
    this.saveRequested.emit({ ref: this.file(), content: this.draft() })
  }

  protected onCancelEdit(): void {
    if (this.dirty()) {
      this.pendingDiscard.set('cancel')
      return
    }
    this.leaveEditMode()
  }

  protected onConfirmDiscard(): void {
    const pending = this.pendingDiscard()
    this.pendingDiscard.set(null)
    this.leaveEditMode()
    if (pending === 'close') this.closeRequested.emit()
  }

  protected onKeepEditing(): void {
    this.pendingDiscard.set(null)
  }

  /** Leave the edit mode with the stale draft dropped, to see the file as the server has it. */
  protected onReload(): void {
    this.leaveEditMode()
    this.reloadRequested.emit()
  }

  private leaveEditMode(): void {
    this.editing.set(false)
    this.selection.set(null)
  }

  // ── Selection → agent ────────────────────────────────────────────────────────────────
  /** Selection made in the editor (textarea offsets are exact, so the lines are known). */
  protected captureEditorSelection(): void {
    const element = this.editor()?.nativeElement
    if (!element || this.actionOpen()) return
    const { selectionStart, selectionEnd, value } = element
    const text = value.slice(selectionStart, selectionEnd)
    if (!text.trim()) {
      this.selection.set(null)
      return
    }
    this.selection.set({ text, ...selectionLineRange(value, selectionStart, selectionEnd) })
  }

  /** Selection made in a markdown / text preview, when it lies within the viewer body. */
  protected onDocumentSelectionChange(): void {
    if (this.editing() || this.actionOpen()) return
    const selection = window.getSelection()
    const text = selection?.toString() ?? ''
    const body = this.body()?.nativeElement
    const anchor = selection?.rangeCount ? selection.getRangeAt(0).commonAncestorContainer : null
    const next = text.trim() && body && anchor && body.contains(anchor) ? { text } : null
    // selectionchange fires for the whole document (the composer included): ignore what did not change.
    if (next?.text !== this.selection()?.text) this.selection.set(next)
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
