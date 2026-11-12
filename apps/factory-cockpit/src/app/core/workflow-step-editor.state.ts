import { Injectable, computed, inject, signal } from '@angular/core'
import { finalize } from 'rxjs'
import { FactoryApiError, FactoryApiService, FullWorkflowDefinition, FullWorkflowStep, NamespaceOption, extractNamespaceOptions } from './factory-api.service'

export interface StepDraft {
  entry: string[]
  mission: string
  exit: string[]
}

export function explicitContract(step: FullWorkflowStep): StepDraft | null {
  return Array.isArray(step['entry']) && typeof step['mission'] === 'string' && Array.isArray(step['exit'])
    ? { entry: [...step['entry']], mission: step['mission'], exit: [...step['exit']] }
    : null
}

/** Deliberate adoption of the generic legacy lane; special runtime-derived briefs cannot be copied safely. */
export function legacyAdoption(step: FullWorkflowStep): StepDraft | null {
  if (step.responsibility?.kind !== 'agent' || ['Gitay', 'A11yReviewer'].includes(step.responsibility.name ?? '')) {
    return null
  }
  const root = !step.dependsOn?.length
  return {
    entry: root
      ? [
          'Frame the handoff: objective, scope and exclusions, user/workflow criteria, constraints and open questions.',
          'If the initial intention is absent or ambiguous, call queryUser and wait. Do not submit FAIL merely for clarification. If unavailable, do not guess or widen scope; report the blocker as a justified terminal failure only when no supported continuation exists.',
          'Do not invent criteria or turn a request for analysis into implementation.',
        ]
      : [
          'Consume the structured dependency inputs and run-brief handoff. The original user request remains the governing intention.',
        ],
    mission: step.name,
    exit: [
      ...(root
        ? [
            "Include a durable Markdown artifact in artifacts with kind 'run-brief', encoding 'markdown', and sections: Objectif, Périmètre et exclusions, Critères, Contraintes, Questions ouvertes.",
          ]
        : [
            'Preserve every run-brief artifact received from direct dependencies unchanged in artifacts. Add separate artifacts for this step; never rewrite or merge independent framings.',
          ]),
      'Choose deliverables from the work actually requested: analysis/design requires reasoned findings, decisions/options, constraints, open questions and a durable Markdown artifact; do not implement unless requested.',
      'Implementation requires scoped changes and concrete verification evidence available under the run policy; do not claim checks that were not run.',
      'Review requires prioritized findings with locations/evidence and residual risks; do not modify code unless requested.',
    ],
  }
}

export function validateDraft(draft: StepDraft): string | null {
  if (!draft.mission.trim() || draft.mission.length > 16000) return 'Prompt must contain 1–16,000 characters.'
  for (const [label, items] of [
    ['Pre-actions', draft.entry],
    ['Post-actions', draft.exit],
  ] as const) {
    if (items.length > 50 || items.some((item) => !item.trim() || item.length > 4000)) {
      return `${label}: at most 50 actions, each containing 1–4,000 characters. Remove empty actions.`
    }
  }
  return null
}

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/

/** Helpful early feedback only: registration always uses the canonical backend validator. */
export function validateStructure(definition: FullWorkflowDefinition): string | null {
  if (!SAFE_ID.test(definition.workflowType ?? '')) return 'Workflow type: use 1–128 letters, digits, dots, underscores or hyphens, starting with a letter or digit.'
  if (!definition.title?.trim() || definition.title.length > 256) return 'Workflow name must contain 1–256 characters.'
  const steps = definition.steps ?? []
  if (!steps.length || steps.length > 500) return 'Add between 1 and 500 blocks.'
  const ids = new Set(steps.map((step) => step.id))
  if (ids.size !== steps.length) return 'Duplicate block IDs are not allowed.'
  for (const step of steps) {
    if (!SAFE_ID.test(step.id)) return `Invalid block ID: ${step.id}`
    if (!step.name.trim() || step.name.length > 256) return `${step.id}: block name must contain 1–256 characters.`
    if (!step.responsibility?.name?.trim()) return `${step.id}: ${step.responsibility?.kind === 'human' ? 'enter a human role.' : 'select an agent (or preserve the existing code responsibility).'}`
    if (step.dependsOn?.some((id) => !ids.has(id))) return `${step.id}: missing predecessor.`
    if (new Set(step.dependsOn).size !== (step.dependsOn?.length ?? 0)) return `${step.id}: duplicate predecessor.`
    const contract = explicitContract(step)
    if (contract) {
      const error = validateDraft(contract)
      if (error) return `${step.id}: ${error}`
    }
  }
  const visited = new Set<string>()
  const visiting = new Set<string>()
  const index = new Map(steps.map((step) => [step.id, step]))
  function cycle(id: string): boolean {
    if (visiting.has(id)) return true
    if (visited.has(id)) return false
    visiting.add(id)
    if (index.get(id)?.dependsOn?.some(cycle)) return true
    visiting.delete(id)
    visited.add(id)
    return false
  }
  if (steps.some((step) => cycle(step.id))) return 'Dependency cycle: remove a predecessor connection.'
  return validateRouting(steps)
}

