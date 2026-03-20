import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { ProjectStateService } from '../../core/services/project-state.service'
import { map } from 'rxjs/operators'
import { toSignal } from '@angular/core/rxjs-interop'

@Component({
  selector: 'app-preview-panel',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './preview-panel.component.html',
  styleUrl: './preview-panel.component.scss',
})
export class PreviewPanelComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef)
  private readonly projectState = inject(ProjectStateService)

  // Derived signal: does the selected project have a preview.command?
  readonly hasPreviewCommand = toSignal(
    this.projectState.selectedProject$.pipe(map((p) => !!p?.config?.['preview']?.['command'])),
    { initialValue: false }
  )

  readonly status = signal<'running' | 'stopped'>('stopped')
  readonly previewUrl = signal<string | null>(null)
  readonly logs = signal('')
  readonly isLoading = signal(false)
  readonly showLogs = signal(false)
  readonly errorMessage = signal('')

  ngOnInit(): void {
    // Fetch status once on init so the UI reflects any already-running preview
    try {
      this.projectState
        .getPreviewStatus()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (resp) => this.applyStatus(resp),
          error: () => {},
        })
    } catch {
      // No project selected yet — ignore
    }
  }

  start(): void {
    this.isLoading.set(true)
    this.errorMessage.set('')
    this.projectState
      .startPreview()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (resp) => {
          this.applyStatus(resp)
          this.isLoading.set(false)
        },
        error: (err) => {
          this.errorMessage.set(err?.error?.error ?? 'Failed to start preview')
          this.isLoading.set(false)
        },
      })
  }

  stop(): void {
    this.isLoading.set(true)
    this.errorMessage.set('')
    this.projectState
      .stopPreview()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.status.set('stopped')
          this.previewUrl.set(null)
          this.isLoading.set(false)
        },
        error: (err) => {
          this.errorMessage.set(err?.error?.error ?? 'Failed to stop preview')
          this.isLoading.set(false)
        },
      })
  }

  refreshLogs(): void {
    this.projectState
      .getPreviewLogs()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (resp) => this.logs.set(resp.logs),
        error: () => this.logs.set('(failed to load logs)'),
      })
  }

  toggleLogs(): void {
    this.showLogs.update((v) => !v)
    if (this.showLogs()) {
      this.refreshLogs()
    }
  }

  private applyStatus(resp: { status: 'running' | 'stopped'; url?: string }): void {
    this.status.set(resp.status)
    this.previewUrl.set(resp.url ?? null)
  }
}
