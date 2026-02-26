import { Component, inject, OnInit, signal } from '@angular/core'
import { PluginControllerService } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'

/**
 * Hello AgentOS — entry point component for the agentos-ui lib.
 *
 * Demonstrates the isolation pattern:
 * - No imports from Coday app internals
 * - Uses only CSS custom properties (contract with the host app)
 * - Standalone, zero NgModule dependency
 * - Consumes @whoz-oss/design-system primitives
 */
@Component({
  selector: 'agentos-hello',
  imports: [IconButtonComponent],
  templateUrl: './hello-agentos.component.html',
  styleUrl: './hello-agentos.component.scss',
})
export class HelloAgentosComponent implements OnInit {
  private readonly pluginService = inject(PluginControllerService)

  protected lastAction = signal<string | null>(null)
  protected agentosOnline = signal<boolean | null>(null)

  ngOnInit(): void {
    this.pluginService.getAllPlugins().subscribe({
      next: () => this.agentosOnline.set(true),
      error: () => this.agentosOnline.set(false),
    })
  }

  protected onSend(): void {
    this.lastAction.set('send')
  }

  protected onStop(): void {
    this.lastAction.set('stop')
  }

  protected onAttach(): void {
    this.lastAction.set('attach')
  }
}
