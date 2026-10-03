import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { RouterLink } from '@angular/router'
import { MatCardModule } from '@angular/material/card'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { Sandbox } from '../../../core/models'
import { StatusChipComponent } from '../../../shared/ui/status-chip.component'
import { MetricChipComponent } from '../../../shared/ui/metric-chip.component'
import { PhaseBarComponent } from '../../../shared/ui/phase-bar.component'
import { DurationPipe, TokensPipe, UsdPipe } from '../../../shared/pipes/format.pipes'

export type SandboxAction = 'ask' | 'workflow' | 'conversation' | 'log' | 'commits'

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
}
