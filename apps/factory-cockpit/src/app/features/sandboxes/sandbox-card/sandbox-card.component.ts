import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core'
import { RouterLink } from '@angular/router'
import { MatCardModule } from '@angular/material/card'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { FactoryStore } from '../../../core/factory.store'
import { Sandbox } from '../../../core/models'
import { StatusChipComponent } from '../../../shared/ui/status-chip.component'
import { MetricChipComponent } from '../../../shared/ui/metric-chip.component'
import { PhaseBarComponent } from '../../../shared/ui/phase-bar.component'
import { DurationPipe, TokensPipe, UsdPipe } from '../../../shared/pipes/format.pipes'

export type SandboxAction = 'ask' | 'workflow' | 'conversation' | 'log' | 'commits' | 'stop' | 'remove' | 'restore'

@Component({
  selector: 'sf-sandbox-card',
  imports: [
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    StatusChipComponent,
    MetricChipComponent,
    PhaseBarComponent,
    UsdPipe,
    DurationPipe,
    TokensPipe,
  ],
  templateUrl: './sandbox-card.component.html',
  styleUrl: './sandbox-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SandboxCardComponent {
  readonly sandbox = input.required<Sandbox>()
  readonly action = output<SandboxAction>()

  private readonly store = inject(FactoryStore)

  /**
   * A running sandbox can only be stopped when an active attempt is resolvable
   * from the authoritative store state. The cockpit never fabricates an attempt
   * id: when none is resolvable the stop action is simply unavailable.
   */
  protected get canStop(): boolean {
    const s = this.sandbox()
    if (s.status !== 'working' || !s.run?.id) return false
    return Boolean(this.store.session(s.run.id)?.activeAttemptId)
  }
}
