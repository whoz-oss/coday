import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  IntegrationConfig,
  IntegrationTypeControllerService,
  IntegrationTypeDescriptor,
  UserIntegrationConfig,
} from '@whoz-oss/agentos-api-client'
import { JsonSchemaFormComponent, JsonSchemaObject } from '@whoz-oss/design-system'
import { IntegrationConfigStateService, IntegrationScope } from '../../services/integration-config-state.service'

const VALID_SCOPES: ReadonlySet<IntegrationScope> = new Set(['namespace', 'userOnNs', 'userGlobal'])

const SCOPE_LABEL: Readonly<Record<IntegrationScope, string>> = Object.freeze({
  namespace: 'Configuration du namespace',
  userOnNs: 'Pour moi sur ce namespace',
  userGlobal: 'Pour moi globalement',
})

/**
 * IntegrationFormComponent — full-page create / edit form for an integration config.
 *
 * Mode is determined by the presence of `:integrationId` in the route params:
 * - `/:namespaceId/integrations/new`                       → create mode
 * - `/:namespaceId/integrations/:integrationId/edit`       → edit mode
 *
 * The active scope is driven by the `?scope=` query param (story 6.5):
 *   - `namespace`  (default) → submits to `IntegrationConfigController`
 *   - `userOnNs`             → submits to `UserIntegrationConfigController` for the current NS
 *   - `userGlobal`           → submits to `UserIntegrationConfigController` cross-namespace
 *
 * In create mode, an optional `?template=<configId>` query param hydrates the form from the
 * referenced NS-shared config — used by the "Override for me" cross-link from the list page.
 *
 * On success or cancel, navigates back to /:namespaceId/integrations.
 */
