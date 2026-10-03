import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { FactoryStore } from '../../core/factory.store'
import { ShellState } from '../../core/shell-state'
import { UsdPipe } from '../../shared/pipes/format.pipes'
import { SandboxAction, SandboxCardComponent } from './sandbox-card/sandbox-card.component'

@Component({
  selector: 'sf-sandboxes-page',
  imports: [RouterLink, MatButtonModule, MatIconModule, SandboxCardComponent, UsdPipe],
  templateUrl: './sandboxes-page.component.html',
  styleUrl: './sandboxes-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SandboxesPageComponent {
  protected readonly store = inject(FactoryStore)

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Sandboxes' }])
  }

  protected onAction(name: string, action: SandboxAction): void {
    void name
    void action
  }
}
