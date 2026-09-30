import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { UsageConfigurationControllerService } from '@whoz-oss/agentos-api-client'

type UsageConfigurationState = 'loading' | 'enabled' | 'disabled' | 'error'

/** Shares the startup setting across every usage view, including concurrent mounts. */
@Injectable({ providedIn: 'root' })
export class UsageConfigurationService {
  private readonly api = inject(UsageConfigurationControllerService)
  private readonly destroy = inject(DestroyRef)
  private readonly configurationState = signal<UsageConfigurationState>('loading')
  readonly state = this.configurationState.asReadonly()
  readonly enabled = computed(() => this.state() === 'enabled')

  constructor() {
    this.load()
  }

  retry(): void {
    // Successful startup settings stay fixed until the application is reloaded.
    if (this.state() !== 'error') return
    this.load()
  }

  private load(): void {
    this.configurationState.set('loading')
    this.api
      .getUsageConfiguration()
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (configuration) => {
          const enabled = configuration?.enabled
          this.configurationState.set(typeof enabled === 'boolean' ? (enabled ? 'enabled' : 'disabled') : 'error')
        },
        error: () => this.configurationState.set('error'),
      })
  }
}
