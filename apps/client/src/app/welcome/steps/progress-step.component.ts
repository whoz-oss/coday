import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core'
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

  protected start(): void {
    const target = this.state.landingCommands()
    if (!target) return
    this.state.stopPolling()
    void this.router.navigate([target.path], { queryParams: target.queryParams })
  }

  protected retry(): void {
    void this.state.retry()
  }
}
