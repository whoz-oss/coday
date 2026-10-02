import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router'
import { MatToolbarModule } from '@angular/material/toolbar'
import { MatSidenavModule } from '@angular/material/sidenav'
import { MatIconModule } from '@angular/material/icon'
import { MatSlideToggleModule } from '@angular/material/slide-toggle'
import { FactoryStore } from '../core/factory.store'
import { ShellState } from '../core/shell-state'
import { UsdPipe } from '../shared/pipes/format.pipes'

interface NavItem {
  label: string
  icon: string
  link: string
}

@Component({
  selector: 'sf-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatIconModule,
    MatSlideToggleModule,
    UsdPipe,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellComponent {
  protected readonly store = inject(FactoryStore)
  protected readonly shell = inject(ShellState)

  protected readonly nav: NavItem[] = [
    { label: 'Sandboxes', icon: 'grid_view', link: '/sandboxes' },
    { label: 'Sessions', icon: 'monitoring', link: '/sessions/872641a8' },
    { label: 'Historique', icon: 'history', link: '/historique' },
    { label: 'Workflows', icon: 'account_tree', link: '/workflows' },
    { label: 'Agents', icon: 'smart_toy', link: '/agents' },
  ]
}
