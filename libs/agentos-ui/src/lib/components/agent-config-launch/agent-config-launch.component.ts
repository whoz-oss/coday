import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfigControllerService, Case, Configuration } from '@whoz-oss/agentos-api-client'
import { firstValueFrom } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'

/**
 * AgentConfigLaunchComponent — launch page for LOOP-mode agent configs.
 *
 * Provides a monospace textarea for the AgentLoopPayload JSON, validates it,
 * creates a Case, sends the payload as the first message, and navigates to
 * the new case chat.
 *
 * The message is prefixed with `@<agentName>` so the case routes it to this LOOP agent
 * rather than to the namespace default agent; AgentLoop strips the mention before parsing.
 *
 * Route: `/:namespaceId/agent-configs/:agentConfigId/launch` — namespace only, since a
 * loop creates cases and cases always live in a namespace.
 *
 * Navigation mirrors CaseHomeComponent.submit():
 *   POST /api/cases → addCase() → POST /api/cases/:id/messages → navigate
 */
@Component({
  selector: 'agentos-agent-config-launch',
  imports: [],
  templateUrl: './agent-config-launch.component.html',
  styleUrl: './agent-config-launch.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentConfigLaunchComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)
  private readonly caseState = inject(CaseStateService)
  private readonly agentConfigController = inject(AgentConfigControllerService)
  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly agentConfigId = this.route.snapshot.params['agentConfigId'] as string

  /** Raw JSON entered by the user. */
  protected readonly payloadJson = signal('')

  /** Inline error shown under the textarea when the JSON is invalid. */
  protected readonly payloadError = signal<string | null>(null)

  /** Error shown when the API calls fail. */
  protected readonly launchError = signal<string | null>(null)

  /** True while the launch sequence is in flight. */
  protected readonly isLaunching = signal(false)

  /** Placeholder illustrating the AgentLoopPayload shape. */
  protected readonly placeholder = JSON.stringify(
    {
      tool: 'SearchTalents',
      searchInput: { endDatePeriod: ['THIS_WEEK'], resolveTargets: ['OWNER'] },
      act: {
        agentName: 'talent-analyzer',
        promptTemplate: 'Analyse this entity: {entityId}',
      },
    },
    null,
    2
  )

  protected onInput(event: Event): void {
    this.payloadJson.set((event.target as HTMLTextAreaElement).value)
  }

  /**
   * Launch sequence:
   * 1. Validate the textarea content as JSON.
   * 2. Resolve the agent name used for the `@mention`.
   * 3. POST /api/cases to create a new case in the current namespace.
   * 4. Prepend the case to CaseStateService.
   * 5. POST /api/cases/:id/messages with `@<agentName> <json>` as content.
   * 6. Navigate to the case chat.
   */
  protected async launch(): Promise<void> {
    const raw = this.payloadJson().trim()
    this.payloadError.set(null)
    this.launchError.set(null)

    // Step 1: validate JSON.
    if (!raw) {
      this.payloadError.set('Payload is required.')
      return
    }
    try {
      JSON.parse(raw)
    } catch {
      this.payloadError.set('Invalid JSON — please fix the syntax and try again.')
      return
    }

    this.isLaunching.set(true)
    try {
      // Step 2: resolve the agent name for the @mention routing.
      const agentConfig = await firstValueFrom(this.agentConfigController.getByIdAgentConfig(this.agentConfigId))

      // Step 3: create the case.
      const createdCase = await firstValueFrom(
        this.http.post<Case>(`${this.config.basePath}/api/cases`, {
          namespaceId: this.namespaceId,
        })
      )
      // Step 4: register the case in the drawer immediately.
      this.caseState.addCase(createdCase)
      const caseId = createdCase.id ?? ''

      // Step 5: send the loop payload as the first message, routed to this agent.
      await firstValueFrom(
        this.http.post(`${this.config.basePath}/api/cases/${caseId}/messages`, {
          content: `@${agentConfig.name} ${raw}`,
          userId: 'default-user',
        })
      )

      // Step 6: navigate to the new case.
      this.router.navigate(['/agentos/home'], {
        queryParams: { ns: this.namespaceId, case: caseId },
      })
    } catch (err) {
      console.error('[AgentConfigLaunch] Failed to launch loop agent', err)
      this.launchError.set('Failed to launch the agent loop. Please check the console for details.')
      this.isLaunching.set(false)
    }
  }

  protected back(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'agent-configs', this.agentConfigId, 'edit'])
  }
}
