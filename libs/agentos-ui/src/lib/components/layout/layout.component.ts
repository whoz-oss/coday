import { Component, ChangeDetectionStrategy } from '@angular/core'
import { RouterOutlet } from '@angular/router'
import { ShellTopbarComponent } from '../shell-topbar/shell-topbar.component'

@Component({
  selector: 'agentos-layout',
  imports: [RouterOutlet, ShellTopbarComponent],
  templateUrl: './layout.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './layout.component.scss',
})
export class LayoutComponent {}
