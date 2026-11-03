import { ChangeDetectionStrategy, Component, computed, effect, inject, untracked } from '@angular/core'
import { Router } from '@angular/router'
import { SetupStateService } from '../setup-state.service'
import { StepState } from '../setup.models'

const ICONS: Record<StepState, string> = {
  pending: '○',
  running: '',
  done: '✓',
  error: '!',
  blocked: '!',
}

/**
 * Step 4: the live checklist (download %, Java, AgentOS, providers, models, namespace, agent), then the
 * "Start with Coday" call to action that lands in the personal namespace on a new case.
 *
 * Also the whole of the loading page (`mode() === 'loading'`, setup already done): the same list reduced to the services,
 * with nothing that suggests a setup, which leaves for the app by itself once they are up.
 */
@Component({
  selector: 'app-progress-step',
  templateUrl: './progress-step.component.html',
  styleUrls: ['./step.scss', './progress-step.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProgressStepComponent {
  protected readonly state = inject(SetupStateService)
  private readonly router = inject(Router)

  protected readonly icon = (s: StepState): string => ICONS[s]
  protected readonly javaHint = computed(() => this.state.status()?.steps.java.hint ?? '')
  protected readonly applyError = computed(() => this.state.status()?.apply.error ?? null)
  protected readonly loading = computed(() => this.state.mode() === 'loading')
  protected readonly items = computed(() => (this.loading() ? this.state.loadingChecklist() : this.state.checklist()))

  constructor() {
    effect(() => {
      if (!this.loading() || !this.state.loadingReady()) return
      untracked(() => this.leave(this.state.loadingTarget()))
    })
  }

  protected start(): void {
    const target = this.state.landingCommands()
    if (target) this.leave(target)
  }

  private leave(target: { path: string; queryParams: Record<string, string> }): void {
    this.state.stopPolling()
    void this.router.navigate([target.path], { queryParams: target.queryParams })
  }

  protected retry(): void {
    void this.state.retry()
  }
}
