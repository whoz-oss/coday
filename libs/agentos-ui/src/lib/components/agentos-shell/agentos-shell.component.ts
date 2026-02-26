import { Component, signal } from '@angular/core'
import { RouterOutlet } from '@angular/router'

/**
 * AgentosShellComponent — layout shell and initialization gate.
 *
 * Sits at path '' (no URL segment contributed).
 * Responsibilities:
 * - Initialize shared context (userId, namespace loading) once services are available
 * - Show a loader while initialization is in progress
 * - Render <router-outlet> only when ready
 *
 * TODO: inject NamespaceStateService once agentos-dataflow is available
 *   - call namespaceState.init() here
 *   - derive `ready` from namespaceState.initialized$
 */
@Component({
  selector: 'agentos-shell',
  standalone: true,
  imports: [RouterOutlet],
  templateUrl: './agentos-shell.component.html',
  styleUrl: './agentos-shell.component.scss',
})
export class AgentosShellComponent {
  // TODO: replace with signal derived from NamespaceStateService.initialized$
  protected ready = signal(true)
}
