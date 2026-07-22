import { Component, Input, signal } from '@angular/core'
import { IconButtonComponent } from '../icon-button/icon-button.component'

/**
 * DsCopyButton — copies a text string to the clipboard and shows a brief confirmation.
 *
 * Displays a `content_copy` icon button. On click, writes `text` to the clipboard
 * and switches the icon to `check` for 2 seconds as visual feedback.
 *
 * @example
 * <ds-copy-button text="Hello world" title="Copy" />
 */
@Component({
  selector: 'ds-copy-button',
  imports: [IconButtonComponent],
  templateUrl: './copy-button.component.html',
})
export class CopyButtonComponent {
  @Input({ required: true }) text!: string
  @Input() title: string = 'Copy'

  protected readonly copied = signal(false)
  private timeout: ReturnType<typeof setTimeout> | null = null

  protected copy(): void {
    navigator.clipboard
      .writeText(this.text)
      .then(() => {
        if (this.timeout) clearTimeout(this.timeout)
        this.copied.set(true)
        this.timeout = setTimeout(() => this.copied.set(false), 2000)
      })
      .catch((err) => {
        console.warn('[DsCopyButton] clipboard write failed', err)
      })
  }

  ngOnDestroy(): void {
    if (this.timeout) clearTimeout(this.timeout)
  }
}
