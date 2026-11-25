import { ChangeDetectionStrategy, Component, inject, ViewEncapsulation } from '@angular/core'
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog'
import { DomSanitizer } from '@angular/platform-browser'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { ExchangeHtmlPreviewService } from '../../services/exchange-html-preview.service'
import { trustPdfBlobUrl, trustSandboxedSrcdoc } from '../../services/exchange-html-srcdoc'

/** An HTML file, rendered in the sandboxed srcdoc iframe. */
export interface ExchangeHtmlFullViewHtmlData {
  kind: 'html'
  content: string
  filename: string
}

/** A PDF, rendered by the browser's PDF viewer from the object URL the shell owns (the blob serves "new tab"). */
export interface ExchangeHtmlFullViewPdfData {
  kind: 'pdf'
  url: string
  blob: Blob
  filename: string
}

export type ExchangeHtmlFullViewData = ExchangeHtmlFullViewHtmlData | ExchangeHtmlFullViewPdfData

/** Value the dialog closes with when the user asked to download the file. */
export const EXCHANGE_HTML_FULL_VIEW_DOWNLOAD = 'download'

/** Class of the overlay pane of the dialog, which the (global) styles of this component are all scoped under. */
export const EXCHANGE_FULL_VIEW_PANEL_CLASS = 'exchange-html-full-view-panel'

/**
 * ExchangeHtmlFullViewComponent — full-viewport view of an HTML file or a PDF, opened through MatDialog.
 *
 * A dialog (an overlay attached to the body) is used rather than a `position: fixed` element inside the files
 * drawer, because the transforms of mat-sidenav break fixed positioning. Escape closes it and focus returns to
 * the element that opened it (MatDialog defaults).
 *
 * HTML: the iframe is sandboxed without `allow-same-origin` (see `trustSandboxedSrcdoc`). PDF: no sandbox, because
 * browsers refuse to run their PDF viewer in a sandboxed frame (see `trustPdfBlobUrl` for why that is acceptable).
 * Download is delegated back to the opener through the close result, so the download logic stays in one place.
 */
@Component({
  selector: 'agentos-exchange-html-full-view',
  standalone: true,
  imports: [IconButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // The dialog lives in the overlay, outside any view: encapsulated styles would never match. The stylesheet scopes
  // every selector under the panel class instead.
  encapsulation: ViewEncapsulation.None,
  styleUrl: './exchange-html-full-view.component.scss',
  template: `
    <header class="exchange-html-full-view__header">
      <span class="exchange-html-full-view__filename" [title]="data.filename">{{ data.filename }}</span>
      <ds-icon-button icon="open_in_new" variant="default" title="Open in new tab" (action)="openInNewTab()" />
      <ds-icon-button icon="download" variant="default" [title]="'Download ' + data.filename" (action)="download()" />
      <ds-icon-button icon="close" variant="default" title="Close full view" (action)="close()" />
    </header>
    @if (data.kind === 'pdf') {
      <!-- No sandbox on purpose: browsers do not run their PDF viewer in a sandboxed frame. Blob URL of a PDF only. -->
      @if (pdfSrc; as src) {
        <iframe class="exchange-html-full-view__iframe" [src]="src" [title]="'Full view of ' + data.filename"></iframe>
      }
    } @else {
      <!-- Static sandbox: Angular forbids a dynamic binding on it. NEVER add allow-same-origin or allow-top-navigation. -->
      <iframe
        class="exchange-html-full-view__iframe"
        [srcdoc]="srcdoc"
        [title]="'Full view of ' + data.filename"
        sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"
      ></iframe>
    }
  `,
})
export class ExchangeHtmlFullViewComponent {
  protected readonly data = inject<ExchangeHtmlFullViewData>(MAT_DIALOG_DATA)
  private readonly dialogRef = inject(MatDialogRef<ExchangeHtmlFullViewComponent, string>)
  private readonly preview = inject(ExchangeHtmlPreviewService)
  private readonly sanitizer = inject(DomSanitizer)

  /** Set for HTML only. */
  protected readonly srcdoc = this.data.kind === 'html' ? trustSandboxedSrcdoc(this.sanitizer, this.data.content) : null
  /** Set for PDF only. */
  protected readonly pdfSrc = this.data.kind === 'pdf' ? trustPdfBlobUrl(this.sanitizer, this.data.url) : null

  protected openInNewTab(): void {
    if (this.data.kind === 'pdf') this.preview.openBlobInNewTab(this.data.blob)
    else this.preview.openInNewTab(this.data.content, this.data.filename)
  }

  protected download(): void {
    this.dialogRef.close(EXCHANGE_HTML_FULL_VIEW_DOWNLOAD)
  }

  protected close(): void {
    this.dialogRef.close()
  }
}
