import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { AiProvider, AiProviderApiTypeEnum, UserAiProvider } from '@whoz-oss/agentos-api-client'
import { AiProviderConfigStateService, AiProviderScope } from '../../services/ai-provider-config-state.service'

const VALID_SCOPES: ReadonlySet<AiProviderScope> = new Set(['namespace', 'userOnNs', 'userGlobal'])

const SCOPE_LABEL: Readonly<Record<AiProviderScope, string>> = Object.freeze({
  namespace: 'Configuration du namespace',
  userOnNs: 'Pour moi sur ce namespace',
  userGlobal: 'Pour moi globalement',
})

/**
 * AiProviderFormComponent — full-page create / edit form for an AI provider (story 6.6).
 *
 * Mode is determined by the route param `:aiProviderId`:
 * - `/:namespaceId/ai-providers/new`                  → create mode
 * - `/:namespaceId/ai-providers/:aiProviderId/edit`   → edit mode
 *
 * The active scope is driven by the `?scope=` query param in create mode (radio selector
 * exposed). In edit mode the scope is **derived from the loaded resource** (presence of
 * `userId`/`namespaceId`) — the query param is ignored to prevent forged URLs from routing
 * the update to the wrong controller (lesson learned from story 6.5).
 *
 * apiKey masking (NFR-SEC-1, FR25): on edit, the loaded apiKey is the masked sentinel
 * returned by the backend. If the user does not change it, the state service omits the
 * field from the update payload so the backend keeps the persisted credential. Pattern
 * inherited from PR #811.
 *
 * Cross-link `?template=<id>` (create only) hydrates name/apiType/baseUrl/description from
 * the referenced provider; the `apiKey` is intentionally NOT hydrated — the user must type
 * a new key for an override (we don't carry a credential across resources).
 */
