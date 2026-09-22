import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core'
import { CaseWorkspaceService, WorkspaceAction, WorkspaceView } from '../../services/case-workspace.service'
import { WorkspaceRetryRequest } from '@whoz-oss/agentos-api-client'
import { Subscription } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { ExchangeStateService } from '../../services/exchange-state.service'

@Component({
  selector: 'agentos-case-workspace',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (view(); as workspace) {
    @if (actionable()) {
      <details class="workspace" open>
        <summary>Workspace · {{ workspace.status }}</summary>
        @if (workspace.rootCaseId !== caseId()) {
          <p>Shared with the root case. Workspace preparation is managed there.</p>
        }
        @if (workspace.failureReason || workspace.cleanupReason) {
          <p>{{ workspace.failureReason || workspace.cleanupReason }}</p>
        }
        @if (error()) {
          <p role="alert">{{ error() }}</p>
        }
        <div class="actions">
          @if (workspace.rootCaseId === caseId() && canWrite()) {
            @if (workspace.status === 'FAILED') {
              <button type="button" [disabled]="busy()" (click)="act('retry')">Retry preparation</button>
              <label
                ><input type="checkbox" [checked]="acknowledgeSetup()" (change)="onAcknowledgeSetup($event)" /> I have
                checked the effects of the interrupted setup and allow it to run again.</label
              >
              <button
                type="button"
                [disabled]="busy() || !acknowledgeSetup()"
                (click)="act('retry', { acknowledgeSetupReplay: true })"
              >
                Run setup again
              </button>
            }
          }
        </div>
      </details>
    }
  }`,
  styles: [
    '.workspace{padding:8px 16px;border-bottom:1px solid var(--color-border,#8884);font-size:12px}.actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.actions p,.actions label{flex-basis:100%}summary{cursor:pointer}button{cursor:pointer;padding:4px 8px}',
  ],
})
export class CaseWorkspaceComponent {
  readonly caseId = input.required<string>()
  readonly canWrite = input(false)
  private readonly service = inject(CaseWorkspaceService)
  private readonly cases = inject(CaseStateService)
  private readonly files = inject(ExchangeStateService)
  readonly view = signal<WorkspaceView | null>(null)
  readonly actionable = computed(() => {
    const view = this.view()
    return (
      view?.equipped &&
      (view.status === 'REQUESTED' ||
        view.status === 'PREPARING' ||
        view.status === 'FAILED' ||
        view.status === 'DELETING' ||
        !!view.failureReason ||
        !!view.cleanupReason)
    )
  })
  readonly busy = signal(false)
  readonly error = signal('')
  readonly acknowledgeSetup = signal(false)
  private action?: Subscription
  constructor() {
    effect((cleanup) => {
      const id = this.caseId()
      this.view.set(null)
      this.error.set('')
      this.acknowledgeSetup.set(false)
      this.busy.set(false)
      const sub = this.service.watch(id).subscribe(({ view }) => this.view.set(view))
      cleanup(() => {
        sub.unsubscribe()
        this.action?.unsubscribe()
      })
    })
  }
  protected onAcknowledgeSetup(event: Event): void {
    this.acknowledgeSetup.set((event.target as HTMLInputElement).checked)
  }
  act(action: WorkspaceAction, body: WorkspaceRetryRequest = { acknowledgeSetupReplay: false }): void {
    if (!this.canWrite() || this.view()?.rootCaseId !== this.caseId() || this.busy()) return
    this.busy.set(true)
    this.error.set('')
    const id = this.caseId()
    this.action = this.service.act(id, action, body).subscribe({
      next: (view) => {
        if (this.caseId() === id) {
          this.view.set(view)
          this.acknowledgeSetup.set(false)
          this.busy.set(false)
          if (action === 'retry') this.files.refreshCase()
          this.cases.reloadCurrent()
        }
      },
      error: (err) => {
        if (this.caseId() === id) {
          this.error.set(err.error?.message || 'Workspace action failed')
          this.busy.set(false)
        }
      },
    })
  }
}
