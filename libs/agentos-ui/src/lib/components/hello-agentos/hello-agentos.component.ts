import { Component } from '@angular/core'

/**
 * Hello AgentOS — entry point component for the agentos-ui lib.
 *
 * Demonstrates the isolation pattern:
 * - No imports from Coday app internals
 * - Uses only CSS custom properties (contract with the host app)
 * - Standalone, zero NgModule dependency
 */
@Component({
  selector: 'agentos-hello',
  standalone: true,
  imports: [],
  templateUrl: './hello-agentos.component.html',
  styleUrl: './hello-agentos.component.scss',
})
export class HelloAgentosComponent {}