@Component({
  selector: 'agentos-ai-provider-form',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './ai-provider-form.component.html',
  styleUrl: './ai-provider-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiProviderFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AiProviderConfigStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly apiTypeOptions = Object.values(AiProviderApiTypeEnum)

  protected readonly scopeOptions: ReadonlyArray<{ value: AiProviderScope; label: string }> = [
    { value: 'namespace', label: SCOPE_LABEL.namespace },
    { value: 'userOnNs', label: SCOPE_LABEL.userOnNs },
    { value: 'userGlobal', label: SCOPE_LABEL.userGlobal },
  ]

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string | null>(null),
    apiType: new FormControl<AiProviderApiTypeEnum>(AiProviderApiTypeEnum.OpenAI, {
      nonNullable: true,
      validators: [Validators.required],
    }),
    baseUrl: new FormControl<string>('', { nonNullable: true }),
    apiKey: new FormControl<string>('', { nonNullable: true }),
    scope: new FormControl<AiProviderScope>('namespace', { nonNullable: true }),
  })

  protected get nameControl() {
    return this.form.controls.name
  }
  protected get descriptionControl() {
    return this.form.controls.description
  }
  protected get apiTypeControl() {
    return this.form.controls.apiType
  }
  protected get baseUrlControl() {
    return this.form.controls.baseUrl
  }
  protected get apiKeyControl() {
    return this.form.controls.apiKey
  }
  protected get scopeControl() {
    return this.form.controls.scope
  }

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /** Kept for the update payload (preserves server-side userId/namespaceId). */
  private existingConfig: AiProvider | UserAiProvider | null = null

  /**
   * Snapshot of the apiKey value loaded from the server (typically a masked sentinel).
   * On submit we compare the current control value to this snapshot — if unchanged, we
   * tell the state service to omit the apiKey field from the update payload (FR25).
   */
  private initialApiKey = ''

  ngOnInit(): void {
    this.state.setNamespace(this.namespaceId)
    const params = this.route.snapshot.paramMap
    const queryParams = this.route.snapshot.queryParamMap

    const aiProviderId = params.get('aiProviderId')
    const hintedScope = this.parseScope(queryParams.get('scope'))

    if (aiProviderId) {
      this.isEditMode.set(true)
      // Edit-mode: scope is immutable — disable the radio so the user cannot change it.
      this.scopeControl.disable()
      this.loadConfig(aiProviderId, hintedScope)
      return
    }

    this.scopeControl.setValue(hintedScope)
    const templateId = queryParams.get('template')
    if (templateId) {
      const templateScope = this.parseScope(queryParams.get('templateScope'))
      this.hydrateFromTemplate(templateId, templateScope)
    }
  }

  private parseScope(raw: string | null): AiProviderScope {
    return raw && VALID_SCOPES.has(raw as AiProviderScope) ? (raw as AiProviderScope) : 'namespace'
  }

  /**
   * In edit-mode, the scope is **derived from the loaded resource**, not from the URL —
   * a forged `?scope=` would otherwise route the update to the wrong controller.
   */
  private deriveScopeFromConfig(config: AiProvider | UserAiProvider): AiProviderScope {
    const isUserScope = !!(config as UserAiProvider).userId
    if (!isUserScope) return 'namespace'
    return config.namespaceId ? 'userOnNs' : 'userGlobal'
  }

  private loadConfig(id: string, hintedScope: AiProviderScope): void {
    this.isLoading.set(true)
    this.state
      .getById(id, hintedScope)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (config) => {
          this.existingConfig = config
          this.scopeControl.setValue(this.deriveScopeFromConfig(config))
          this.applyConfigToForm(config)
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  private hydrateFromTemplate(templateId: string, templateScope: AiProviderScope): void {
    this.isLoading.set(true)
    this.state
      .getById(templateId, templateScope)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (config) => {
          // Hydrate everything except the apiKey — credentials don't carry across overrides.
          this.nameControl.setValue(config.name)
          this.descriptionControl.setValue(config.description ?? null)
          this.apiTypeControl.setValue(config.apiType as AiProviderApiTypeEnum)
          this.baseUrlControl.setValue(config.baseUrl ?? '')
          this.apiKeyControl.setValue('')
          this.initialApiKey = ''
          this.apiKeyControl.markAsTouched()
          this.isLoading.set(false)
          // Strip the template param so a refresh doesn't re-hydrate over user edits.
          this.router.navigate([], {
            relativeTo: this.route,
            queryParams: { template: null, templateScope: null },
            queryParamsHandling: 'merge',
            replaceUrl: true,
          })
        },
        error: (err) => {
          console.warn(`[AiProviderForm] Could not hydrate from template ${templateId}:`, err)
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

  private applyConfigToForm(config: AiProvider | UserAiProvider): void {
    this.nameControl.setValue(config.name)
    this.descriptionControl.setValue(config.description ?? null)
    this.apiTypeControl.setValue(config.apiType as AiProviderApiTypeEnum)
    this.baseUrlControl.setValue(config.baseUrl ?? '')
    const loadedApiKey = config.apiKey ?? ''
    this.apiKeyControl.setValue(loadedApiKey)
    this.initialApiKey = loadedApiKey
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    if (this.isEditMode() && !this.existingConfig?.id) {
      this.navigateBack()
      return
    }

    this.isSubmitting.set(true)
    const trimmedDescription = this.descriptionControl.value?.trim()
    const baseUrl = this.baseUrlControl.value.trim()
    const currentApiKey = this.apiKeyControl.value
    // apiKey masking: in edit mode, if the user did not modify the field, send null so the
    // state service omits the key from the update payload. In create mode, an empty string
    // means "no key" (sent as null too — backend accepts no apiKey on create).
    const apiKey = this.isEditMode() && currentApiKey === this.initialApiKey ? null : currentApiKey

    const draft = {
      name: this.nameControl.value.trim(),
      apiType: this.apiTypeControl.value,
      description: trimmedDescription ? trimmedDescription : null,
      baseUrl: baseUrl ? baseUrl : null,
      apiKey,
    }
    const scope = this.form.getRawValue().scope

    const call$ =
      this.isEditMode() && this.existingConfig?.id
        ? this.state.update(this.existingConfig.id, draft, scope, this.existingConfig)
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
    this.router.navigate(['/agentos', this.namespaceId, 'ai-providers'])
  }

  protected trackByScope(_index: number, opt: { value: AiProviderScope }): string {
    return opt.value
  }
}
