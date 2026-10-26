import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog'

export interface MessageDetailDialogData {
  actorLabel?: string
  time: string
  messageText: string
}

@Component({
  selector: 'sf-message-detail-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>Agent message</h2>
    <mat-dialog-content>
      <p class="message-meta">
        @if (data.actorLabel) {
          <span>{{ data.actorLabel }}</span>
        }
        <time>{{ data.time }}</time>
      </p>
      @if (data.messageText) {
        <pre class="message-body">{{ data.messageText }}</pre>
      } @else {
        <p class="message-empty">No textual message content is available for this event.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
  styles: `
    .message-meta {
      display: flex;
      gap: 12px;
      margin: 0 0 16px;
      color: var(--sf-text-muted);
      font-family: var(--sf-mono);
      font-size: 12px;
    }
    .message-body {
      margin: 0;
      max-height: min(60vh, 640px);
      overflow: auto;
      color: var(--sf-text);
      font-family: var(--sf-mono);
      font-size: 13px;
      line-height: 1.55;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    .message-empty {
      color: var(--sf-text-muted);
      font-style: italic;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessageDetailDialogComponent {
  protected readonly data = inject<MessageDetailDialogData>(MAT_DIALOG_DATA)
}
