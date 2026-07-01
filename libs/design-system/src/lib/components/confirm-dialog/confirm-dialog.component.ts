import {
  AfterViewChecked,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core'

/**
 * DsConfirmDialog — an accessible confirmation dialog to replace native confirm()/alert().
 *
 * Visibility is controlled externally via the `open` input. When confirmed or cancelled
 * the matching output fires — the host is responsible for closing the dialog (set open=false).
 *
 * Accessibility:
 *   - role="dialog" + aria-modal="true", labelled by the title.
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
export class ConfirmDialogComponent implements OnChanges, AfterViewChecked {
  /** Dialog heading. */
  @Input() title: string = ''

  /** Body text describing the action being confirmed. */
  @Input() message: string = ''

  /** Label of the primary (confirm) button. */
  @Input() confirmLabel: string = 'Confirm'

  /** Label of the secondary (cancel) button. */
  @Input() cancelLabel: string = 'Cancel'

  /** Whether the dialog is visible. */
  @Input() open: boolean = false

  /** Emitted when the user confirms the action. */
  @Output() confirmed = new EventEmitter<void>()

  /** Emitted when the user cancels (button, Esc or backdrop click). */
  @Output() cancelled = new EventEmitter<void>()

  @ViewChild('confirmButton') private confirmButton?: ElementRef<HTMLButtonElement>

  private pendingFocus = false

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open) {
      // Defer the focus to ngAfterViewChecked: the button only exists once @if renders it.
      this.pendingFocus = true
    }
  }

  ngAfterViewChecked(): void {
    if (this.pendingFocus && this.confirmButton) {
      this.confirmButton.nativeElement.focus()
      this.pendingFocus = false
    }
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
