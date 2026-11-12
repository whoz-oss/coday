import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
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

/** Starting point of the `loopData` editor: the keys a scheduled prompt in loop mode sends. */
const LOOP_DATA_SKELETON = JSON.stringify(
  { searchToolName: '', searchToolArgs: {}, agentName: '', prompt: '', scheduledPromptId: '' },
  null,
  2
)

/**
 * AgentConfigLaunchComponent — launch page for LOOP-mode agent configs.
 *
 * Displays the AgentConfig.loopConfig as read-only JSON so the user can inspect what will be used,
 * then creates a Case and sends a start message. The backend reads loopConfig directly from the
 * AgentConfig, so the textarea showing it is informational only.
 *
 * Two loopConfig formats exist:
 * - `steps` (recommended): the steps read their data from `${loopData.key}` placeholders, resolved
 *   against the `loopData` entry of the session context of the triggering message. A scheduled
 *   prompt in loop mode sends it; from this page the user provides it in an editable "Loop data"
 *   field, sent as `sessionContext.loopData`. A missing entry makes the loop emit a warning.
 * - legacy (`search` / `act`): runs as is, no loopData, the message carries no session context.
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
  private readonly caseController = inject(CaseControllerService)
  private readonly caseState = inject(CaseStateService)
  private readonly agentConfigController = inject(AgentConfigControllerService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly agentConfigId = this.route.snapshot.params['agentConfigId'] as string

  /** Resolved agent config — stored to avoid a second fetch at launch time. */
  private resolvedAgentConfig: AgentConfig | null = null

  /** Read-only JSON display of loopConfig, pre-filled from the resolved AgentConfig. */
  protected readonly payloadJson = signal('')

  /** True when the loopConfig uses the `steps` format, which needs a `loopData` session context. */
  protected readonly isStepsFormat = signal(false)

  /** Editable `loopData` (JSON), only used by the `steps` format. */
  protected readonly loopDataJson = signal(LOOP_DATA_SKELETON)

  /** Parsed `loopData`, or null while the text is not a valid JSON object. */
  private readonly parsedLoopData = computed(() => parseJsonObject(this.loopDataJson()))

  /** True when the steps format is used and `loopData` is not a valid JSON object: launching is blocked. */
  protected readonly loopDataInvalid = computed(() => this.isStepsFormat() && this.parsedLoopData() === null)

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
          this.isStepsFormat.set(hasSteps(agentConfig.loopConfig))
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
   * 4. POST /api/cases/:id/messages with a minimal start message. The backend reads loopConfig from
   *    AgentConfig directly; for the `steps` format the message also carries `loopData` in its
   *    session context.
   * 5. Navigate to the case chat.
   */
  protected async launch(): Promise<void> {
    if (this.loopDataInvalid()) return
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

      // Step 4: send a minimal start message routed to this agent. The backend reads loopConfig from
      // AgentConfig; only the steps format needs data, carried by the session context.
      const startMessage: AddMessageRequest = { content: `@${agentConfig.name} start` }
      if (this.isStepsFormat()) {
        startMessage.sessionContext = { loopData: this.parsedLoopData() }
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

  protected onLoopDataInput(event: Event): void {
    this.loopDataJson.set((event.target as HTMLTextAreaElement).value)
  }

  protected back(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'agent-configs', this.agentConfigId, 'edit'])
  }
}

/** True when `loopConfig` is an object holding a `steps` array (the recommended format). */
function hasSteps(loopConfig: unknown): boolean {
  return (
    loopConfig !== null && typeof loopConfig === 'object' && Array.isArray((loopConfig as { steps?: unknown }).steps)
  )
}

/** Parses a JSON *object* (not an array or a primitive); null when the text is anything else. */
function parseJsonObject(text: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(text)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}
