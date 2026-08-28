import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core'
import { toObservable, takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { filter } from 'rxjs/operators'
import { AgentConfig, Namespace } from '@whoz-oss/agentos-api-client'
import { FactoryLaunchRequest, FactoryWorkflow } from '../../services/factory-api.service'
import { FactoryLaunchStateService } from '../../services/factory-launch-state.service'

/**
 * FactoryLaunchComponent — form to launch a new factory run.
 *
 * Receives the current namespaceId as input. Emits:
 * - `launched` with the runId when the run is successfully created.
 * - `cancelled` when the user dismisses the form.
 *
 * ## FACTORY_ROOT derivation
 *
 * fix-loop / agentos-smoke (single agent):
 *   On agent selection, calls deriveRootForAgent(). The root field is
 *   auto-populated when derivedRoot resolves. A mismatch between the derived
 *   root and a manually typed value blocks launch.
 *
 * us-loop (two agents):
 *   BOTH analyst and editor must be explicitly selected — server-side defaults
 *   (factory-analyst / factory-editor) cannot be verified from the UI.
 *   On any change to either field, deriveTwoAgentRoots() is called with both
 *   agents. Both FILE_ACCESS rootPaths must be equal; a mismatch produces a
 *   clear error naming both roles and their paths. Launch is blocked until
 *   roots agree and no rootError is present.
 *
 * Workflow field contract (mirrors standalone dashboard):
 *   - fix-loop / agentos-smoke : single FACTORY_AGENT (required)
 *   - us-loop                  : FACTORY_AGENT_ANALYST + FACTORY_AGENT_EDITOR (both required for root verification)
 *   - backend-oracle-check     : no agent fields
 */
@Component({
  selector: 'agentos-factory-launch',
  imports: [ReactiveFormsModule],
  templateUrl: './factory-launch.component.html',
  styleUrl: './factory-launch.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryLaunchComponent implements OnInit {
  private readonly launchState = inject(FactoryLaunchStateService)
  private readonly destroyRef = inject(DestroyRef)

  readonly namespaceId = input.required<string>()
  /** Full namespace object — provides configPath for FACTORY_ROOT auto-population. */
  readonly namespace = input<Namespace | null>(null)
  readonly launched = output<string | null>()
  readonly cancelled = output<void>()

  protected readonly agents = this.launchState.agents
  protected readonly agentsLoading = this.launchState.agentsLoading
  protected readonly agentsError = this.launchState.agentsError
  protected readonly launchStatus = this.launchState.launchStatus
  protected readonly launchError = this.launchState.launchError
  protected readonly derivedRoot = this.launchState.derivedRoot
  protected readonly rootLoading = this.launchState.rootLoading
  protected readonly rootError = this.launchState.rootError

  protected readonly form = new FormGroup({
    workflow: new FormControl<FactoryWorkflow>('fix-loop', { nonNullable: true }),
    agent: new FormControl<string>('', { nonNullable: true }),
    analyst: new FormControl<string>('', { nonNullable: true }),
    editor: new FormControl<string>('', { nonNullable: true }),
    domain: new FormControl<'front' | 'back'>('front', { nonNullable: true }),
    task: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    scope: new FormControl<string>('', { nonNullable: true }),
    ticket: new FormControl<string>('', { nonNullable: true }),
    command: new FormControl<string>('', { nonNullable: true }),
    root: new FormControl<string>('', { nonNullable: true }),
  })

  /**
   * Signal bridge for workflow value.
   * computed(() => form.controls.workflow.value) never re-evaluates because
   * FormControl.value is a plain property, not a signal. valueChanges + signal
   * ensures downstream computed()s react immediately.
   */
  protected readonly workflow = signal<FactoryWorkflow>('fix-loop')

  protected readonly isSingleAgent = computed(() => FactoryLaunchStateService.isSingleAgent(this.workflow()))
  protected readonly isTwoAgent = computed(() => FactoryLaunchStateService.isTwoAgent(this.workflow()))
  protected readonly isNoAgent = computed(() => FactoryLaunchStateService.isNoAgent(this.workflow()))

  protected readonly agentsManual = signal(false)

  protected readonly usableAgents = computed(() =>
    this.agents().filter((a) => a.enabled !== false && !a.subAgents?.length)
  )
  protected readonly otherAgents = computed(() => {
    const usable = this.usableAgents()
    return this.agents().filter((a) => !usable.includes(a))
  })

  /**
   * Repository root derived from the namespace configPath.
   *
   * configPath points to the Coday configuration directory inside the repo
   * (e.g. /workspace/myproject/.coday or /workspace/myproject/coday).
   * FACTORY_ROOT must be the repository root, i.e. the parent directory of configPath.
   *
   * Derivation: strip the last path segment from configPath.
   * Example: /Users/ben/sprint/coday → /Users/ben/sprint
   *
   * Returns null when configPath is absent or has no parent (root-level path).
   */
  protected readonly namespaceRoot = computed(() => {
    const configPath = this.namespace()?.configPath
    if (!configPath) return null
    const normalized = configPath.replace(/\/+$/, '') // strip trailing slashes
    const lastSlash = normalized.lastIndexOf('/')
    if (lastSlash <= 0) return null // no parent or already at fs root
    return normalized.slice(0, lastSlash)
  })

  protected readonly rootMismatch = computed(() => {
    // When namespaceRoot is configured, it is the authoritative source — no mismatch check needed.
    if (this.namespaceRoot()) return false
    const derived = this.derivedRoot()
    if (!derived) return false
    const typed = this.form.controls.root.value.trim()
    return typed.length > 0 && typed !== derived
  })

  constructor() {
    // Workflow change: reset agent fields, clear root state.
    this.form.controls.workflow.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((wf) => {
      this.workflow.set(wf)
      this.form.controls.agent.setValue('')
      this.form.controls.analyst.setValue('')
      this.form.controls.editor.setValue('')
      this.launchState.clearDerivedRoot()
      this.form.controls.root.setValue('')
    })

    // Single-agent root derivation (fix-loop / agentos-smoke).
    this.form.controls.agent.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((name) => {
      if (!FactoryLaunchStateService.isSingleAgent(this.workflow())) return
      const agent = this.agents().find((a) => a.name === name)
      if (agent) {
        this.launchState.deriveRootForAgent(this.namespaceId(), agent)
      } else {
        this.launchState.clearDerivedRoot()
      }
    })

    // Two-agent root derivation (us-loop).
    this.form.controls.analyst.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      if (!FactoryLaunchStateService.isTwoAgent(this.workflow())) return
      this.triggerTwoAgentRootDerivation()
    })

    this.form.controls.editor.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      if (!FactoryLaunchStateService.isTwoAgent(this.workflow())) return
      this.triggerTwoAgentRootDerivation()
    })

    /**
     * Auto-populate the root field when derivedRoot resolves and the field is empty.
     * toObservable() + filter() remplace l'effect() avec mutation de FormControl.
     */
    toObservable(this.launchState.derivedRoot)
      .pipe(
        filter((derived): derived is string => !!derived && !this.form.controls.root.value.trim()),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((derived) => {
        this.form.controls.root.setValue(derived, { emitEvent: false })
      })

    // Navigate away on success.
    toObservable(this.launchState.launchStatus)
      .pipe(
        filter((status) => status === 'success'),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(() => {
        const result = this.launchState.launchResult()
        this.launched.emit(result?.runId ?? null)
      })

    // Switch between select and manual input modes.
    toObservable(this.launchState.agentsError)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((err) => {
        if (err) this.agentsManual.set(true)
      })

    toObservable(this.launchState.agentsLoading)
      .pipe(
        filter((loading) => !loading),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(() => {
        if (!this.launchState.agentsError() && this.launchState.agents().length > 0) {
          this.agentsManual.set(false)
        }
      })
  }

  ngOnInit(): void {
    this.launchState.reset()
    this.launchState.loadAgents(this.namespaceId())
  }

  /**
   * Trigger two-agent root derivation when both analyst and editor are selected.
   * If either is empty, clear root state — server-side defaults cannot be verified.
   */
  private triggerTwoAgentRootDerivation(): void {
    const analystName = this.form.controls.analyst.value.trim()
    const editorName = this.form.controls.editor.value.trim()

    if (!analystName || !editorName) {
      this.launchState.clearDerivedRoot()
      return
    }

    const analyst = this.agents().find((a) => a.name === analystName)
    const editor = this.agents().find((a) => a.name === editorName)

    if (!analyst || !editor) {
      // Manual mode: agents not in the list (AgentOS unreachable). Cannot verify.
      this.launchState.clearDerivedRoot()
      return
    }

    this.launchState.deriveTwoAgentRoots(this.namespaceId(), analyst, editor)
  }

  protected isLaunching(): boolean {
    return this.launchStatus() === 'launching'
  }

  protected canSubmit(): boolean {
    if (this.isLaunching()) return false
    if (this.form.controls.task.invalid) return false
    if (this.rootError()) return false
    if (this.rootMismatch()) return false

    // When no namespace root is configured and no root has been derived or typed,
    // we cannot guarantee colocalisation — block the launch.
    const effectiveRoot = this.namespaceRoot() ?? this.derivedRoot() ?? this.form.controls.root.value.trim()
    if (!effectiveRoot) return false

    const wf = this.workflow()

    if (FactoryLaunchStateService.isSingleAgent(wf)) {
      return !!this.form.controls.agent.value.trim()
    }

    if (FactoryLaunchStateService.isTwoAgent(wf)) {
      return !!this.form.controls.analyst.value.trim() && !!this.form.controls.editor.value.trim()
    }

    return true
  }

  protected agentLabel(agent: AgentConfig): string {
    return agent.description ? `${agent.name} — ${agent.description}` : agent.name
  }

  protected submit(): void {
    if (!this.canSubmit()) return

    const wf = this.workflow()
    const domain = this.form.controls.domain.value
    const cmd = this.form.controls.command.value.trim()

    const request: FactoryLaunchRequest = {
      workflow: wf,
      FACTORY_NAMESPACE_ID: this.namespaceId(),
      FACTORY_TASK: this.form.controls.task.value.trim(),
      FACTORY_DOMAIN: domain,
    }

    if (FactoryLaunchStateService.isSingleAgent(wf)) {
      request.FACTORY_AGENT = this.form.controls.agent.value.trim() || undefined
    } else if (FactoryLaunchStateService.isTwoAgent(wf)) {
      const analyst = this.form.controls.analyst.value.trim()
      const editor = this.form.controls.editor.value.trim()
      if (analyst) request.FACTORY_AGENT_ANALYST = analyst
      if (editor) request.FACTORY_AGENT_EDITOR = editor
    }

    const scope = this.form.controls.scope.value.trim()
    if (scope) request.FACTORY_SCOPE = scope

    const ticket = this.form.controls.ticket.value.trim()
    if (ticket) request.FACTORY_TICKET = ticket

    // Priority: namespace configPath > agent-derived root > manually typed.
    const root = this.namespaceRoot() ?? this.derivedRoot() ?? this.form.controls.root.value.trim()
    if (root) request.FACTORY_ROOT = root

    if (cmd && domain === 'front') request.FACTORY_COMMAND_FRONT = cmd
    if (cmd && domain === 'back') request.FACTORY_COMMAND_BACK = cmd

    this.launchState.launch(request)
  }

  protected cancel(): void {
    this.cancelled.emit()
  }
}
