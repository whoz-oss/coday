import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  input,
  output,
  viewChild,
} from '@angular/core'
import { MatIcon } from '@angular/material/icon'

/**
 * ListFooterComponent — "Load more" control shared by the cursor-paged lists: a count / loading /
 * error status line and the button. The status line is always rendered (it is a live region); the
 * button is hidden during the initial load and when there is nothing more to load, and a failed
 * initial page still shows it as a retry. A button that had focus when it disappears or is disabled
 * hands the focus to the status line, so keyboard users do not fall back to the page start.
 */
@Component({
  selector: 'agentos-list-footer',
  imports: [MatIcon],
  templateUrl: './list-footer.component.html',
  styleUrl: './list-footer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ListFooterComponent {
  readonly hasMore = input.required<boolean>()
  readonly loading = input.required<boolean>()
  readonly error = input.required<boolean>()
  /** Server-loaded rows so far, compared to [total]. */
  readonly loaded = input.required<number>()
  readonly total = input.required<number>()

  readonly loadMoreRequested = output<void>()

  private readonly status = viewChild.required<ElementRef<HTMLElement>>('status')
  private readonly button = viewChild<ElementRef<HTMLButtonElement>>('button')

  protected readonly showButton = computed(
    () => (this.hasMore() || this.error()) && !(this.loading() && this.loaded() === 0)
  )

  constructor() {
    // Runs before the template refresh, while the button is still in the DOM.
    effect(() => {
      if (this.showButton() && !this.loading()) return
      const button = this.button()?.nativeElement
      if (button?.matches(':focus')) this.status().nativeElement.focus()
    })
  }
}
