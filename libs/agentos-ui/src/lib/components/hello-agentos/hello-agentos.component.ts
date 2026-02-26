import { Component, signal } from '@angular/core'
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
export class HelloAgentosComponent {
  protected lastAction = signal<string | null>(null)

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
