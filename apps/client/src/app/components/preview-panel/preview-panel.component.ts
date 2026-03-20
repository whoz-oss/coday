import { Component, inject, OnDestroy, OnInit } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { Subject } from 'rxjs'
import { ProjectApiService, PreviewStatusResponse } from '../../core/services/project-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { toSignal } from '@angular/core/rxjs-interop'
import { map } from 'rxjs/operators'

@Component({
  selector: 'app-preview-panel',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './preview-panel.component.html',
  styleUrl: './preview-panel.component.scss',
})
export class PreviewPanelComponent implements OnInit, OnDestroy {
  private readonly destroy$ = new Subject<void>()
  private readonly projectApi = inject(ProjectApiService)
  private readonly projectState = inject(ProjectStateService)

  // Derived signal: does the selected project have a preview.command?
  readonly hasPreviewCommand = toSignal(
    this.projectState.selectedProject$.pipe(map((p) => !!p?.config?.['preview']?.['command'])),
    { initialValue: false }
  )

  status: 'running' | 'stopped' = 'stopped'
  previewUrl: string | null = null
  logs = ''
  isLoading = false
  showLogs = false
  errorMessage = ''

  ngOnInit(): void {
    // Fetch status once on init so the UI reflects any already-running preview
    const name = this.projectState.getSelectedProjectId()
    if (name) {
      this.projectApi.getPreviewStatus(name).subscribe({
        next: (resp) => this.applyStatus(resp),
        error: () => {},
      })
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  start(): void {
    const name = this.projectState.getSelectedProjectId()
    if (!name) return
    this.isLoading = true
    this.errorMessage = ''
    this.projectApi.startPreview(name).subscribe({
      next: (resp) => {
        this.applyStatus(resp)
        this.isLoading = false
      },
      error: (err) => {
        this.errorMessage = err?.error?.error ?? 'Failed to start preview'
        this.isLoading = false
      },
    })
  }

  stop(): void {
    const name = this.projectState.getSelectedProjectId()
    if (!name) return
    this.isLoading = true
    this.errorMessage = ''
    this.projectApi.stopPreview(name).subscribe({
      next: () => {
        this.status = 'stopped'
        this.previewUrl = null
        this.isLoading = false
      },
      error: (err) => {
        this.errorMessage = err?.error?.error ?? 'Failed to stop preview'
        this.isLoading = false
      },
    })
  }

  refreshLogs(): void {
    const name = this.projectState.getSelectedProjectId()
    if (!name) return
    this.projectApi.getPreviewLogs(name).subscribe({
      next: (resp) => {
        this.logs = resp.logs
      },
      error: () => {
        this.logs = '(failed to load logs)'
      },
    })
  }

  toggleLogs(): void {
    this.showLogs = !this.showLogs
    if (this.showLogs) {
      this.refreshLogs()
    }
  }

  private applyStatus(resp: PreviewStatusResponse): void {
    this.status = resp.status
    this.previewUrl = resp.url ?? null
  }
}
