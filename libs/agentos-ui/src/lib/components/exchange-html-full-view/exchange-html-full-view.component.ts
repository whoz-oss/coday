import { ChangeDetectionStrategy, Component, inject, ViewEncapsulation } from '@angular/core'
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog'
import { DomSanitizer } from '@angular/platform-browser'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { ExchangeHtmlPreviewService } from '../../services/exchange-html-preview.service'
import { trustSandboxedSrcdoc } from '../../services/exchange-html-srcdoc'

export interface ExchangeHtmlFullViewData {
  content: string
  filename: string
}

/** Value the dialog closes with when the user asked to download the file. */
export const EXCHANGE_HTML_FULL_VIEW_DOWNLOAD = 'download'

/**
 * ExchangeHtmlFullViewComponent — full-viewport view of an HTML file, opened through MatDialog.
 *
 * A dialog (an overlay attached to the body) is used rather than a `position: fixed` element inside the files
 * drawer, because the transforms of mat-sidenav break fixed positioning. Escape closes it and focus returns to
 * the element that opened it (MatDialog defaults).
 *
 * The iframe is sandboxed without `allow-same-origin` (see `trustSandboxedSrcdoc`). Download is delegated back
 * to the opener through the close result, so the download logic stays in one place.
 */
@Component({
  selector: 'agentos-exchange-html-full-view',
  standalone: true,
  imports: [IconButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './exchange-html-full-view.component.scss',
  template: `
    <header class="exchange-html-full-view__header">
      <span class="exchange-html-full-view__filename" [title]="data.filename">{{ data.filename }}</span>
      <ds-icon-button icon="open_in_new" variant="default" title="Open in new tab" (action)="openInNewTab()" />
      <ds-icon-button icon="download" variant="default" [title]="'Download ' + data.filename" (action)="download()" />
      <ds-icon-button icon="close" variant="default" title="Close full view" (action)="close()" />
    </header>
    <!-- Static sandbox: Angular forbids a dynamic binding on it. NEVER add allow-same-origin or allow-top-navigation. -->
    <iframe
      class="exchange-html-full-view__iframe"
      [srcdoc]="srcdoc"
      [title]="'Full view of ' + data.filename"
      sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"
    ></iframe>
  `,
})
export class ExchangeHtmlFullViewComponent {
  protected readonly data = inject<ExchangeHtmlFullViewData>(MAT_DIALOG_DATA)
  private readonly dialogRef = inject(MatDialogRef<ExchangeHtmlFullViewComponent, string>)
  private readonly preview = inject(ExchangeHtmlPreviewService)

  protected readonly srcdoc = trustSandboxedSrcdoc(inject(DomSanitizer), this.data.content)

  protected openInNewTab(): void {
    this.preview.openInNewTab(this.data.content, this.data.filename)
  }

  protected download(): void {
    this.dialogRef.close(EXCHANGE_HTML_FULL_VIEW_DOWNLOAD)
  }

  protected close(): void {
    this.dialogRef.close()
  }
}
