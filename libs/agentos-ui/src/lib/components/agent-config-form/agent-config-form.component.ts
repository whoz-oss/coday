import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, WritableSignal, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router, RouterLink } from '@angular/router'
import {
  AgentConfig,
  AgentConfigControllerService,
  AgentConfigExportService,
  IntegrationConfig,
  IntegrationTypeControllerService,
  IntegrationTypeDescriptor,
} from '@whoz-oss/agentos-api-client'
import { catchError, forkJoin, map, Observable, of } from 'rxjs'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'

/** Represents one sub-agent glob pattern entry in the list. */
interface SubAgentRow {
  control: FormControl<string>
}

/**
 * Tracks the per-integration state within the form:
 * - `enabled`: signal-based toggle so OnPush change detection reacts immediately
 * - `toolsControl`: free-text input for comma-separated tool names (empty = all tools allowed)
 */
interface IntegrationRow {
  config: IntegrationConfig
  enabled: WritableSignal<boolean>
  toolsControl: FormControl<string>
}

/**
 * A built-in integration (e.g. file exchange) surfaced by the backend in /api/integration-types
 * with `builtIn = true`. Enabled by adding its `type` to AgentConfig.integrations — no instance,
 * no per-tool allowlist (toggle only).
 */
interface BuiltInIntegrationRow {
  type: string
  displayName: string
  description: string
  enabled: WritableSignal<boolean>
}

/**
 * AgentConfigFormComponent — full-page create / edit form for an agent config.
 *
 * Mode is determined by the presence of `:agentConfigId` in the route params:
 * - `/:namespaceId/agent-configs/new`                      → create mode
 * - `/:namespaceId/agent-configs/:agentConfigId/edit`       → edit mode
 *
 * The namespaceId is fixed at creation time and never exposed as an editable field.
 * On success or cancel, navigates back to /:namespaceId/agent-configs.
 *
 * ## Integrations section
 *
 * The form loads the namespace's IntegrationConfig list and lets the user select
 * which integrations the agent can use. For each selected integration, an optional
 * comma-separated list of tool names restricts access further (empty = all tools).
 *
 * This maps directly to AgentConfig.integrations:
 *   { integrationName: string[] | null }
 * where null means all tools of that integration are allowed.
 */
