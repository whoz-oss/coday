import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core'

/**
 * Avatar — shows an image when one is available, the initials otherwise.
 *
 * The component fills its container (the consumer owns size, border and radius) and falls back to the
 * initials when `src` is empty or the image fails to load (404, offline, blocked).
 *
 * @example
 * <ds-avatar [src]="avatarUrl()" initials="VP" />
 */
@Component({
  selector: 'ds-avatar',
  template: `
    @if (showImage()) {
      <img [src]="src()" alt="" referrerpolicy="no-referrer" (error)="failedSrc.set(src())" />
    } @else {
      {{ initials() }}
    }
  `,
  styleUrl: './avatar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AvatarComponent {
  /** Image URL. When null or empty, the initials are shown. */
  readonly src = input<string | null>(null)
  /** Text shown when there is no image, or when it cannot be loaded. */
  readonly initials = input<string>('')

  /** The URL that failed to load: a new `src` gets a fresh chance. */
  protected readonly failedSrc = signal<string | null>(null)

  protected readonly showImage = computed(() => {
    const src = this.src()
    return !!src && src !== this.failedSrc()
  })
}
