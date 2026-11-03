import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  WritableSignal,
  computed,
  inject,
  signal,
} from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms'
import { ActivatedRoute, Router, RouterLink } from '@angular/router'
import {
  AgentConfig,
  AgentConfigControllerService,
  AgentConfigExecutionModeEnum,
  AgentConfigDefaultsControllerService,
  AgentConfigExportService,
  IntegrationConfig,
  IntegrationTypeControllerService,
  IntegrationTypeDescriptor,
  SkillSummary,
} from '@whoz-oss/agentos-api-client'
import { catchError, forkJoin, map, Observable, of } from 'rxjs'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import {
  ALL_SKILLS_SELECTOR,
  grantsAllSkills,
  namedSelectorKeys,
  normalizeSkillSelector,
} from '../../services/skill-selector.utils'
import {
  ALL_SUB_AGENTS_SELECTOR,
  grantsAllSubAgents,
  isSubAgentPattern,
  namedSubAgentKeys,
} from '../../services/sub-agent-selector.utils'
import { SkillStateService } from '../../services/skill-state.service'
import { CONFIG_AGENT_TYPE, isConfigAgentExposed } from './config-agent-exposure'

/** Convenience alias for the generated enum. */
type ExecutionMode = AgentConfigExecutionModeEnum

/**
 * One line of the sub-agents section: an agent this one may delegate to.
 *
 * Shaped like [SkillRow] and for the same reason — [selector] is what gets written back, [name]
 * what gets displayed, and they differ for an [unresolved] row, where the persisted selector is
 * kept verbatim rather than rewritten into something the form cannot verify.
 *
 * The section offers names because the server resolves the allowlist against names: an id matches
 * nobody and leaves the agent with no delegation tool at all, which it then reports as being unable
 * to delegate rather than as a broken configuration.
 */
interface SubAgentRow {
  /** Value written to AgentConfig.subAgents when the row is selected. */
  selector: string
  name: string
  /** The agent declares this selector but no reachable agent carries that name. */
  unresolved: boolean
  /** The selector is a glob, so it resolves against names this form cannot enumerate. */
  pattern: boolean
  selected: WritableSignal<boolean>
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
 * What an agent says about a built-in integration.
 *
 * `default` is a real, persisted state — the absence of the key in AgentConfig.integrations — and
 * not a synonym for "off": the platform decides for an agent that stays silent. Keeping it as its
 * own state is what stops an agent that was never configured from being silently switched off the
 * first time someone saves an unrelated change on the form.
 */
type BuiltInState = 'default' | 'on' | 'off'

/**
 * A built-in integration (e.g. file exchange) surfaced by the backend in /api/integration-types
 * with `builtIn = true`. Carried in AgentConfig.integrations under its `type` key, whose value
 * follows the backend contract: null enables every tool, an empty array is an explicit opt-out,
 * and a non-empty array restricts the grant to those tool names. The form only edits the first
 * two; a persisted allowlist is surfaced read-only and written back verbatim on save.
 */
interface BuiltInIntegrationRow {
  type: string
  displayName: string
  description: string
  /** What this instance grants when the agent says nothing; labels the `default` state. */
  enabledByDefault: boolean
  state: WritableSignal<BuiltInState>
  /**
   * Non-empty per-tool allowlist persisted outside this form (API or YAML). The form offers no
   * editor for it, so it is shown read-only and re-emitted verbatim while the row stays on `on`;
   * mapping it to null would silently widen the grant to every tool.
   */
  restrictedTools: string[] | null
}

/**
 * One line of the skills section: a skill the agent may be granted.
 *
 * [selector] is what gets written back, [name] what gets displayed. They differ for
 * an [unresolved] row, where the persisted selector is kept verbatim rather than
 * rewritten into a canonical form the form cannot verify.
 */
interface SkillRow {
  /** Value written to AgentConfig.skillSelectors when the row is selected. */
  selector: string
  name: string
  description: string
  /** Platform-level skill, i.e. offered to every namespace. */
  platform: boolean
  /** The agent declares this selector but no catalogue entry matches it. */
  unresolved: boolean
  selected: WritableSignal<boolean>
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
 *
 * ## Skills section
 *
 * Attaches skills to the agent through AgentConfig.skillSelectors. The candidate list
 * is the catalogue the backend would resolve for this agent — `GET /api/skills/effective`
 * in namespace mode, the platform skills in platform mode — so a name offered here
 * cannot resolve differently at run time.
 *
 * No tool toggle accompanies it: `SkillToolGrantService` grants `readSkill` and
 * `readSkillResource` exactly when the resolved catalogue is non-empty, so selecting a
 * skill is the whole opt-in.
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
  private readonly defaultsController = inject(AgentConfigDefaultsControllerService)
  protected readonly defaultDelegationTimeoutSeconds = signal<number | null>(null)