@Component({
  selector: 'agentos-agent-config-form',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './agent-config-form.component.html',
  styleUrl: './agent-config-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentConfigFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly agentConfigController = inject(AgentConfigControllerService)
  private readonly exportService = inject(AgentConfigExportService)
  private readonly integrationConfigState = inject(IntegrationConfigStateService)
  private readonly integrationTypeController = inject(IntegrationTypeControllerService)

  /**
   * namespaceId from the route, or undefined when operating in platform mode
   * (route is /admin/agent-configs/... and has no :namespaceId segment).
   */
  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined

  /** True when editing/creating a platform-level config (namespaceId IS NULL). */
  protected readonly isPlatformMode = !this.namespaceId

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string | null>(null),
    modelName: new FormControl<string | null>(null),
    instructions: new FormControl<string | null>(null),
    advancedExecution: new FormControl<boolean>(false, { nonNullable: true }),
    enabled: new FormControl<boolean>(false, { nonNullable: true }),
  })

  protected get nameControl() {
    return this.form.controls.name
  }

  protected get descriptionControl() {
    return this.form.controls.description
  }

  protected get modelNameControl() {
    return this.form.controls.modelName
  }

  protected get instructionsControl() {
    return this.form.controls.instructions
  }

  protected get advancedExecutionControl() {
    return this.form.controls.advancedExecution
  }

  protected get enabledControl() {
    return this.form.controls.enabled
  }

  // ── Sub-agents list ───────────────────────────────────────────────────────

  /** Current list of sub-agent glob patterns, each backed by a live FormControl. */
  protected readonly subAgentRows = signal<SubAgentRow[]>([])

  /** Input control for the "add new pattern" field at the bottom of the list. */
  protected readonly newSubAgentControl = new FormControl<string>('', { nonNullable: true })

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)
  protected readonly isExporting = signal(false)

  /**
   * Built-in integration rows (e.g. file exchange) surfaced by the backend only when their
   * prerequisite plugin is loaded. Toggled on/off; enablement rides AgentConfig.integrations.
   */
  protected readonly builtInRows = signal<BuiltInIntegrationRow[]>([])

  /** Route to the inspect view — only valid in edit mode (agentConfigId is present). */
  protected inspectRoute(): string[] {
    const agentConfigId = this.route.snapshot.paramMap.get('agentConfigId') ?? ''
    if (this.isPlatformMode) {
      return ['/agentos', 'admin', 'agent-configs', agentConfigId, 'inspect']
    }
    return ['/agentos', this.namespaceId!, 'agent-configs', agentConfigId, 'inspect']
  }

  /** Integration rows built from the namespace's IntegrationConfig list. */
  protected readonly integrationRows = signal<IntegrationRow[]>([])

  /** Kept for the update payload (preserves server-side fields). */
  private existingConfig: AgentConfig | null = null

  ngOnInit(): void {
    const agentConfigId = this.route.snapshot.paramMap.get('agentConfigId')
    if (agentConfigId) {
      this.isEditMode.set(true)
      this.loadConfigAndIntegrations(agentConfigId)
    } else {
      this.loadIntegrations(undefined)
    }
  }

  /**
   * In edit mode: load the agent config, the integrations, and hydrate both the main
   * form and the integration rows.
   *
   * In platform mode: only platform integration configs are available (no namespace).
   * In namespace mode: platform + namespace integrations are merged.
   */
  private loadConfigAndIntegrations(agentConfigId: string): void {
    this.isLoading.set(true)

    forkJoin({
      config: this.agentConfigController.getByIdAgentConfig(agentConfigId),
      namespaceIntegrations: this.namespaceId
        ? this.integrationConfigState.loadNamespaceConfigs(this.namespaceId)
        : of([]),
      platformIntegrations: this.integrationConfigState.loadPlatformConfigs(),
      builtInTypes: this.loadBuiltInTypes(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ config, namespaceIntegrations, platformIntegrations, builtInTypes }) => {
          this.existingConfig = config
          this.nameControl.setValue(config.name)
          this.descriptionControl.setValue(config.description ?? null)
          this.modelNameControl.setValue(config.modelName ?? null)
          this.instructionsControl.setValue(config.instructions ?? null)
          this.advancedExecutionControl.setValue(config.advancedExecution ?? false)
          this.enabledControl.setValue(config.enabled ?? true)
          const allIntegrations = [...platformIntegrations, ...namespaceIntegrations]
          this.integrationRows.set(this.buildIntegrationRows(allIntegrations, config.integrations ?? undefined))
          this.builtInRows.set(this.buildBuiltInRows(builtInTypes, config.integrations ?? undefined))
          this.subAgentRows.set(
            (config.subAgents ?? []).map((p) => ({ control: new FormControl<string>(p, { nonNullable: true }) }))
          )
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  /**
   * In create mode: load platform + namespace integrations and merge them.
   * Platform configs are visible to non-admins (backend returns [] for them, not 403).
   */
  private loadIntegrations(existingIntegrations: AgentConfig['integrations']): void {
    forkJoin({
      namespaceIntegrations: this.namespaceId
        ? this.integrationConfigState.loadNamespaceConfigs(this.namespaceId)
        : of([]),
      platformIntegrations: this.integrationConfigState.loadPlatformConfigs(),
      builtInTypes: this.loadBuiltInTypes(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ namespaceIntegrations, platformIntegrations, builtInTypes }) => {
          const allIntegrations = [...platformIntegrations, ...namespaceIntegrations]
          this.integrationRows.set(this.buildIntegrationRows(allIntegrations, existingIntegrations ?? undefined))
          this.builtInRows.set(this.buildBuiltInRows(builtInTypes, existingIntegrations ?? undefined))
        },
      })
  }

  /**
   * Resolve whether the file-exchange plugin is loaded by looking for a `FILE_ACCESS`
   * integration type. Resilient: any error (e.g. endpoint unavailable) resolves to false
   * so it never blocks the integrations load — and the checkboxes stay hidden (fail-safe).
   */
  private loadBuiltInTypes(): Observable<IntegrationTypeDescriptor[]> {
    return this.integrationTypeController.listTypesIntegrationType().pipe(
      map((types) => types.filter((t) => t.builtIn === true)),
      catchError((err) => {
        // Fail-safe: hide the built-in toggles rather than block the form, but leave a diagnostic.
        console.error('[agent-config-form] failed to load integration types', err)
        return of([])
      })
    )
  }

  /**
   * Build an IntegrationRow for each available integration config.
   *
   * If existingIntegrations is provided (edit mode), pre-populate:
   * - enabled = true when the integration name appears as a key
   * - toolsControl = comma-joined tool names when the list is non-null, else empty
   */
  private buildIntegrationRows(
    available: IntegrationConfig[],
    existingIntegrations: AgentConfig['integrations']
  ): IntegrationRow[] {
    return available.map((config) => {
      const name = config.name ?? ''
      const existingEntry = existingIntegrations?.[name]
      const isEnabled = existingIntegrations != null && name in existingIntegrations
      const toolsValue = Array.isArray(existingEntry) ? existingEntry.join(', ') : ''
      return {
        config,
        enabled: signal(isEnabled),
        toolsControl: new FormControl<string>(toolsValue, { nonNullable: true }),
      }
    })
  }

  /**
   * Build a toggle row per built-in integration type, pre-checked when the agent's integrations
   * map already contains its `type` key.
   */
  private buildBuiltInRows(
    types: IntegrationTypeDescriptor[],
    existingIntegrations: AgentConfig['integrations']
  ): BuiltInIntegrationRow[] {
    return types.map((t) => ({
      type: t.type,
      displayName: t.displayName,
      description: t.description,
      enabled: signal(existingIntegrations != null && t.type in existingIntegrations),
    }))
  }

  /** Toggle the enabled signal for a given row. */
  protected toggleIntegration(row: IntegrationRow): void {
    row.enabled.update((v) => !v)
  }

  /** Toggle a built-in integration row. */
  protected toggleBuiltIn(row: BuiltInIntegrationRow): void {
    row.enabled.update((v) => !v)
  }

  /** Add the current newSubAgentControl value as a new row, then reset the input. */
  protected addSubAgent(): void {
    const pattern = this.newSubAgentControl.value.trim()
    if (!pattern) return
    this.subAgentRows.update((rows) => [...rows, { control: new FormControl<string>(pattern, { nonNullable: true }) }])
    this.newSubAgentControl.reset('')
  }

  /** Remove a row from the list. */
  protected removeSubAgent(row: SubAgentRow): void {
    this.subAgentRows.update((rows) => rows.filter((r) => r !== row))
  }

  /**
   * Convert the sub-agent rows into the AgentConfig.subAgents payload.
   * Empty list → undefined (no delegation capability).
   */
  protected buildSubAgentsPayload(): AgentConfig['subAgents'] {
    const patterns = this.subAgentRows()
      .map((r) => r.control.value.trim())
      .filter((p) => p.length > 0)
    return patterns.length > 0 ? patterns : undefined
  }

  /**
   * Convert the integration rows into the AgentConfig.integrations payload.
   *
   * Enabled rows with no tool names → null (all tools allowed).
   * Enabled rows with tool names → trimmed, non-empty string array.
   * Disabled rows → excluded from the map.
   * If no rows are enabled → undefined (no filter, agent sees all namespace tools).
   */

  protected buildIntegrationsPayload(): AgentConfig['integrations'] {
    // null per integration means "all tools allowed" (backend contract). The generated TS type
    // declares Array<string> but the backend accepts null; springdoc does not emit nullable:true
    // on Map additionalProperties for Kotlin nullable generics, so the cast is intentional.
    const result: { [key: string]: Array<string> } = {}

    for (const row of this.integrationRows().filter((r) => r.enabled())) {
      const name = row.config.name ?? ''
      const rawTools = row.toolsControl.value.trim()
      result[name] =
        rawTools === ''
          ? (null as any)
          : rawTools
              .split(',')
              .map((t) => t.trim())
              .filter((t) => t.length > 0)
    }

    // Built-in integrations are toggle-only: their type key with a null value (= all tools).
    for (const row of this.builtInRows().filter((r) => r.enabled())) {
      result[row.type] = null as any
    }

    // Preserve any existing integration key the form could not represent this session — e.g. a
    // built-in whose type list failed to load (loadBuiltInTypes falls back to []), or an integration
    // config no longer available. Rebuilding the map purely from the visible rows would otherwise
    // silently drop such a key when the user saves unrelated changes.
    const renderable = new Set<string>([
      ...this.integrationRows().map((r) => r.config.name ?? ''),
      ...this.builtInRows().map((r) => r.type),
    ])
    for (const [key, value] of Object.entries(this.existingConfig?.integrations ?? {})) {
      if (!renderable.has(key)) result[key] = value as any
    }

    return Object.keys(result).length > 0 ? result : undefined
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    // Flush the "add pattern" input: if the user typed a pattern and clicked Save
    // without pressing "+" or Enter, we include it rather than silently dropping it.
    this.addSubAgent()

    this.isSubmitting.set(true)

    // createdOn / updatedOn are server-set audit fields — omitted on create, preserved from
    // existingConfig on update via the spread. Cast is intentional: the backend accepts the
    // payload without these fields in create mode.
    // In platform mode, namespaceId is intentionally undefined (platform-level config).
    const payload = {
      ...(this.existingConfig ?? {}),
      // `createdOn` / `updatedOn` are server-managed timestamps — omit them in the FE
      // payload; the backend ignores them on create and preserves them on update.
      // Cast needed because the generated model marks them as required (non-optional)
      // but the backend does not require them in the request body.
      createdOn: this.existingConfig?.createdOn as string,
      updatedOn: this.existingConfig?.updatedOn as string,
      namespaceId: this.namespaceId,
      name: this.nameControl.value.trim(),
      description: this.descriptionControl.value?.trim() || undefined,
      modelName: this.modelNameControl.value?.trim() || undefined,
      instructions: this.instructionsControl.value?.trim() || undefined,
      integrations: this.buildIntegrationsPayload(),
      advancedExecution: this.advancedExecutionControl.value,
      enabled: this.enabledControl.value,
      subAgents: this.buildSubAgentsPayload(),
    } as AgentConfig

    const call$ = this.isEditMode()
      ? this.agentConfigController.updateAgentConfig(this.existingConfig!.id ?? '', payload)
      : this.agentConfigController.createAgentConfig(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected exportYaml(): void {
    const id = this.existingConfig?.id
    if (!id || this.isExporting()) return

    this.isExporting.set(true)
    this.exportService
      .exportAsYaml(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (yaml) => {
          const blob = new Blob([yaml], { type: 'application/yaml' })
          const url = URL.createObjectURL(blob)
          const anchor = document.createElement('a')
          anchor.href = url
          anchor.download = `${this.existingConfig?.name ?? 'agent'}.yaml`
          anchor.click()
          URL.revokeObjectURL(url)
          this.isExporting.set(false)
        },
        error: () => this.isExporting.set(false),
      })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    if (this.isPlatformMode) {
      this.router.navigate(['/agentos', 'admin', 'agent-configs'])
    } else {
      this.router.navigate(['/agentos', this.namespaceId, 'agent-configs'])
    }
  }
}
