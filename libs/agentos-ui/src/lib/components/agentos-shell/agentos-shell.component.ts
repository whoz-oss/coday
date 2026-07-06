import { Component, inject } from '@angular/core'
import { RouterOutlet } from '@angular/router'
import { THEME_PORT } from '../../services/theme.service'

/**
 * AgentosShellComponent — layout shell.
 *
 * Thin wrapper, renders the router-outlet directly.
 * Initialization concerns are handled per-route (NamespaceListComponent calls init()).
 */
@Component({
  selector: 'agentos-shell',
  imports: [RouterOutlet],
  templateUrl: './agentos-shell.component.html',
  styleUrl: './agentos-shell.component.scss',
})
export class AgentosShellComponent {
  constructor() {
    // Eagerly resolve the theme port so the active theme is applied as soon as the shell mounts
    // on entering /agentos. Inside the Coday client this resolves to the host's already-live
    // theme service (no flip); standalone it instantiates the lib's default.
    inject(THEME_PORT)
  }
}
