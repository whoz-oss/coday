import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { HttpClient } from '@angular/common/http'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, AgentConfigControllerService, Case, Configuration } from '@whoz-oss/agentos-api-client'
import { firstValueFrom } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'

/**
 * AgentConfigLaunchComponent — launch page for LOOP-mode agent configs.
 *
 * Displays the AgentConfig.loopConfig as read-only JSON so the user can
 * inspect what will be used, then creates a Case and sends a minimal start
 * message. The backend reads loopConfig directly from AgentConfig — the
 * textarea content is informational only and is never sent in the message.
 *
 * Route: `/:namespaceId/agent-configs/:agentConfigId/launch`
 */
@Component({
  selector: 'agentos-agent-config-launch',
  imports: [],
  templateUrl: './agent-config-launch.component.html',
  styleUrl: './agent-config-launch.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentConfigLaunchComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)
  private readonly caseState = inject(CaseStateService)
  private readonly agentConfigController = inject(AgentConfigControllerService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly agentConfigId = this.route.snapshot.params['agentConfigId'] as string

  /** Resolved agent config — stored to avoid a second fetch at launch time. */
  private resolvedAgentConfig: AgentConfig | null = null

  /** Read-only JSON display of loopConfig, pre-filled from the resolved AgentConfig. */
  protected readonly payloadJson = signal('')

  /** Error shown when the API calls fail. */
  protected readonly launchError = signal<string | null>(null)

  /** True while the launch sequence is in flight. */
  protected readonly isLaunching = signal(false)

  /** True while the agent config is being fetched on init. */
  protected readonly isLoading = signal(false)

  ngOnInit(): void {
    this.isLoading.set(true)
    this.agentConfigController
      .getByIdAgentConfig(this.agentConfigId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (agentConfig) => {
          this.resolvedAgentConfig = agentConfig
          if (agentConfig.loopConfig != null) {
            this.payloadJson.set(JSON.stringify(agentConfig.loopConfig, null, 2))
          }
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
        },
      })
  }

  /**
   * Launch sequence:
   * 1. Use the already-loaded agent config (or fetch if not yet resolved).
   * 2. POST /api/cases to create a new case in the current namespace.
   * 3. Prepend the case to CaseStateService.
   * 4. POST /api/cases/:id/messages with a minimal start message.
   *    The backend reads loopConfig from AgentConfig directly.
   * 5. Navigate to the case chat.
   */
  protected async launch(): Promise<void> {
    this.launchError.set(null)
    this.isLaunching.set(true)

    try {
      // Step 1: use the cached config or fetch it if the init call is still pending.
      const agentConfig =
        this.resolvedAgentConfig ??
        (await firstValueFrom(this.agentConfigController.getByIdAgentConfig(this.agentConfigId)))

      // Step 2: create the case.
      const createdCase = await firstValueFrom(
        this.http.post<Case>(`${this.config.basePath}/api/cases`, {
          namespaceId: this.namespaceId,
        })
      )

      // Step 3: register the case in the drawer immediately.
      this.caseState.addCase(createdCase)
      const caseId = createdCase.id ?? ''

      // Step 4: send a minimal start message routed to this agent.
      // The backend reads loopConfig from AgentConfig — no payload in the message.
      await firstValueFrom(
        this.http.post(`${this.config.basePath}/api/cases/${caseId}/messages`, {
          content: `@${agentConfig.name} start`,
          userId: 'default-user',
        })
      )

      // Step 5: navigate to the new case.
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
