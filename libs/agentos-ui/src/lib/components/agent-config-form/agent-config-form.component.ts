import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, WritableSignal, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfig,
  AgentConfigControllerService,
  AiModelAliasService,
  IntegrationConfig,
  IntegrationConfigControllerService,
} from '@whoz-oss/agentos-api-client'
import { catchError, forkJoin, of } from 'rxjs'

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
 * AgentConfigFormComponent — full-page create / edit form for an agent config.
 *
 * Mode is determined by the presence of `:agentConfigId` in the route params:
 * - `/:namespaceId/agent-configs/new`                      → create mode
 * - `/:namespaceId/agent-configs/:agentConfigId/edit`       → edit mode
 *
 * The namespaceId is fixed at creation time and never exposed as an editable field.
 * On success or cancel, navigates back to /:namespaceId/agent-configs.
 *
 * ## LLM model field
 *
 * The `modelName` field is rendered as an optional dropdown populated from
 * `GET /api/ai-models/aliases-by-namespaceId/{namespaceId}`. Selecting no alias
 * (the empty “— default —” option) leaves the field null, letting the backend apply
 * the namespace’s default alias. If the aliases endpoint is unavailable (e.g.
 * backend not yet deployed), the dropdown degrades gracefully to an empty list
 * showing only the default option.
 *
 * An existing agent whose `modelName` does not match any current alias is still
 * displayed correctly — the value is preserved in the form control and submitted
 * as-is if the user saves without changing the field.
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
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './agent-config-form.component.html',
  styleUrl: './agent-config-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentConfigFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly agentConfigController = inject(AgentConfigControllerService)
  private readonly integrationConfigController = inject(IntegrationConfigControllerService)
  private readonly aiModelAliasService = inject(AiModelAliasService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string | null>(null),
    modelName: new FormControl<string | null>(null),
    instructions: new FormControl<string | null>(null),
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

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /** Integration rows built from the namespace's IntegrationConfig list. */
  protected readonly integrationRows = signal<IntegrationRow[]>([])

  /**
   * Available LLM model aliases for the namespace.
   * An empty array means no aliases are configured — only the default option is shown.
   */
  protected readonly availableAliases = signal<string[]>([])

  /** Kept for the update payload (preserves server-side fields). */
  private existingConfig: AgentConfig | null = null

  ngOnInit(): void {
    const agentConfigId = this.route.snapshot.paramMap.get('agentConfigId')
    if (agentConfigId) {
      this.isEditMode.set(true)
      this.loadConfigAndIntegrations(agentConfigId)
    } else {
      this.loadIntegrationsAndAliases(undefined)
    }
  }

  /**
   * In edit mode: load the agent config, namespace integrations, and available
   * aliases in parallel, then hydrate the main form and integration rows.
   */
  private loadConfigAndIntegrations(agentConfigId: string): void {
    this.isLoading.set(true)
    forkJoin({
      config: this.agentConfigController.getByIdAgentConfig(agentConfigId),
      integrations: this.integrationConfigController.listByParentIntegrationConfig(this.namespaceId),
      aliases: this.aiModelAliasService
        .listAliasesByNamespace(this.namespaceId)
        .pipe(catchError(() => of([] as string[]))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ config, integrations, aliases }) => {
          this.existingConfig = config
          this.nameControl.setValue(config.name)
          this.descriptionControl.setValue(config.description ?? null)
          this.modelNameControl.setValue(config.modelName ?? null)
          this.instructionsControl.setValue(config.instructions ?? null)
          this.integrationRows.set(this.buildIntegrationRows(integrations, config.integrations ?? undefined))
          this.availableAliases.set(aliases)
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  /** In create mode: load namespace integrations and available aliases. */
  private loadIntegrationsAndAliases(existingIntegrations: AgentConfig['integrations']): void {
    forkJoin({
      integrations: this.integrationConfigController.listByParentIntegrationConfig(this.namespaceId),
      aliases: this.aiModelAliasService
        .listAliasesByNamespace(this.namespaceId)
        .pipe(catchError(() => of([] as string[]))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ integrations, aliases }) => {
          this.integrationRows.set(this.buildIntegrationRows(integrations, existingIntegrations ?? undefined))
          this.availableAliases.set(aliases)
        },
      })
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

  /** Toggle the enabled signal for a given row. */
  protected toggleIntegration(row: IntegrationRow): void {
    row.enabled.update((v) => !v)
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
    const rows = this.integrationRows()
    const enabledRows = rows.filter((r) => r.enabled())
    if (enabledRows.length === 0) return undefined

    const result: { [key: string]: Array<string> } = {}
    for (const row of enabledRows) {
      const name = row.config.name ?? ''
      const rawTools = row.toolsControl.value.trim()
      if (rawTools === '') {
        // null means all tools allowed for this integration (backend contract).
        // The generated TS type declares Array<string> but the backend accepts null
        // per integration to mean "all tools allowed". The spec does not yet express
        // this nullable value because springdoc does not emit nullable:true on Map
        // additionalProperties for Kotlin nullable generics. Cast is intentional.
        result[name] = null as any
      } else {
        result[name] = rawTools
          .split(',')
          .map((t) => t.trim())
          .filter((t) => t.length > 0)
      }
    }
    return result
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    this.isSubmitting.set(true)

    const payload: AgentConfig = {
      ...(this.existingConfig ?? {}),
      namespaceId: this.namespaceId,
      name: this.nameControl.value.trim(),
      description: this.descriptionControl.value?.trim() ?? undefined,
      modelName: this.modelNameControl.value?.trim() ?? undefined,
      instructions: this.instructionsControl.value?.trim() ?? undefined,
      integrations: this.buildIntegrationsPayload(),
    }

    const call$ = this.isEditMode()
      ? this.agentConfigController.updateAgentConfig(this.existingConfig!.id ?? '', payload)
      : this.agentConfigController.createAgentConfig(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'agent-configs'])
  }
}