export function validateRouting(steps: FullWorkflowStep[]): string | null {
  const decisions = steps.filter((step) => step.decision)
  const joins = steps.filter((step) => step.join)
  if (!decisions.length && !joins.length) return null
  if (decisions.length !== 1 || joins.length !== 1) return 'Routing requires exactly one decision and one explicit join.'
  const decision = decisions[0]!
  const join = joins[0]!
  const config = decision.decision!
  if (decision.responsibility?.kind !== 'agent' || !['single', 'multiple'].includes(config.mode)) return 'Decision requires an agent and single/multiple mode.'
  const routes = config.routes
  if (routes.length < 2 || routes.length > 16 || routes.some((route) => !SAFE_ID.test(route.name) || !SAFE_ID.test(route.target)) ||
    new Set(routes.map((route) => route.name)).size !== routes.length || new Set(routes.map((route) => route.target)).size !== routes.length) return 'Configure 2–16 unique route names and distinct targets.'
  if (join.join !== decision.id || join.id === decision.id) return 'Join must reference the decision.'
  const targets = routes.map((route) => route.target)
  for (const target of targets) {
    const branch = steps.find((step) => step.id === target)
    if (!branch || [decision.id, join.id].includes(target) || branch.dependsOn?.length !== 1 || branch.dependsOn[0] !== decision.id) return 'Each route targets one step depending only on the decision.'
    if (steps.filter((step) => step.dependsOn?.includes(target)).some((step) => step.id !== join.id) || !join.dependsOn?.includes(target)) return 'Every branch must lead only and directly to the explicit join. Multi-step/nested branches are not supported.'
  }
  if (steps.some((step) => step.dependsOn?.includes(decision.id) && !targets.includes(step.id) && step.id !== join.id)) return 'Decision fanout must use named routes.'
  return null
}

/** Page-scoped composer. Existing definitions are immutable; edits only create a new version. */
@Injectable()
export class WorkflowStepEditorState {
  private readonly api = inject(FactoryApiService)
  private baseline: FullWorkflowDefinition | null = null
  readonly creating = signal(false)
  readonly editing = signal(false)
  readonly structureDirty = signal(false)
  readonly namespaces = signal<NamespaceOption[]>([])
  readonly namespaceId = signal('')
  readonly agents = signal<string[]>([])
  readonly agentsLoading = signal(false)
  readonly discoveryError = signal<string | null>(null)
  private discoverySequence = 0
  readonly definition = signal<FullWorkflowDefinition | null>(null)
  readonly selectedId = signal<string | null>(null)
  readonly drafts = signal<Record<string, StepDraft>>({})
  readonly loading = signal(false)
  readonly saving = signal(false)
  readonly error = signal<string | null>(null)
  readonly saved = signal<string | null>(null)
  readonly targetVersion = signal('')
  readonly selected = computed(() => this.definition()?.steps?.find((step) => step.id === this.selectedId()) ?? null)
  readonly draft = computed(() => {
    const step = this.selected()
    return step ? (this.drafts()[step.id] ?? explicitContract(step)) : null
  })
  readonly dirty = computed(() => this.structureDirty() || Object.keys(this.drafts()).length > 0 || this.targetVersion() !== (this.creating() ? '1.0.0' : ''))
  readonly validation = computed(() => {
    for (const draft of Object.values(this.drafts())) {
      const error = validateDraft(draft)
      if (error) return error
    }
    const definition = this.preview()
    return this.structureDirty() && definition ? validateStructure(definition) : null
  })
  readonly preview = computed(() => {
    const definition = this.definition()
    return definition ? { ...definition, steps: definition.steps?.map((step) => ({ ...step, ...this.drafts()[step.id] })) } : null
  })

  startNew(): void {
    this.creating.set(true)
    this.editing.set(true)
    this.baseline = { schemaVersion: '1', workflowType: '', version: '', title: '', steps: [] }
    this.definition.set(this.baseline)
    this.targetVersion.set('1.0.0')
  }

  loadDiscovery(): void {
    this.api.getNamespaces().subscribe({
      next: (items) => this.namespaces.set(extractNamespaceOptions(items)),
      error: (error: FactoryApiError) => this.discoveryError.set(error.message || 'Unable to load namespaces.'),
    })
  }

