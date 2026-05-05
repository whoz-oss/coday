import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { startWith, switchMap } from 'rxjs'
import { AiModel, UserAiModel } from '@whoz-oss/agentos-api-client'
import { AiModelConfigStateService, AiModelScope, EligibleProvider } from '../../services/ai-model-config-state.service'

const VALID_SCOPES: ReadonlySet<AiModelScope> = new Set(['namespace', 'userOnNs', 'userGlobal'])

const SCOPE_LABEL: Readonly<Record<AiModelScope, string>> = Object.freeze({
  namespace: 'Configuration du namespace',
  userOnNs: 'Pour moi sur ce namespace',
  userGlobal: 'Pour moi globalement',
})

const SCOPE_BADGE: Readonly<Record<AiModelScope, string>> = Object.freeze({
  namespace: 'NS',
  userOnNs: 'USER × NS',
  userGlobal: 'USER GLOBAL',
})

/**
 * AiModelFormComponent — full-page create / edit form for an AI model (story 6.6).
 *
 * Mode is determined by the route param `:modelId`:
 * - `/:namespaceId/ai-models/new`               → create mode
 * - `/:namespaceId/ai-models/:modelId/edit`     → edit mode
 *
 * The active scope is driven by the `?scope=` query param in create mode (radio selector
 * exposed). In edit mode the scope is **derived from the loaded resource** — the query
 * param is ignored to prevent forged URLs from routing the update to the wrong controller.
 *
 * Provider dropdown filters by FR3 parent-mode constraint via
 * `AiModelConfigStateService.eligibleProviders$(scope)`. When the user changes the scope
 * radio in create mode, the eligible list updates and a stale selection is cleared so the
 * user is forced to pick a compatible parent.
 *
 * The provider field is also locked in edit mode (consistent with the previous UX — the
 * parent reference is immutable post-creation per backend invariants).
 */