  private readonly exportService = inject(AgentConfigExportService)
  private readonly integrationConfigState = inject(IntegrationConfigStateService)
  private readonly integrationTypeController = inject(IntegrationTypeControllerService)
  private readonly skillState = inject(SkillStateService)

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
    executionMode: new FormControl<ExecutionMode>(AgentConfigExecutionModeEnum.SIMPLE, { nonNullable: true }),
    delegationTimeoutSeconds: new FormControl<number | null>(null, {
      validators: [Validators.min(1), Validators.max(2147483647), Validators.pattern(/^\d+$/)],
    }),
    enabled: new FormControl<boolean>(false, { nonNullable: true }),
    loopConfig: new FormControl<string | null>(null, { validators: [AgentConfigFormComponent.jsonValidator] }),
  })

  /**
   * Validates that the control value is either empty/null or a valid JSON string.
   * Returns `{ invalidJson: true }` when the value is non-empty but cannot be parsed.
   */
  static jsonValidator(control: AbstractControl): ValidationErrors | null {
    const value = control.value?.trim()
    if (!value) return null
    try {
      JSON.parse(value)
      return null
    } catch {
      return { invalidJson: true }
    }
  }

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

  protected get executionModeControl() {
    return this.form.controls.executionMode
  }

  protected get delegationTimeoutControl() {
    return this.form.controls.delegationTimeoutSeconds
  }

  protected useDefaultDelegationTimeout(): void {
    this.delegationTimeoutControl.setValue(null)
    this.delegationTimeoutControl.markAsDirty()
  }

  protected get enabledControl() {
    return this.form.controls.enabled
  }

  protected get loopConfigControl() {
    return this.form.controls.loopConfig
  }

  /**
   * Reactive signal tracking the current execution mode value.
   * Bridges the FormControl observable world into the signal world so that
   * `@if (isLoopMode())` in the OnPush template reacts to select changes.
   */
  private readonly executionModeValue = toSignal(this.form.controls.executionMode.valueChanges, {
    initialValue: this.form.controls.executionMode.value,
  })

  /** True when the selected execution mode is LOOP. */
  protected readonly isLoopMode = computed(() => this.executionModeValue() === AgentConfigExecutionModeEnum.LOOP)

  // ── Sub-agents list ───────────────────────────────────────────────────────

  /** One row per reachable agent, plus one per selector no reachable agent matches. */
  protected readonly subAgentRows = signal<SubAgentRow[]>([])

  /** The `*` selector: delegate to whatever the namespace resolves to, now and later. */
  protected readonly allSubAgentsSelected = signal(false)

  /**
   * True when the reachable agents could not be listed. The rows then come from the agent's own
   * selectors alone, so they must not be labelled unknown — the form simply has nothing to check
   * them against this session.
   */
  protected readonly subAgentCandidatesUnavailable = signal(false)

  /**
   * True when saving would make the selection below redundant by granting `*`.
   *
   * Every pattern is tried server-side and any match wins, so the names would still resolve — but
   * they would no longer decide anything, and the stored list would read as a restriction that is
   * not one. Surfaced before the save rather than discovered after it.
   */
  protected readonly namedSubAgentSelectionDropped = computed(
    () => this.allSubAgentsSelected() && this.subAgentRows().some((r) => r.selected())
  )

  /** Whether this agent ends up with any delegation at all, by name or through `*`. */
  protected readonly hasAnySubAgent = computed(
    () => this.allSubAgentsSelected() || this.subAgentRows().some((r) => r.selected())
  )

  /** Input control for the "add a pattern" field under the list. */
  protected readonly newSubAgentControl = new FormControl<string>('', { nonNullable: true })

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)
  protected readonly isExporting = signal(false)

  /**
   * Why the last save was refused, as the server explained it.
   *
   * The form used to drop the error and only clear its submitting flag, so a refused save looked
   * like nothing happening at all — the caller could not tell a validation refusal from a dead
   * button. The service answers with `server.error.include-message: always`, so the reason it gives
   * is worth showing verbatim.
   */
  protected readonly submitError = signal<string | null>(null)

  /** Expose the enum to the template for option value bindings. */
  protected readonly ExecutionMode = AgentConfigExecutionModeEnum

  /** Placeholder illustrating the AgentLoopPayload shape expected by loopConfig. */
  protected readonly loopConfigPlaceholder = JSON.stringify(
    {
      search: {
        tool: 'SearchTalents',
        params: { talentId: ['6ac76692a909eaaae0b078d6'], resolveTargets: ['MANAGER'], next: null },
      },
      act: {
        agentName: 'ProfileCaretaker',
        promptTemplate:
          'This talent has not completed their profile. As their manager, I need you to help me review and improve it.',
      },
    },
    null,
    2
  )

  /**
   * Built-in integration rows (e.g. file exchange) surfaced by the backend only when their
   * prerequisite plugin is loaded. Each row is a tri-state (platform default / on / off)
   * persisted through AgentConfig.integrations.
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

  /** Whether to warn that this config agent could send what it reads elsewhere: see {@link isConfigAgentExposed}. */
  protected readonly configAgentExposureWarning = computed(() =>
    isConfigAgentExposed({
      configEnabled: this.builtInRows().some((row) => row.type === CONFIG_AGENT_TYPE && row.state() === 'on'),
      integrationTypes: this.integrationRows()
        .filter((row) => row.enabled())
        .map((row) => row.config.integrationType),
      hasSubAgents: this.hasAnySubAgent(),
    })
  )

  /** Integration rows built from the namespace's IntegrationConfig list. */
  protected readonly integrationRows = signal<IntegrationRow[]>([])

  // ── Skills ────────────────────────────────────────────────────────────────

  /** One row per candidate skill, plus one per selector no catalogue entry matches. */
  protected readonly skillRows = signal<SkillRow[]>([])

  /** The `*` selector: grant whatever the catalogue resolves to, now and later. */
  protected readonly allSkillsSelected = signal(false)

  /**
   * True when the catalogue could not be loaded. The rows then come from the agent's
   * own selectors alone, so they must not be labelled unknown — the form simply has
   * nothing to check them against this session.
   */
  protected readonly skillCatalogueUnavailable = signal(false)

  /**
   * True when saving would replace a named selection with `*`. Surfaced in the
   * template so the trade is visible before the user commits to it, rather than
   * discovered after the selectors are gone.
   */
  protected readonly namedSkillSelectionDropped = computed(
    () => this.allSkillsSelected() && this.skillRows().some((r) => r.selected())
  )

  /** Kept for the update payload (preserves server-side fields). */
  private existingConfig: AgentConfig | null = null

  ngOnInit(): void {
    this.defaultsController
      .getAgentConfigDefaults()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => of(null))
      )
      .subscribe((defaults) => this.defaultDelegationTimeoutSeconds.set(defaults?.delegationTimeoutSeconds ?? null))
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
      skillCatalogue: this.loadSkillCatalogue(),
      delegationCandidates: this.loadDelegationCandidates(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({
          config,
          namespaceIntegrations,
          platformIntegrations,
          builtInTypes,
          skillCatalogue,
          delegationCandidates,
        }) => {
          this.existingConfig = config
          this.nameControl.setValue(config.name)
          this.descriptionControl.setValue(config.description ?? null)
          this.modelNameControl.setValue(config.modelName ?? null)
          this.instructionsControl.setValue(config.instructions ?? null)
          // Backward compat: if executionMode is absent but advancedExecution is true, infer ADVANCED.
          const mode: ExecutionMode =
            config.executionMode ??
            (config.advancedExecution ? AgentConfigExecutionModeEnum.ADVANCED : AgentConfigExecutionModeEnum.SIMPLE)
          this.executionModeControl.setValue(mode)
          this.delegationTimeoutControl.setValue(config.delegationTimeoutSeconds ?? null)
          this.enabledControl.setValue(config.enabled ?? true)
          this.loopConfigControl.setValue(config.loopConfig != null ? JSON.stringify(config.loopConfig, null, 2) : null)
          const allIntegrations = [...platformIntegrations, ...namespaceIntegrations]
          this.integrationRows.set(this.buildIntegrationRows(allIntegrations, config.integrations ?? undefined))
          this.builtInRows.set(this.buildBuiltInRows(builtInTypes, config.integrations ?? undefined))
          this.allSubAgentsSelected.set(grantsAllSubAgents(config.subAgents))
          this.subAgentRows.set(this.buildSubAgentRows(delegationCandidates, config.subAgents, config.name))
          this.allSkillsSelected.set(grantsAllSkills(config.skillSelectors))
          this.skillRows.set(this.buildSkillRows(skillCatalogue, config.skillSelectors))
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
      skillCatalogue: this.loadSkillCatalogue(),
      delegationCandidates: this.loadDelegationCandidates(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ namespaceIntegrations, platformIntegrations, builtInTypes, skillCatalogue, delegationCandidates }) => {
          const allIntegrations = [...platformIntegrations, ...namespaceIntegrations]
          this.integrationRows.set(this.buildIntegrationRows(allIntegrations, existingIntegrations ?? undefined))
          this.builtInRows.set(this.buildBuiltInRows(builtInTypes, existingIntegrations ?? undefined))
          this.allSubAgentsSelected.set(false)
          this.subAgentRows.set(this.buildSubAgentRows(delegationCandidates, undefined, null))
          this.allSkillsSelected.set(false)
          this.skillRows.set(this.buildSkillRows(skillCatalogue, undefined))
        },
      })
  }

  /**
   * The candidate skills for this config's scope.
   *
   * Namespace mode asks the backend for the effective catalogue, resolved by the same
   * function the agent runtime uses. Platform mode has no namespace to resolve against,
   * so it can only offer the platform skills: a platform agent's run resolves namespace
   * skills per namespace, and those names surface here as unresolved rows.
   *
   * Fail-safe like [loadBuiltInTypes]: an error yields an empty catalogue and flags
   * [skillCatalogueUnavailable] rather than blocking the whole form.
   */
  private loadSkillCatalogue(): Observable<SkillSummary[]> {
    const catalogue$ = this.namespaceId
      ? this.skillState.listEffective(this.namespaceId)
      : this.skillState.listPlatform().pipe(
          map((skills) =>
            skills.map(
              (s): SkillSummary => ({
                id: s.id ?? '',
                name: s.name,
                description: s.description,
                namespaceId: s.namespaceId ?? null,
                readOnly: s.readOnly === true,
              })
            )
          )
        )
    return catalogue$.pipe(
      catchError((err) => {
        console.error('[agent-config-form] failed to load the skill catalogue', err)
        this.skillCatalogueUnavailable.set(true)
        return of([] as SkillSummary[])
      })
    )
  }

  /**
   * Build one row per catalogue skill, pre-selected from the agent's selectors, then one
   * extra row per selector nothing matched.
   *
   * Those extra rows are the point of the method: a selector naming a skill that was
   * renamed or deleted, or a namespace skill seen from a platform config, stays visible
   * and selected so an unrelated save preserves it verbatim. Dropping it would be a
   * silent edit of someone else's configuration.
   *
   * Matching is case-insensitive, like the shadowing rule server-side.
   *
   * The `*` grant is not a row: the caller reads it from the same selectors into
   * [allSkillsSelected], so this method only maps the named ones.
   */
  private buildSkillRows(catalogue: SkillSummary[], selectors: AgentConfig['skillSelectors']): SkillRow[] {
    const declared = (selectors ?? []).map((s) => s.trim()).filter((s) => s.length > 0)
    const named = declared.filter((s) => s !== ALL_SKILLS_SELECTOR)
    const declaredKeys = namedSelectorKeys(named)
    const catalogueKeys = new Set(catalogue.map((s) => s.name.toLowerCase()))

    const rows: SkillRow[] = catalogue.map((s) => ({
      selector: s.name,
      name: s.name,
      description: s.description,
      platform: s.namespaceId == null,
      unresolved: false,
      selected: signal(declaredKeys.has(s.name.toLowerCase())),
    }))

    const seen = new Set<string>()
    for (const selector of named) {
      const key = normalizeSkillSelector(selector).toLowerCase()
      if (catalogueKeys.has(key) || seen.has(key)) continue
      seen.add(key)
      rows.push({
        selector,
        name: selector,
        description: '',
        platform: false,
        unresolved: true,
        selected: signal(true),
      })
    }
    return rows
  }

  protected toggleSkill(row: SkillRow): void {
    row.selected.update((v) => !v)
  }

  protected toggleAllSkills(): void {
    this.allSkillsSelected.update((v) => !v)
  }

  /**
   * Convert the skills section into the AgentConfig.skillSelectors payload.
   *
   * `*` wins alone: the backend short-circuits on it, so emitting it next to names
   * would store a list whose names cannot be read as having any effect. The template
   * warns while a named selection is about to be replaced this way.
   *
   * Nothing selected → undefined, which clears the field: the section renders every
   * selector the agent has, including the ones it could not resolve, so an empty
   * selection is a choice the user can see having made.
   */
  protected buildSkillSelectorsPayload(): AgentConfig['skillSelectors'] {
    if (this.allSkillsSelected()) return [ALL_SKILLS_SELECTOR]
    const selected = this.skillRows()
      .filter((r) => r.selected())
      .map((r) => r.selector)
    return selected.length > 0 ? selected : undefined
  }

  /**
   * The agents this config may delegate to: the namespace's agents, or the platform agents when
   * editing a platform-level config — the same population the server resolves the allowlist
   * against.
   *
   * Disabled agents are included. An allowlist entry is a durable choice, and leaving out an agent
   * that is merely unpublished today would show its selector as matching nothing and invite the
   * user to drop it.
   *
   * Fail-safe like [loadSkillCatalogue]: an error yields an empty list and flags
   * [subAgentCandidatesUnavailable] rather than blocking the whole form.
   */
  private loadDelegationCandidates(): Observable<string[]> {
    const agents$ = this.namespaceId
      ? this.agentConfigController.listByParentAgentConfig(this.namespaceId, true)
      : this.agentConfigController.listPlatformAgentsAgentConfig(true)
    return agents$.pipe(
      map((agents) => agents.map((a) => a.name).filter((name): name is string => name.length > 0)),
      catchError((err) => {
        console.error('[agent-config-form] failed to load the delegation candidates', err)
        this.subAgentCandidatesUnavailable.set(true)
        return of([] as string[])
      })
    )
  }

  /**
   * Build one row per reachable agent, pre-selected from the agent's selectors, then one extra row
   * per selector nothing matched.
   *
   * Those extra rows are the point of the method: a selector naming an agent that was renamed or
   * deleted, a glob such as `*Fixer`, or an id written by a configuring agent before the server
   * refused them, all stay visible and selected so an unrelated save preserves them verbatim.
   * Dropping one would be a silent edit of someone else's configuration — and, for the id, would
   * hide the very reason delegation is not working.
   *
   * Matching is case-insensitive, like the server's glob match, so a selector stored as `reviewer`
   * ticks the row of the agent named `Reviewer` instead of adding an unresolved twin. Writing back
   * then stores the agent's own spelling, which is the same allowlist either way.
   *
   * [selfName] is withheld from the offered names so the form never proposes an agent delegating to
   * itself; a config that already names itself keeps it as a row, so nothing is lost.
   *
   * The `*` grant is not a row: the caller reads it from the same selectors into
   * [allSubAgentsSelected], so this method only maps the named ones.
   */
  private buildSubAgentRows(
    candidateNames: string[],
    selectors: AgentConfig['subAgents'],
    selfName: string | null
  ): SubAgentRow[] {
    const offered = candidateNames.filter((name) => name !== selfName)
    const declaredKeys = namedSubAgentKeys(selectors)
    const offeredKeys = new Set(offered.map((name) => name.toLowerCase()))

    const rows: SubAgentRow[] = offered.map((name) => ({
      selector: name,
      name,
      unresolved: false,
      pattern: false,
      selected: signal(declaredKeys.has(name.toLowerCase())),
    }))

    const seen = new Set<string>()
    const named = (selectors ?? []).map((s) => s.trim()).filter((s) => s.length > 0 && s !== ALL_SUB_AGENTS_SELECTOR)
    for (const selector of named) {
      const key = selector.toLowerCase()
      if (offeredKeys.has(key) || seen.has(key)) continue
      seen.add(key)
      rows.push({
        selector,
        name: selector,
        unresolved: true,
        pattern: isSubAgentPattern(selector),
        selected: signal(true),
      })
    }
    return rows
  }

  /**
   * Resolve whether the file-exchange plugin is loaded by looking for a `FILE_ACCESS`
   * integration type. Resilient: any error (e.g. endpoint unavailable) resolves to an empty
   * list so it never blocks the integrations load, and the built-in rows stay hidden (fail-safe).
   */
  private loadBuiltInTypes(): Observable<IntegrationTypeDescriptor[]> {
    return this.integrationTypeController.listTypesIntegrationType().pipe(
      map((types) => types.filter((t) => !t.configSchema)),
      catchError((err) => {
        // Fail-safe: hide the built-in rows rather than block the form, but leave a diagnostic.
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
   * Build a tri-state row per built-in integration type, hydrated from the agent's integrations map:
   * - key absent          → `default`, the agent expressed no choice and the platform decides;
   * - key with `[]`       → `off`, an explicit opt-out that beats a platform default of on;
   * - key with null       → `on`, every tool allowed;
   * - key with tool names → `on`, restricted to those names; the list is kept on the row so the
   *   save path can write it back verbatim instead of widening it to "all tools".
   */
  private buildBuiltInRows(
    types: IntegrationTypeDescriptor[],
    existingIntegrations: AgentConfig['integrations']
  ): BuiltInIntegrationRow[] {
    return types.map((t) => {
      const declared = existingIntegrations != null && t.type in existingIntegrations
      const entry = existingIntegrations?.[t.type]
      const state: BuiltInState = !declared ? 'default' : Array.isArray(entry) && entry.length === 0 ? 'off' : 'on'
      return {
        type: t.type,
        displayName: t.displayName,
        description: t.description,
        enabledByDefault: t.enabledByDefault === true,
        state: signal(state),
        restrictedTools: Array.isArray(entry) && entry.length > 0 ? entry : null,
      }
    })
  }

  /** Toggle the enabled signal for a given row. */
  protected toggleIntegration(row: IntegrationRow): void {
    row.enabled.update((v) => !v)
  }

  /** Set the tri-state of a built-in integration row from the select. */
  protected setBuiltInState(row: BuiltInIntegrationRow, value: string): void {
    row.state.set(value as BuiltInState)
  }

  /** Label for the `default` option, so the user can see which way it currently resolves. */
  protected builtInDefaultLabel(row: BuiltInIntegrationRow): string {
    return row.enabledByDefault ? 'Platform default (enabled)' : 'Platform default (disabled)'
  }

  /**
   * Add the typed pattern as a selected row, then reset the input.
   *
   * Kept for the globs the checkboxes cannot express — `*Fixer` and the like. A plain name typed
   * here ticks its existing row instead of creating a duplicate, so the list never shows the same
   * agent twice.
   */
  protected addSubAgent(): void {
    const pattern = this.newSubAgentControl.value.trim()
    if (!pattern) return
    const existing = this.subAgentRows().find((r) => r.selector.toLowerCase() === pattern.toLowerCase())
    if (existing) {
      existing.selected.set(true)
    } else if (pattern === ALL_SUB_AGENTS_SELECTOR) {
      this.allSubAgentsSelected.set(true)
    } else {
      this.subAgentRows.update((rows) => [
        ...rows,
        {
          selector: pattern,
          name: pattern,
          unresolved: true,
          pattern: isSubAgentPattern(pattern),
          selected: signal(true),
        },
      ])
    }
    this.newSubAgentControl.reset('')
  }

  /** Drop a row entirely. Only ever offered on a row the catalogue did not provide. */
  protected removeSubAgent(row: SubAgentRow): void {
    this.subAgentRows.update((rows) => rows.filter((r) => r !== row))
  }

  protected toggleSubAgent(row: SubAgentRow): void {
    row.selected.update((v) => !v)
  }

  protected toggleAllSubAgents(): void {
    this.allSubAgentsSelected.update((v) => !v)
  }

  /**
   * Convert the sub-agents section into the AgentConfig.subAgents payload.
   *
   * `*` is written alone: every pattern is tried server-side and any match wins, so keeping the
   * names beside it would store a restriction that restricts nothing. The template warns while the
   * trade is still reversible.
   *
   * Nothing selected → undefined, which grants no delegation at all. The section lists every
   * selector the agent has, including the ones it could not resolve, so an empty selection is a
   * choice the user can see having made.
   */
  protected buildSubAgentsPayload(): AgentConfig['subAgents'] {
    if (this.allSubAgentsSelected()) return [ALL_SUB_AGENTS_SELECTOR]
    const selected = this.subAgentRows()
      .filter((r) => r.selected())
      .map((r) => r.selector)
    return selected.length > 0 ? selected : undefined
  }

  /**
   * Convert the integration rows into the AgentConfig.integrations payload.
   *
   * Enabled rows with no tool names → null (all tools allowed).
   * Enabled rows with tool names → trimmed, non-empty string array.
   * Disabled rows → excluded from the map.
   * Built-in rows are tri-state: `on` → null (or the persisted allowlist, preserved verbatim),
   * `off` → [] (explicit opt-out), `default` → key omitted so the platform default keeps deciding.
   * If nothing at all is selected → undefined, which binds no integration: the backend resolver
   * grants no plugin tools, and only the platform exchange defaults can still add the file tools.
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

    // Built-in keys follow the backend contract: null = enabled with every tool, [] = explicit
    // opt-out, non-empty = restricted to those tool names. The form only chooses between the first
    // two, so a persisted allowlist is written back verbatim while the row stays on `on`; mapping
    // it to null would silently grant the agent every tool. A row left on `default` writes no key
    // at all: that absence is what lets the platform default decide, and overwriting it with []
    // would turn "never chosen" into "chosen off" behind the user's back.
    for (const row of this.builtInRows()) {
      const state = row.state()
      if (state === 'on') result[row.type] = row.restrictedTools ?? (null as any)
      else if (state === 'off') result[row.type] = []
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

    this.submitError.set(null)
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
      executionMode: this.executionModeControl.value,
      // Keep advancedExecution for backward compat with older backend versions.
      advancedExecution: this.executionModeControl.value === AgentConfigExecutionModeEnum.ADVANCED,
      enabled: this.enabledControl.value,
      subAgents: this.buildSubAgentsPayload(),
      skillSelectors: this.buildSkillSelectorsPayload(),
      delegationTimeoutSeconds: this.delegationTimeoutControl.value,
      loopConfig: this.buildLoopConfigPayload(),
    } as AgentConfig

    const call$ = this.isEditMode()
      ? this.agentConfigController.updateAgentConfig(this.existingConfig!.id ?? '', payload)
      : this.agentConfigController.createAgentConfig(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: (err: unknown) => {
        this.isSubmitting.set(false)
        this.submitError.set(AgentConfigFormComponent.serverMessage(err))
      },
    })
  }

  /**
   * The server's own explanation of a refused save, or a generic line when it gave none.
   *
   * Reads `message` from the error body, which is where Spring puts the `ResponseStatusException`
   * reason. Anything else — a network failure, an opaque gateway error — has no message to show,
   * and inventing a cause would be worse than admitting there is none.
   */
  private static serverMessage(err: unknown): string {
    const body = (err as { error?: unknown } | null)?.error
    const message = (body as { message?: unknown } | null)?.message
    if (typeof message === 'string' && message.trim().length > 0) return message
    return 'The server refused the save and gave no reason. Check the values and try again.'
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

  /**
   * Parse the loopConfig textarea value into an object for the API payload.
   * Returns undefined when the textarea is empty or null, preserving the existing
   * value from the server (via existingConfig) when the field was never touched.
   * Returns null only when the user explicitly cleared a previously-set value.
   */
  private buildLoopConfigPayload(): object | null | undefined {
    const raw = this.loopConfigControl.value?.trim()
    if (!raw) {
      // If the user cleared the field and there was a previous value, send null to clear it.
      // If there was no previous value, omit the field entirely.
      return this.existingConfig?.loopConfig != null ? null : undefined
    }
    try {
      return JSON.parse(raw) as object
    } catch {
      // Validator should have caught this; guard anyway.
      return undefined
    }
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
