import { Component } from '@angular/core'
import { RouterOutlet } from '@angular/router'

/**
 * AgentosShellComponent — layout shell.
 *
 * Thin wrapper, renders the router-outlet directly.
 * Initialization concerns are handled per-route (NamespaceListComponent calls init()).
 */
@Component({
  selector: 'agentos-shell',
  standalone: true,
  imports: [RouterOutlet],
  templateUrl: './agentos-shell.component.html',
  styleUrl: './agentos-shell.component.scss',
})
export class AgentosShellComponent {}