@Component({
  selector: 'agentos-integration-form',
  standalone: true,
  imports: [ReactiveFormsModule, JsonSchemaFormComponent],
  templateUrl: './integration-form.component.html',
  styleUrl: './integration-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(IntegrationConfigStateService)
  private readonly integrationTypeController = inject(IntegrationTypeControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string | null>(null),
    type: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    scope: new FormControl<IntegrationScope>('namespace', { nonNullable: true }),
  })

  protected get nameControl() {
    return this.form.controls.name
  }
  protected get descriptionControl() {
    return this.form.controls.description
  }
  protected get typeControl() {
    return this.form.controls.type
  }
  protected get scopeControl() {
    return this.form.controls.scope
  }

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  protected readonly scopeOptions: ReadonlyArray<{ value: IntegrationScope; label: string }> = [
    { value: 'namespace', label: SCOPE_LABEL.namespace },
    { value: 'userOnNs', label: SCOPE_LABEL.userOnNs },
    { value: 'userGlobal', label: SCOPE_LABEL.userGlobal },
  ]

  /** Parsed parameters value from ds-json-schema-form */
  protected readonly paramsValue = signal<Record<string, unknown> | null>(null)

  /** Initial params to seed ds-json-schema-form on load */
  protected readonly initialParams = signal<Record<string, unknown> | null>(null)

  /** All known integration type descriptors */
  protected readonly integrationTypes = toSignal(this.integrationTypeController.listTypesIntegrationType(), {
    initialValue: [] as IntegrationTypeDescriptor[],
  })

  /** Currently selected type key as a signal — driven by typeControl value changes */
  private readonly selectedType = toSignal(this.form.controls.type.valueChanges, {
    initialValue: this.form.controls.type.value,
  })

  /** JSON Schema for the currently selected integration type, or null */
  protected readonly schema = computed<JsonSchemaObject | null>(() => {
    const typeKey = this.selectedType()
    if (!typeKey) return null
    const descriptor = this.integrationTypes().find((d) => d.type === typeKey)
    return (descriptor?.configSchema as JsonSchemaObject) ?? null
  })

  /** Kept for the update payload (preserves server-side fields like userId). */
  private existingConfig: IntegrationConfig | UserIntegrationConfig | null = null

  ngOnInit(): void {
    this.state.setNamespace(this.namespaceId)
    const params = this.route.snapshot.paramMap
    const queryParams = this.route.snapshot.queryParamMap

    const integrationId = params.get('integrationId')
    const hintedScope = this.parseScope(queryParams.get('scope'))

    if (integrationId) {
      this.isEditMode.set(true)
      // Edit-mode: scope is immutable — disable the radio so the user cannot change it.
      this.scopeControl.disable()
      this.loadConfig(integrationId, hintedScope)
      return
    }

    this.scopeControl.setValue(hintedScope)
    const templateId = queryParams.get('template')
    if (templateId) {
      const templateScope = this.parseScope(queryParams.get('templateScope'))
      this.hydrateFromTemplate(templateId, templateScope)
    }
  }

  private parseScope(raw: string | null): IntegrationScope {
    return raw && VALID_SCOPES.has(raw as IntegrationScope) ? (raw as IntegrationScope) : 'namespace'
  }

  /**
   * In edit-mode, the scope is **derived from the loaded resource**, not from the URL —
   * a forged `?scope=` would otherwise route the update to the wrong controller. The
   * query param is only used as an initial hint for the GET; the source of truth is
   * the entity returned by the server.
   */
  private deriveScopeFromConfig(config: IntegrationConfig | UserIntegrationConfig): IntegrationScope {
    const isUserScope = 'userId' in config && !!(config as UserIntegrationConfig).userId
    if (!isUserScope) return 'namespace'
    return config.namespaceId ? 'userOnNs' : 'userGlobal'
  }

  private loadConfig(id: string, hintedScope: IntegrationScope): void {
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

  private hydrateFromTemplate(templateId: string, templateScope: IntegrationScope): void {
    this.isLoading.set(true)
    this.state
      .getById(templateId, templateScope)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (config) => {
          this.applyConfigToForm(config)
          this.isLoading.set(false)
          // Strip the template param from the URL so a refresh doesn't re-hydrate over user edits.
          this.router.navigate([], {
            relativeTo: this.route,
            queryParams: { template: null, templateScope: null },
            queryParamsHandling: 'merge',
            replaceUrl: true,
          })
        },
        error: (err) => {
          // Template inaccessible (deleted, 403, network) — drop the loading flag, drop the
          // template param, and stay on a blank create form so the user can still proceed.
          console.warn(`[IntegrationForm] Could not hydrate from template ${templateId}:`, err)
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

  private applyConfigToForm(config: IntegrationConfig | UserIntegrationConfig): void {
    this.nameControl.setValue(config.name)
    this.descriptionControl.setValue(config.description ?? null)
    this.typeControl.setValue(config.integrationType)
    this.initialParams.set(config.parameters as Record<string, unknown> | null)
    this.paramsValue.set(config.parameters as Record<string, unknown> | null)
  }

  protected onParamsChange(value: Record<string, unknown> | null): void {
    this.paramsValue.set(value)
  }

  protected submit(): void {
    if (this.nameControl.invalid || this.typeControl.invalid || this.isSubmitting()) return

    // Edit-mode without a loaded existingConfig.id is a corrupt state (load failed, or
    // the id was stripped). Bailing out is safer than firing a PUT on an empty path.
    if (this.isEditMode() && !this.existingConfig?.id) {
      this.navigateBack()
      return
    }

    this.isSubmitting.set(true)
    const trimmedDescription = this.descriptionControl.value?.trim()
    const draft = {
      name: this.nameControl.value.trim(),
      // Send null (not undefined) when the user cleared the field so the backend clears it.
      description: trimmedDescription ? trimmedDescription : null,
      integrationType: this.typeControl.value,
      parameters: this.paramsValue(),
    }
    // getRawValue() includes disabled controls (scope is disabled in edit mode) — more robust
    // than form.value which would skip it. control.value direct also works on disabled controls
    // but is fragile to refactors that pass the parent form value around.
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
    this.router.navigate(['/agentos', this.namespaceId, 'integrations'])
  }

  protected trackByType(_index: number, descriptor: IntegrationTypeDescriptor): string {
    return descriptor.type
  }

  protected trackByScope(_index: number, opt: { value: IntegrationScope }): string {
    return opt.value
  }
}
