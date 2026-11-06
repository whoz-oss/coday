import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import {
  ConfigurationEntry,
  ConfigurationEntrySourceEnum,
  EffectiveConfigurationControllerService,
} from '@whoz-oss/agentos-api-client'
import { ButtonDirective, SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, EMPTY, switchMap } from 'rxjs'
import { ConfigurationDisplay, describeConfigurationEntry } from '../../utils/configuration-entry'
import { createRowFilter } from '../../utils/filter-rows'
import { TableFilterComponent } from '../table-filter/table-filter.component'

interface ConfigurationRow {
  key: string
  entry: ConfigurationEntry
  display: ConfigurationDisplay
}

type SourceFilter = 'ALL' | ConfigurationEntrySourceEnum
type StatusFilter = 'ALL' | 'SET' | 'UNSET'

/**
 * PlatformConfigurationComponent — read-only view of the effective configuration of the running service.
 *
 * Loaded at /agentos/admin/configuration. Super-admins only (the backend answers 403 otherwise).
 *
 * Lists the OS environment variables and the Spring properties the service knows about, with their
 * effective value, so an administrator can tell what is on, off or unset. Secrets are masked by the
 * server: the page only learns whether one is set.
 */
@Component({
  selector: 'agentos-platform-configuration',
  imports: [ButtonDirective, SectionHeaderComponent, TableDirective, TagComponent, TableFilterComponent],
  templateUrl: './platform-configuration.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformConfigurationComponent {
  private readonly destroyRef = inject(DestroyRef)
  private readonly configurationController = inject(EffectiveConfigurationControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)
  protected readonly loadError = signal(false)
  protected readonly rows = signal<ConfigurationRow[]>([])
  protected readonly source = signal<SourceFilter>('ALL')
  protected readonly status = signal<StatusFilter>('ALL')
  protected readonly rowFilter = createRowFilter(this.rows, (row) =>
    [row.entry.key, row.entry.description, row.entry.value].filter(Boolean).join(' ')
  )

  /** The text filter, then the source and status selects. */
  protected readonly visibleRows = computed(() =>
    this.rowFilter.filteredRows().filter((row) => {
      const source = this.source()
      const status = this.status()
      if (source !== 'ALL' && row.entry.source !== source) return false
      if (status === 'SET') return row.entry.configured
      if (status === 'UNSET') return !row.entry.configured
      return true
    })
  )

  protected readonly Source = ConfigurationEntrySourceEnum

  constructor() {
    this.refresh$
      .pipe(
        switchMap(() => {
          this.isLoading.set(true)
          this.loadError.set(false)
          // Caught inside the switchMap: an error reaching the outer stream would end it, and Refresh would
          // then do nothing.
          return this.configurationController.listEffectiveConfiguration().pipe(
            catchError(() => {
              this.loadError.set(true)
              this.isLoading.set(false)
              return EMPTY
            })
          )
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((entries) => {
        this.rows.set(
          entries.map((entry) => ({
            key: `${entry.source}:${entry.key}`,
            entry,
            display: describeConfigurationEntry(entry),
          }))
        )
        this.isLoading.set(false)
      })
  }

  protected reload(): void {
    this.refresh$.next()
  }
}
