import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { AbstractControl, FormControl, ReactiveFormsModule, ValidationErrors } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AddMessageRequest,
  AgentConfig,
  AgentConfigControllerService,
  Case,
  CaseControllerService,
} from '@whoz-oss/agentos-api-client'
import { firstValueFrom } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'

/**
 * AgentConfigLaunchComponent — launch page for LOOP-mode agent configs, and the UI way to test a loop.
 *
 * It exercises the same path as the scheduler: the editable JSON below is an `AgentLoopPayload`, sent as
 * `sessionContext.agentFlowConfig` of the start message, where it takes precedence over `AgentConfig.loopConfig`.
 * The textarea is prefilled with `loopConfig` when the agent has one, with a `searchAgentUsers` template otherwise
 * (its `agentConfigId` being this loop agent's own, whose audience contains the launching user).
 * When the textarea is emptied, no context is sent and the backend falls back to `loopConfig`.
 *
 * Route: `/:namespaceId/agent-configs/:agentConfigId/launch`
 */
@Component({
  selector: 'agentos-agent-config-launch',
  imports: [ReactiveFormsModule],
  templateUrl: './agent-config-launch.component.html',
  styleUrl: './agent-config-launch.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentConfigLaunchComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly caseController = inject(CaseControllerService)
  private readonly caseState = inject(CaseStateService)
  private readonly agentConfigController = inject(AgentConfigControllerService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly agentConfigId = this.route.snapshot.params['agentConfigId'] as string

  /** Resolved agent config — stored to avoid a second fetch at launch time. */
  private resolvedAgentConfig: AgentConfig | null = null

  /** Editable AgentLoopPayload JSON, sent as `sessionContext.agentFlowConfig` when non-empty. */
  protected readonly payloadControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [jsonValidator],
  })

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
          this.payloadControl.setValue(
            agentConfig.loopConfig != null
              ? JSON.stringify(agentConfig.loopConfig, null, 2)
              : payloadTemplate(this.agentConfigId)
          )
          this.isLoading.set(false)
        },
        error: () => {
          // Keep the page usable: the template needs nothing from the config.
          this.payloadControl.setValue(payloadTemplate(this.agentConfigId))
          this.isLoading.set(false)
        },
      })
  }

  /**
   * Launch sequence:
   * 1. Use the already-loaded agent config (or fetch if not yet resolved).
   * 2. POST /api/cases to create a new case in the current namespace.
   * 3. Prepend the case to CaseStateService.
   * 4. POST /api/cases/:id/messages with a start message; when the textarea is non-empty its JSON travels as
   *    `sessionContext.agentFlowConfig`, otherwise the backend uses loopConfig.
   * 5. Navigate to the case chat.
   */
  protected async launch(): Promise<void> {
    if (this.payloadControl.invalid) return
    this.launchError.set(null)
    this.isLaunching.set(true)

    try {
      // Step 1: use the cached config or fetch it if the init call is still pending.
      const agentConfig =
        this.resolvedAgentConfig ??
        (await firstValueFrom(this.agentConfigController.getByIdAgentConfig(this.agentConfigId)))

      // Step 2: create the case.
      // Cast: the backend only needs namespaceId at creation; the generated type requires
      // all non-optional fields (favorite, removed, status) that the server sets itself.
      const createdCase = await firstValueFrom(
        this.caseController.createCase({ namespaceId: this.namespaceId } as Case)
      )

      // Step 3: register the case in the drawer immediately.
      this.caseState.addCase(createdCase)
      const caseId = createdCase.id ?? ''

      // Step 4: send the start message routed to this agent, with the payload in its context if any.
      const raw = this.payloadControl.value.trim()
      const startMessage: AddMessageRequest = {
        content: `@${agentConfig.name} start`,
        ...(raw ? { sessionContext: { agentFlowConfig: JSON.parse(raw) } } : {}),
      }
      await firstValueFrom(this.caseController.addMessageCase(caseId, startMessage))

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

/** Template for an agent without loopConfig: searches the users of the loop agent itself. */
function payloadTemplate(agentConfigId: string): string {
  return JSON.stringify(
    {
      search: {
        tool: 'searchAgentUsers',
        params: { agentConfigId },
      },
      act: {
        agentName: '<agent started for each user>',
        prompt: '<first message of each started case>',
      },
    },
    null,
    2
  )
}

function jsonValidator(control: AbstractControl): ValidationErrors | null {
  const value = (control.value as string)?.trim()
  if (!value) return null
  try {
    JSON.parse(value)
    return null
  } catch {
    return { invalidJson: true }
  }
}
