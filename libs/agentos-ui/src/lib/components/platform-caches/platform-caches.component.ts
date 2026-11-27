import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FilesystemCacheControllerService } from '@whoz-oss/agentos-api-client'
import { ButtonDirective, SectionHeaderComponent } from '@whoz-oss/design-system'

/**
 * PlatformCachesComponent — lets a super-admin make the service read the configuration files again.
 *
 * Loaded at /agentos/admin/caches. Super-admins only (the backend answers 403 otherwise).
 *
 * Agents, integrations, prompts, skills and AI models read from a namespace configPath are cached by the
 * server for 5 minutes. One call forgets all of those caches, so that an edited file is picked up on the
 * next read instead of after the expiry. Only the instance that receives the call is concerned.
 */
@Component({
  selector: 'agentos-platform-caches',
  imports: [ButtonDirective, SectionHeaderComponent],
  templateUrl: './platform-caches.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformCachesComponent {
  private readonly destroyRef = inject(DestroyRef)
  private readonly cacheController = inject(FilesystemCacheControllerService)

  protected readonly isClearing = signal(false)
  /** Directories forgotten by the last successful call; null until one succeeded. */
  protected readonly clearedCount = signal<number | null>(null)
  protected readonly error = signal(false)

  protected clear(): void {
    if (this.isClearing()) return
    this.isClearing.set(true)
    this.error.set(false)
    this.clearedCount.set(null)
    this.cacheController
      .clearFilesystemCache()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.clearedCount.set(result.clearedDirectories)
          this.isClearing.set(false)
        },
        error: () => {
          this.error.set(true)
          this.isClearing.set(false)
        },
      })
  }
}