  discoverAgents(namespaceId: string): void {
    this.namespaceId.set(namespaceId)
    this.agents.set([])
    this.discoveryError.set(null)
    const sequence = ++this.discoverySequence
    this.agentsLoading.set(!!namespaceId)
    if (!namespaceId) return
    this.api.getAgents(namespaceId).subscribe({
      next: (items) => {
        if (sequence !== this.discoverySequence) return
        this.agents.set([...new Set(items.map((item) => item.name))].sort())
        this.agentsLoading.set(false)
      },
      error: (error: FactoryApiError) => {
        if (sequence !== this.discoverySequence) return
        this.discoveryError.set(error.message || 'Unable to discover agents.')
        this.agentsLoading.set(false)
      },
    })
  }

  metadata(field: 'workflowType' | 'title', value: string): void {
    if (field === 'workflowType' && !this.creating()) return
    this.changeDefinition({ ...this.definition(), [field]: value })
  }

  addBlock(kind: 'agent' | 'human' = 'agent'): void {
    if (!this.editing() || this.saving()) return
    const definition = this.definition()
    if (!definition || (definition.steps?.length ?? 0) >= 500) return
    let number = 1
    while (definition.steps?.some((step) => step.id === `step-${number}`)) number++
    const id = `step-${number}`
    this.changeDefinition({ ...definition, steps: [...(definition.steps ?? []), {
      id, name: `${kind === 'human' ? 'Human' : 'Agent'} step ${number}`,
      responsibility: { kind, name: '' }, dependsOn: [],
      ...(kind === 'agent' ? { entry: [], mission: '', exit: [] } : {}),
    }] })
    this.selectedId.set(id)
  }

  updateBlock(patch: Partial<FullWorkflowStep>): void {
    const selected = this.selected()
    if (!this.editing() || !selected || this.saving()) return
    const id = patch.id ?? selected.id
    if (id !== selected.id && (!SAFE_ID.test(id) || this.definition()?.steps?.some((step) => step.id === id))) {
      this.error.set('Block ID must be unique and use 1–128 safe characters, starting with a letter or digit.')
      return
    }
    const steps = this.definition()?.steps?.map((step) => ({
      ...(step.id === selected.id ? { ...step, ...patch } : step),
      dependsOn: (step.id === selected.id ? patch.dependsOn ?? step.dependsOn : step.dependsOn)?.map((dep) => dep === selected.id ? id : dep),
      ...(id !== selected.id && step.decision ? { decision: { ...step.decision, routes: step.decision.routes.map((route) => ({ ...route, target: route.target === selected.id ? id : route.target })) } } : {}),
      ...(id !== selected.id && step.join ? { join: step.join === selected.id ? id : step.join } : {}),
    }))
    if (id !== selected.id) {
      const drafts = { ...this.drafts() }
      const draft = drafts[selected.id]
      if (draft) { drafts[id] = draft; delete drafts[selected.id] }
      this.drafts.set(drafts)
    }
    this.changeDefinition({ ...this.definition(), steps })
    this.selectedId.set(id)
  }

  setRoutingMode(mode: string): void {
    const step = this.selected()
    if (!step || step.responsibility?.kind !== 'agent') return
    if (mode === '') { this.updateBlock({ decision: undefined }); return }
    if (mode !== 'single' && mode !== 'multiple') return
    this.updateBlock({ decision: { mode, routes: step.decision?.routes ?? [{ name: 'frontend', target: '' }, { name: 'backend', target: '' }] } })
  }

  updateRoute(index: number, field: 'name' | 'target', value: string): void {
    const decision = this.selected()?.decision
    if (!decision) return
    this.updateBlock({ decision: { ...decision, routes: decision.routes.map((route, i) => i === index ? { ...route, [field]: value } : route) } })
  }

  addRoute(): void {
    const decision = this.selected()?.decision
    if (decision && decision.routes.length < 16) this.updateBlock({ decision: { ...decision, routes: [...decision.routes, { name: '', target: '' }] } })
  }

  removeRoute(index: number): void {
    const decision = this.selected()?.decision
    if (decision) this.updateBlock({ decision: { ...decision, routes: decision.routes.filter((_, i) => i !== index) } })
  }

  setAgent(name: string): void {
    if (this.selected()?.responsibility?.kind === 'agent' && this.agents().includes(name)) {
      this.updateBlock({ responsibility: { kind: 'agent', name } })
    }
  }

  addHumanContract(): void {
    const step = this.selected()
    if (this.editing() && !this.saving() && step?.responsibility?.kind === 'human' && !this.draft()) {
      this.setDraft(step.id, { entry: [], mission: step.name, exit: [] })
    }
  }

  connect(predecessor: string, checked: boolean): void {
    const step = this.selected()
    if (!step || predecessor === step.id || !this.definition()?.steps?.some((candidate) => candidate.id === predecessor)) return
    this.updateBlock({ dependsOn: checked ? [...new Set([...(step.dependsOn ?? []), predecessor])] : step.dependsOn?.filter((id) => id !== predecessor) })
  }

