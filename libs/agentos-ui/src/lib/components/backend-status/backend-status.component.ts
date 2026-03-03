import { Component, inject, OnInit, signal } from '@angular/core'
import { PluginControllerService } from '@whoz-oss/agentos-api-client'

/**
 * BackendStatusComponent — small status dot indicating AgentOS reachability.
 *
 * Pings the backend once on init via PluginControllerService.
 * Three states: null (checking), true (online), false (offline).
 *
 * Reusable primitive: used in HeaderComponent and HelloAgentosComponent.
 */
@Component({
  selector: 'agentos-backend-status',
  standalone: true,
  imports: [],
  templateUrl: './backend-status.component.html',
  styleUrl: './backend-status.component.scss',
})
export class BackendStatusComponent implements OnInit {
  private readonly pluginService = inject(PluginControllerService)

  protected online = signal<boolean | null>(null)

  ngOnInit(): void {
    this.pluginService.getAllPlugins().subscribe({
      next: () => this.online.set(true),
      error: () => this.online.set(false),
    })
  }
}
