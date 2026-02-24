import { Component, EventEmitter, Input, Output } from '@angular/core'
import { MatIconButton } from '@angular/material/button'
import { MatIcon } from '@angular/material/icon'

export type IconButtonVariant = 'default' | 'primary' | 'danger'

/**
 * WhozIconButton — a rounded Material icon button with variant support.
 *
 * Encapsulates mat-icon-button + mat-icon with design-system styling.
 * Contextual animations (pulse, gradient shift...) remain the host's responsibility.
 *
 * CSS contract: relies on --color-primary, --color-error, --color-text-secondary,
 * --color-text-inverse, --color-bg-hover from the host theme.
 *
 * @example
 * <whoz-icon-button icon="send" variant="primary" title="Send" (action)="send()" />
 * <whoz-icon-button icon="stop" variant="danger" title="Stop" (action)="stop()" />
 * <whoz-icon-button icon="mic" [disabled]="isRecording" (action)="toggle()" />
 */
@Component({
  selector: 'ds-icon-button',
  standalone: true,
  imports: [MatIconButton, MatIcon],
  templateUrl: './icon-button.component.html',
  styleUrl: './icon-button.component.scss',
})
export class IconButtonComponent {
  @Input({ required: true }) icon!: string
  @Input() title: string = ''
  @Input() disabled: boolean = false
  @Input() variant: IconButtonVariant = 'default'

  @Output() action = new EventEmitter<void>()

  protected onClick(): void {
    if (!this.disabled) {
      this.action.emit()
    }
  }
}
