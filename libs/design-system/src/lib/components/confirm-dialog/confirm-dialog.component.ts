import { ChangeDetectionStrategy, Component, effect, ElementRef, input, output, viewChild } from '@angular/core'

/**
 * DsConfirmDialog — an accessible confirmation dialog to replace native confirm()/alert().
 *
 * Visibility is controlled externally via the `open` input. When confirmed or cancelled
 * the matching output fires — the host is responsible for closing the dialog (set open=false).
 *
 * Accessibility:
 *   - role="dialog" + aria-modal="true", labelled by the title, described by the message.
 *   - Esc cancels, Enter confirms (when focus is not on a button, which activate natively).
 *   - The confirm button receives focus when the dialog opens.
 *
 * CSS contract: --color-overlay, --color-bg-secondary, --color-border, --color-shadow,
 * --color-text, --color-text-secondary, --color-text-inverse, --color-primary,
 * --color-primary-hover, --color-bg-hover
 *
 * @example
 * <ds-confirm-dialog
 *   [open]="confirmOpen"
 *   title="Delete configuration"
 *   message="This action cannot be undone."
 *   confirmLabel="Delete"
 *   (confirmed)="onConfirm()"
 *   (cancelled)="confirmOpen = false"
 * />
 */
@Component({
  selector: 'ds-confirm-dialog',
  standalone: true,
  templateUrl: './confirm-dialog.component.html',
  styleUrl: './confirm-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialogComponent {
  /** Dialog heading. */
  readonly title = input<string>('')

  /** Body text describing the action being confirmed. */
  readonly message = input<string>('')

  /** Label of the primary (confirm) button. */
  readonly confirmLabel = input<string>('Confirm')

  /** Label of the secondary (cancel) button. */
  readonly cancelLabel = input<string>('Cancel')

  /** Whether the dialog is visible. */
  readonly open = input<boolean>(false)

  /** Emitted when the user confirms the action. */
  readonly confirmed = output<void>()

  /** Emitted when the user cancels (button, Esc or backdrop click). */
  readonly cancelled = output<void>()

  private readonly confirmButton = viewChild<ElementRef<HTMLButtonElement>>('confirmButton')

  /** Stable id for the message paragraph, referenced by the dialog's aria-describedby. */
  protected readonly messageId = `ds-confirm-dialog-message-${crypto.randomUUID()}`

  constructor() {
    // Focus the confirm button once the dialog is open and the button has rendered (@if open).
    // The viewChild signal updates when the button appears, so this re-runs and focuses it.
    effect(() => {
      if (this.open()) {
        this.confirmButton()?.nativeElement.focus()
      }
    })
  }

  protected onConfirm(): void {
    this.confirmed.emit()
  }

  protected onCancel(): void {
    this.cancelled.emit()
  }

  protected onBackdropClick(event: MouseEvent): void {
    // Only a click on the backdrop itself dismisses; clicks bubbling up from the dialog do not.
    if (event.target === event.currentTarget) {
      this.onCancel()
    }
  }

  protected onEnter(event: Event): void {
    // Buttons activate on Enter natively — only confirm when focus is elsewhere in the
    // dialog, so we never emit twice.
    if ((event.target as HTMLElement).tagName !== 'BUTTON') {
      this.onConfirm()
    }
  }
}
