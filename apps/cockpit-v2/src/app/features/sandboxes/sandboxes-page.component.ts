import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms'
import { RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatCheckboxModule } from '@angular/material/checkbox'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatInputModule } from '@angular/material/input'
import { MatSelectModule } from '@angular/material/select'
import { FactoryStore } from '../../core/factory.store'
import { ShellState } from '../../core/shell-state'
import { UsdPipe } from '../../shared/pipes/format.pipes'
import { SandboxAction, SandboxCardComponent } from './sandbox-card/sandbox-card.component'

@Component({
  selector: 'sf-sandboxes-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    SandboxCardComponent,
    UsdPipe,
  ],
  templateUrl: './sandboxes-page.component.html',
  styleUrl: './sandboxes-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SandboxesPageComponent {
  protected readonly store = inject(FactoryStore)

  protected readonly projects = ['coday']
  protected readonly rosters = ['default']

  protected readonly form = inject(NonNullableFormBuilder).group({
    project: 'coday',
    branch: '',
    roster: 'default',
    orchestrator: true,
  })

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Sandboxes' }])
  }

  protected mount(): void {
    // No container-fleet backend exists: the form is informational only.
  }

  protected bestOfN(): void {
    // No best-of-N backend exists: the control is informational only.
  }

  protected onAction(name: string, action: SandboxAction): void {
    void name
    void action
  }
}