  removeBlock(): void {
    const selected = this.selected()
    if (!this.editing() || !selected || !['agent', 'human'].includes(selected.responsibility?.kind ?? '') || this.saving()) return
    const steps = this.definition()?.steps?.filter((step) => step.id !== selected.id).map((step) => ({
      ...step, dependsOn: step.dependsOn?.filter((id) => id !== selected.id),
    })) ?? []
    const drafts = { ...this.drafts() }; delete drafts[selected.id]; this.drafts.set(drafts)
    this.changeDefinition({ ...this.definition(), steps })
    this.selectedId.set(steps[0]?.id ?? null)
  }

  private changeDefinition(definition: FullWorkflowDefinition): void {
    if (!this.editing() || this.saving()) return
    this.definition.set(definition)
    this.structureDirty.set(true)
    this.saved.set(null)
    this.error.set(null)
  }

  load(type: string, version: string): void {
    if (!type || !version) {
      this.error.set('Missing route parameters (type or version).')
      return
    }
    this.loading.set(true)
    this.saved.set(null)
    this.error.set(null)
    this.api
      .getWorkflowDefinition(type, version)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (definition) => {
          this.baseline = definition
          this.definition.set(definition)
          this.creating.set(false)
          this.editing.set(false)
          this.drafts.set({})
          this.structureDirty.set(false)
          this.selectedId.set(definition.steps?.[0]?.id ?? null)
          this.targetVersion.set('')
        },
        error: (error: FactoryApiError) => this.error.set(error.message || 'Unable to load workflow definition.'),
      })
  }

  select(id: string): void {
    if (!this.saving()) this.selectedId.set(id)
  }

  adopt(): void {
    const step = this.selected()
    const draft = step && legacyAdoption(step)
    if (this.editing() && step && draft && !this.saving()) this.setDraft(step.id, draft)
  }

  update(field: keyof StepDraft, value: string | string[]): void {
    const step = this.selected()
    const draft = this.draft()
    if (!this.editing() || !step || !draft || this.saving()) return
    this.setDraft(step.id, { ...draft, [field]: value })
  }

  private setDraft(id: string, draft: StepDraft): void {
    this.drafts.update((drafts) => ({ ...drafts, [id]: draft }))
    this.saved.set(null)
    this.error.set(null)
  }

  edit(): void {
    if (this.definition() && !this.saving()) {
      this.editing.set(true)
      this.saved.set(null)
    }
  }

  cancel(): void {
    if (this.saving()) return
    this.discard()
    this.editing.set(false)
  }

  discard(): void {
    if (!this.saving()) {
      this.drafts.set({})
      this.definition.set(this.baseline)
      this.structureDirty.set(false)
      this.targetVersion.set(this.creating() ? '1.0.0' : '')
      this.selectedId.set(this.baseline?.steps?.[0]?.id ?? null)
      this.error.set(null)
      this.saved.set(null)
    }
  }

  save(): void {
    const source = this.definition()
    if (!this.editing() || !source || !this.dirty() || this.saving()) return
    const validation = this.validation()
    if (validation) {
      this.error.set(validation)
      return
    }
    const workflowType = source.workflowType?.trim()
    const version = this.targetVersion().trim()
    if (!workflowType || !SEMVER.test(version) || version === source.version) {
      this.error.set('Choose a new version in MAJOR.MINOR.PATCH format. Existing definitions and active runs are never modified.')
      return
    }
    const drafts = this.drafts()
    const definition: FullWorkflowDefinition = {
      ...source,
      version,
      steps: source.steps?.map((step) => (drafts[step.id] ? { ...step, ...drafts[step.id] } : step)),
    }
    this.saving.set(true)
    this.error.set(null)
    this.api
      .createWorkflowDefinition(definition)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (record) => {
          this.saved.set(
            `Saved ${record.workflowType}@${record.version} · hash ${record.definitionHash ?? 'unavailable'}. Original definition and runs unchanged.`
          )
          // Resolve the saved identity from the registration response, not the creation route.
          const savedDefinition = {
            ...definition,
            workflowType: record.workflowType ?? definition.workflowType,
            version: record.version ?? definition.version,
          }
          this.baseline = savedDefinition
          this.definition.set(savedDefinition)
          this.editing.set(false)
          this.drafts.set({})
          this.structureDirty.set(false)
          this.creating.set(false)
          this.targetVersion.set('')
        },
        error: (error: FactoryApiError) => {
          const path = error.details?.['path']
          this.error.set(`${error.message || 'Save failed.'}${path ? ` (${path})` : ''}`)
        },
      })
  }
}
