import {
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  OnDestroy,
  signal,
  ViewChild,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { forkJoin } from 'rxjs'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { ProjectApiService } from '../../core/services/project-api.service'

/** Runtime state for a single preview entry. */
interface EntryState {
  name: string
  command: string
  status: 'running' | 'stopped'
  isLoading: boolean
  errorMessage: string
}

@Component({
  selector: 'app-preview-panel',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './preview-panel.component.html',
  styleUrl: './preview-panel.component.scss',
})
export class PreviewPanelComponent implements OnDestroy {
  private readonly destroyRef = inject(DestroyRef)
  private readonly projectApi = inject(ProjectApiService)

  /** Project name passed from the sidenav. */
  readonly projectName = input.required<string>()

  /** Per-entry runtime state. */
  readonly entryStates = signal<EntryState[]>([])

  readonly hasPreview = computed(() => this.entryStates().length > 0)

  /** Which entry's logs are currently open (by name), or null. */
  readonly logsOpenFor = signal<string | null>(null)
  readonly logs = signal('')

  @ViewChild('logsContent') private logsContent?: ElementRef<HTMLPreElement>

  private logsPollTimer: ReturnType<typeof setInterval> | null = null
  private logsTimeoutTimer: ReturnType<typeof setTimeout> | null = null

  private static readonly LOGS_POLL_MS = 5_000
  private static readonly LOGS_TIMEOUT_MS = 20 * 60 * 1_000

  constructor() {
    effect(
      () => {
        const name = this.projectName()
        if (name) {
          this.onProjectChanged(name)
        }
      },
      { allowSignalWrites: true }
    )

    effect(() => {
      console.log('entry states', this.entryStates())
    })
  }

  ngOnDestroy(): void {
    this.stopLogPolling()
  }

  // ── Project change ──────────────────────────────────────────────────────────

  private onProjectChanged(projectName: string): void {
    this.stopLogPolling()
    this.entryStates.set([])
    this.logsOpenFor.set(null)
    this.logs.set('')

    this.projectApi
      .getPreviewEntries(projectName)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (resp) => {
          const entries = resp.entries
          if (entries.length === 0) {
            return
          }

          // Initialize all entries as stopped
          const states: EntryState[] = entries.map((e) => ({
            name: e.name,
            command: e.command,
            status: 'stopped' as const,
            isLoading: false,
            errorMessage: '',
          }))
          this.entryStates.set(states)

          // Fetch status for each entry in parallel
          const statusCalls = entries.map((e) => this.projectApi.getPreviewStatus(projectName, e.name))
          forkJoin(statusCalls)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
              next: (results) => {
                this.entryStates.update((current) =>
                  current.map((s, i) => {
                    const result = results[i]
                    if (!result) return s
                    return { ...s, status: result.status }
                  })
                )
              },
              error: () => {},
            })
        },
        error: () => {},
      })
  }

  // ── Actions ─────────────────────────────────────────────────────────────────

  start(entryName: string): void {
    this.updateEntry(entryName, { isLoading: true, errorMessage: '' })
    this.projectApi
      .startPreview(this.projectName(), entryName)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (resp) => {
          this.updateEntry(entryName, {
            status: resp.status,
            isLoading: false,
          })
        },
        error: (err) => {
          this.updateEntry(entryName, {
            errorMessage: err?.error?.error ?? 'Failed to start preview',
            isLoading: false,
          })
        },
      })
  }

  stop(entryName: string): void {
    this.updateEntry(entryName, { isLoading: true, errorMessage: '' })
    this.projectApi
      .stopPreview(this.projectName(), entryName)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.updateEntry(entryName, {
            status: 'stopped',
            isLoading: false,
          })
          if (this.logsOpenFor() === entryName) {
            this.stopLogPolling()
          }
        },
        error: (err) => {
          this.updateEntry(entryName, {
            errorMessage: err?.error?.error ?? 'Failed to stop preview',
            isLoading: false,
          })
        },
      })
  }

  // ── Logs ────────────────────────────────────────────────────────────────────

  toggleLogs(entryName: string): void {
    if (this.logsOpenFor() === entryName) {
      this.logsOpenFor.set(null)
      this.logs.set('')
      this.stopLogPolling()
    } else {
      this.logsOpenFor.set(entryName)
      this.refreshLogs()
      this.startLogPolling()
    }
  }

  onManualRefresh(): void {
    this.refreshLogs()
    if (this.logsPollTimer) {
      this.resetLogsTimeout()
    }
  }

  private refreshLogs(): void {
    const entryName = this.logsOpenFor()
    if (!entryName) return

    this.projectApi
      .getPreviewLogs(this.projectName(), entryName)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (resp) => {
          this.logs.set(resp.logs)
          this.scrollLogsToBottom()
        },
        error: () => this.logs.set('(failed to load logs)'),
      })
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private updateEntry(name: string, patch: Partial<EntryState>): void {
    this.entryStates.update((states) => states.map((s) => (s.name === name ? { ...s, ...patch } : s)))
  }

  private startLogPolling(): void {
    this.stopLogPolling()
    this.logsPollTimer = setInterval(() => {
      if (this.logsOpenFor()) {
        this.refreshLogs()
      } else {
        this.stopLogPolling()
      }
    }, PreviewPanelComponent.LOGS_POLL_MS)
    this.resetLogsTimeout()
  }

  private stopLogPolling(): void {
    if (this.logsPollTimer) {
      clearInterval(this.logsPollTimer)
      this.logsPollTimer = null
    }
    if (this.logsTimeoutTimer) {
      clearTimeout(this.logsTimeoutTimer)
      this.logsTimeoutTimer = null
    }
  }

  private resetLogsTimeout(): void {
    if (this.logsTimeoutTimer) {
      clearTimeout(this.logsTimeoutTimer)
    }
    this.logsTimeoutTimer = setTimeout(() => {
      this.stopLogPolling()
    }, PreviewPanelComponent.LOGS_TIMEOUT_MS)
  }

  private scrollLogsToBottom(): void {
    setTimeout(() => {
      const el = this.logsContent?.nativeElement
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    })
  }
}