@Component({
  selector: 'agentos-ai-model-form',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './ai-model-form.component.html',
  styleUrl: './ai-model-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiModelFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AiModelConfigStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly scopeOptions: ReadonlyArray<{ value: AiModelScope; label: string }> = [
    { value: 'namespace', label: SCOPE_LABEL.namespace },
    { value: 'userOnNs', label: SCOPE_LABEL.userOnNs },
    { value: 'userGlobal', label: SCOPE_LABEL.userGlobal },
  ]

  protected readonly form = new FormGroup({
    aiProviderId: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    apiModelName: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string | null>(null),
    alias: new FormControl<string>('', { nonNullable: true }),
    priority: new FormControl<number>(0, { nonNullable: true }),
    temperature: new FormControl<number | null>(null),
    maxTokens: new FormControl<number | null>(null),
    scope: new FormControl<AiModelScope>('namespace', { nonNullable: true }),
  })

  protected get aiProviderIdControl() {
    return this.form.controls.aiProviderId
  }
  protected get apiModelNameControl() {
    return this.form.controls.apiModelName
  }
  protected get descriptionControl() {
    return this.form.controls.description
  }
  protected get aliasControl() {
    return this.form.controls.alias
  }
  protected get priorityControl() {
    return this.form.controls.priority
  }
  protected get temperatureControl() {
    return this.form.controls.temperature
  }
  protected get maxTokensControl() {
    return this.form.controls.maxTokens
  }
  protected get scopeControl() {
    return this.form.controls.scope
  }

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /**
   * Eligible providers for the current scope, computed reactively. The signal is wired
   * from the state service which combines the 3 provider sources and filters via FR3.
   */
  protected readonly eligibleProviders = signal<EligibleProvider[]>([])

  protected readonly selectedProviderLabel = computed(() => {
    const id = this.aiProviderIdControl.value
    const provider = this.eligibleProviders().find((p) => p.id === id)
    return provider ? `${provider.name} (${SCOPE_BADGE[provider.scope]})` : id
  })

  /** Kept for the update payload (preserves server-side userId/namespaceId). */
  private existingModel: AiModel | UserAiModel | null = null

  ngOnInit(): void {
    this.state.setNamespace(this.namespaceId)
    const params = this.route.snapshot.paramMap
    const queryParams = this.route.snapshot.queryParamMap

    const modelId = params.get('modelId')
    const hintedScope = this.parseScope(queryParams.get('scope'))

    // Single subscription that drives the eligible providers list and clears a stale
    // selection. switchMap auto-cancels the previous inner observable on every scope
    // change, so no race between concurrent emissions and no subscription leak when
    // the user toggles the radio rapidly. startWith seeds the initial value before
    // the first setValue triggers valueChanges.
    this.scopeControl.valueChanges
      .pipe(
        startWith(this.scopeControl.value),
        switchMap((scope) => this.state.eligibleProviders$(scope)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((list) => {
        this.eligibleProviders.set(list)
        // If the previously selected provider is no longer eligible, clear the field so
        // the user is forced to pick a compatible parent.
        const currentId = this.aiProviderIdControl.value
        if (currentId && !list.some((p) => p.id === currentId)) {
          this.aiProviderIdControl.setValue('')
        }
      })

    if (modelId) {
      this.isEditMode.set(true)
      this.scopeControl.disable()
      this.aiProviderIdControl.disable()
      this.loadModel(modelId, hintedScope)
      return
    }

    // setValue triggers valueChanges, which propagates through the switchMap above.
    this.scopeControl.setValue(hintedScope)

    const templateId = queryParams.get('template')
    if (templateId) {
      const templateScope = this.parseScope(queryParams.get('templateScope'))
      this.hydrateFromTemplate(templateId, templateScope)
    }
  }

  private parseScope(raw: string | null): AiModelScope {
    return raw && VALID_SCOPES.has(raw as AiModelScope) ? (raw as AiModelScope) : 'namespace'
  }

  private deriveScopeFromConfig(config: AiModel | UserAiModel): AiModelScope {
    const isUserScope = !!(config as UserAiModel).userId
    if (!isUserScope) return 'namespace'
    return config.namespaceId ? 'userOnNs' : 'userGlobal'
  }

  private loadModel(id: string, hintedScope: AiModelScope): void {
    this.isLoading.set(true)
    this.state
      .getById(id, hintedScope)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (model) => {
          this.existingModel = model
          const scope = this.deriveScopeFromConfig(model)
          // setValue propagates through the switchMap pipe set up in ngOnInit, which
          // refreshes eligibleProviders and clears any stale aiProviderId selection.
          this.scopeControl.setValue(scope)
          this.applyModelToForm(model)
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  private hydrateFromTemplate(templateId: string, templateScope: AiModelScope): void {
    this.isLoading.set(true)
    this.state
      .getById(templateId, templateScope)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (model) => {
          // Hydrate fields that make sense to copy across overrides; leave aiProviderId
          // untouched because the parent provider must satisfy the FR3 mode constraint
          // for the chosen scope, which the user picks via the radio.
          this.apiModelNameControl.setValue(model.apiModelName)
          this.descriptionControl.setValue(model.description ?? null)
          this.aliasControl.setValue(model.alias ?? '')
          this.priorityControl.setValue(model.priority ?? 0)
          this.temperatureControl.setValue(model.temperature ?? null)
          this.maxTokensControl.setValue(model.maxTokens ?? null)
          this.isLoading.set(false)
          this.router.navigate([], {
            relativeTo: this.route,
            queryParams: { template: null, templateScope: null },
            queryParamsHandling: 'merge',
            replaceUrl: true,
          })
        },
        error: (err) => {
          console.warn(`[AiModelForm] Could not hydrate from template ${templateId}:`, err)
          this.isLoading.set(false)
          this.router.navigate([], {
            relativeTo: this.route,
            queryParams: { template: null, templateScope: null },
            queryParamsHandling: 'merge',
            replaceUrl: true,
          })
        },
      })
  }

  private applyModelToForm(model: AiModel | UserAiModel): void {
    this.aiProviderIdControl.setValue(model.aiProviderId)
    this.apiModelNameControl.setValue(model.apiModelName)
    this.descriptionControl.setValue(model.description ?? null)
    this.aliasControl.setValue(model.alias ?? '')
    this.priorityControl.setValue(model.priority ?? 0)
    this.temperatureControl.setValue(model.temperature ?? null)
    this.maxTokensControl.setValue(model.maxTokens ?? null)
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    if (this.isEditMode() && !this.existingModel?.id) {
      this.navigateBack()
      return
    }

    this.isSubmitting.set(true)
    const raw = this.form.getRawValue()
    const trimmedDescription = raw.description?.trim()
    const alias = raw.alias.trim()

    const draft = {
      apiModelName: raw.apiModelName.trim(),
      description: trimmedDescription ? trimmedDescription : null,
      alias: alias ? alias : null,
      priority: raw.priority,
      temperature: raw.temperature ?? null,
      maxTokens: raw.maxTokens ?? null,
      aiProviderId: raw.aiProviderId,
    }
    // raw is form.getRawValue() — already includes the disabled scope control in edit mode.
    const scope = raw.scope

    const call$ =
      this.isEditMode() && this.existingModel?.id
        ? this.state.update(this.existingModel.id, draft, scope, this.existingModel)
        : this.state.create(draft, scope, this.namespaceId)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'ai-models'])
  }

  protected trackByProvider(_index: number, provider: EligibleProvider): string {
    return provider.id
  }

  protected trackByScope(_index: number, opt: { value: AiModelScope }): string {
    return opt.value
  }

  protected scopeBadgeFor(scope: 'namespace' | 'userOnNs' | 'userGlobal'): string {
    return SCOPE_BADGE[scope]
  }
}
