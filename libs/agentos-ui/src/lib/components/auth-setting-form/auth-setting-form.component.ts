import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, effect, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { AuthSetting, AuthSettingAuthTypeEnum } from '@whoz-oss/agentos-api-client'
import { AuthSettingConfigStateService, AuthSettingScope } from '../../services/auth-setting-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'

const VALID_SCOPES: ReadonlySet<AuthSettingScope> = new Set(['namespace', 'userOnNs', 'userGlobal'])

const SCOPE_LABEL: Readonly<Record<AuthSettingScope, string>> = Object.freeze({
  namespace: 'Configuration du namespace',
  userOnNs: 'Pour moi sur ce namespace',
  userGlobal: 'Pour moi globalement',
})

/**
 * AuthSettingFormComponent — full-page create / edit form for an auth setting
 * (Issue #1095, Phase 7).
 *
 * Mode is determined by the route param `:authSettingId`:
 * - `/:namespaceId/auth-settings/new`                    → create mode
 * - `/:namespaceId/auth-settings/:authSettingId/edit`    → edit mode
 *
 * The active scope is driven by the `?scope=` query param in create mode (radio selector
 * exposed). In edit mode the scope is **derived from the loaded resource** (presence of
 * `userId`/`namespaceId`) — the query param is ignored to prevent forged URLs from routing
 * the update to the wrong controller (lesson learned from story 6.5).
 *
 * Data masking (NFR-SEC-1): on edit, the loaded data values are the masked sentinels
 * returned by the backend. The form tracks `initialData` (a snapshot of the loaded values).
 * On submit, each data entry is compared to the snapshot:
 *   - unchanged value → key is omitted from the update payload (backend keeps persisted cred)
 *   - changed value   → included as-is
 *   - empty string    → included (clears the key on the backend)
 *
 * Cross-link `?template=<id>` (create only) hydrates name/authType/description from the
 * referenced setting; the `data` map is intentionally NOT hydrated — credentials don't
 * carry across resources (NFR-SEC-1).
 *
 * authType is immutable in edit mode (same as apiType in AiProviderFormComponent).
 */
@Component({
  selector: 'agentos-auth-setting-form',
  imports: [ReactiveFormsModule],
  templateUrl: './auth-setting-form.component.html',
  styleUrl: './auth-setting-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthSettingFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AuthSettingConfigStateService)
  private readonly namespaceRole = inject(NamespaceRoleStateService)

  /**
   * Namespace scoping this form. `undefined` when loaded from an admin route
   * (`admin/auth-settings/new`, `admin/auth-settings/:id/edit`) — platform context.
   * A concrete UUID when loaded from `/:namespaceId/auth-settings/*`.
   */
  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined

  /**
   * Whether this form is operating in platform-admin context.
   * True when there is no `:namespaceId` param in the route (admin/* routes).
   * In platform mode:
   * - the scope selector is hidden (scope is implicitly 'namespace' = platform-level)
   * - create payload has namespaceId: null (platform scope, namespaceId IS NULL)
   * - navigateBack() returns to /agentos/admin/auth-settings
   */
  protected readonly isPlatformMode = this.namespaceId === undefined

  /**
   * Whether the current user can write at namespace scope (super-admin OR namespace ADMIN
   * by Neo4j relation). Drives the namespace radio option's disabled state — non-admins
   * cannot pick `scope='namespace'` because the backend would 403. Defaults to `false`
   * until the lookup resolves; safe — admins see options enable as soon as the role lands.
   * In platform mode, always true (super-admin access is enforced by the backend).
   */
  protected readonly isAdmin = this.isPlatformMode
    ? signal(true)
    : toSignal(this.namespaceRole.isAdminOfNamespace$(this.namespaceId!), { initialValue: false })

  protected readonly authTypeOptions = Object.values(AuthSettingAuthTypeEnum)

  protected readonly scopeOptions: ReadonlyArray<{ value: AuthSettingScope; label: string }> = [
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
    authType: new FormControl<AuthSettingAuthTypeEnum>(AuthSettingAuthTypeEnum.API_KEY, {
      nonNullable: true,
      validators: [Validators.required],
    }),
    scope: new FormControl<AuthSettingScope>('namespace', { nonNullable: true }),
    data: new FormArray<FormGroup<{ key: FormControl<string>; value: FormControl<string> }>>([], { validators: [] }),
  })

  protected get nameControl() {
    return this.form.controls.name
  }
  protected get descriptionControl() {
    return this.form.controls.description
  }
  protected get authTypeControl() {
    return this.form.controls.authType
  }
  protected get scopeControl() {
    return this.form.controls.scope
  }
  protected get dataArray() {
    return this.form.controls.data
  }

  protected addDataEntry(): void {
    this.dataArray.push(
      new FormGroup({
        key: new FormControl<string>('', { nonNullable: true, validators: [Validators.required] }),
        value: new FormControl<string>('', { nonNullable: true }),
      })
    )
  }

  protected removeDataEntry(index: number): void {
    this.dataArray.removeAt(index)
  }

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /** Kept for the update payload (preserves server-side userId/namespaceId). */
  private existingConfig: AuthSetting | null = null

  /**
   * Snapshot of the data values loaded from the server (typically masked sentinels).
   * On submit we compare each entry's current value to this snapshot — if unchanged,
   * we omit that key from the update payload so the backend keeps the persisted credential
   * (NFR-SEC-1, FR25 equivalent for auth settings).
   */
  private initialData: Record<string, string> = {}

  constructor() {
    // URL-forging defence: in create-mode, if a non-admin lands on `?scope=namespace` (the
    // default, or via a hand-crafted URL), bounce the radio to `userOnNs` once the role
    // lookup resolves. We watch reactively because the role lookup is async.
    // In platform mode, scope is always 'namespace' (platform-level) and never bounced.
    if (!this.isPlatformMode) {
      effect(() => {
        const admin = this.isAdmin()
        if (admin || this.isEditMode()) return
        if (this.scopeControl.value === 'namespace') {
          this.scopeControl.setValue('userOnNs')
        }
      })
    }
  }

  ngOnInit(): void {
    if (this.namespaceId) this.state.setNamespace(this.namespaceId)
    const params = this.route.snapshot.paramMap
    const queryParams = this.route.snapshot.queryParamMap

    const authSettingId = params.get('authSettingId')
    const hintedScope = this.isPlatformMode ? 'namespace' : this.parseScope(queryParams.get('scope'))

    if (authSettingId) {
      this.isEditMode.set(true)
      // Edit-mode: scope and authType are immutable — disable them so the user cannot change.
      this.scopeControl.disable()
      this.authTypeControl.disable()
      this.loadConfig(authSettingId)
      return
    }

    this.scopeControl.setValue(hintedScope)
    const templateId = queryParams.get('template')
    if (templateId) {
      this.hydrateFromTemplate(templateId)
    }
  }

  private parseScope(raw: string | null): AuthSettingScope {
    return raw && VALID_SCOPES.has(raw as AuthSettingScope) ? (raw as AuthSettingScope) : 'namespace'
  }

  /**
   * In edit-mode, the scope is **derived from the loaded resource**, not from the URL —
   * a forged `?scope=` would otherwise route the update to the wrong controller.
   */
  private deriveScopeFromConfig(config: AuthSetting): AuthSettingScope {
    const isUserScope = !!config.userId
    if (!isUserScope) return 'namespace'
    return config.namespaceId ? 'userOnNs' : 'userGlobal'
  }

  private loadConfig(id: string): void {
    this.isLoading.set(true)
    this.state
      .getById(id)
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
          console.warn(`[AuthSettingForm] Could not load auth setting '${id}' — navigating back`)
          this.navigateBack()
        },
      })
  }

  private hydrateFromTemplate(templateId: string): void {
    this.isLoading.set(true)
    this.state
      .getById(templateId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (config) => {
          // Hydrate everything except the data map — credentials don't carry across overrides.
          this.nameControl.setValue(config.name)
          this.descriptionControl.setValue(config.description ?? null)
          this.authTypeControl.setValue(config.authType as AuthSettingAuthTypeEnum)
          this.dataArray.clear()
          this.initialData = {}
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
          console.warn(`[AuthSettingForm] Could not hydrate from template ${templateId}:`, err)
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

  private applyConfigToForm(config: AuthSetting): void {
    this.nameControl.setValue(config.name)
    this.descriptionControl.setValue(config.description ?? null)
    this.authTypeControl.setValue(config.authType as AuthSettingAuthTypeEnum)
    // Hydrate data entries from the loaded config
    this.dataArray.clear()
    this.initialData = {}
    for (const [key, value] of Object.entries(config.data ?? {})) {
      this.initialData[key] = value
      this.dataArray.push(
        new FormGroup({
          key: new FormControl<string>(key, { nonNullable: true, validators: [Validators.required] }),
          value: new FormControl<string>(value, { nonNullable: true }),
        })
      )
    }
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    if (this.isEditMode() && !this.existingConfig?.id) {
      this.navigateBack()
      return
    }

    this.isSubmitting.set(true)
    const trimmedDescription = this.descriptionControl.value?.trim()

    // Data masking: on edit, compare each value to the snapshot.
    // If a key's value is unchanged from the initial (masked) sentinel, omit that key
    // from the payload so the backend keeps the persisted credential.
    // If no entries are present and we are editing, send null (omit data entirely).
    let data: { [key: string]: string } | null = null

    const dataEntries = this.dataArray.controls.filter((g) => g.controls.key.value.trim())

    if (dataEntries.length > 0) {
      const resultMap: { [key: string]: string } = {}
      for (const group of dataEntries) {
        const key = group.controls.key.value.trim()
        const currentValue = group.controls.value.value
        if (this.isEditMode() && currentValue === this.initialData[key]) {
          // Value unchanged — omit this key from the payload; backend keeps persisted cred.
          continue
        }
        resultMap[key] = currentValue
      }
      // If all values were unchanged, resultMap is empty — treat as null (no-op on backend).
      data = Object.keys(resultMap).length > 0 ? resultMap : null
    } else if (!this.isEditMode()) {
      // Create mode with no entries: send empty object to indicate no data.
      data = {}
    }
    // Edit mode with no entries: data stays null (no-op, backend keeps existing data).

    const draft = {
      name: this.nameControl.value.trim(),
      authType: this.authTypeControl.value,
      description: trimmedDescription ? trimmedDescription : null,
      data,
    }
    const scope = this.form.getRawValue().scope

    // In platform mode, namespaceId is null (platform scope: namespaceId IS NULL).
    const namespaceIdForCreate = this.isPlatformMode ? null : (this.namespaceId ?? null)

    const call$ =
      this.isEditMode() && this.existingConfig?.id
        ? this.state.update(this.existingConfig.id, draft, scope, this.existingConfig)
        : this.state.create(draft, scope, namespaceIdForCreate)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    if (this.isPlatformMode) {
      this.router.navigate(['/agentos', 'admin', 'auth-settings'])
    } else {
      this.router.navigate(['/agentos', this.namespaceId!, 'auth-settings'])
    }
  }

  protected trackByScope(_index: number, opt: { value: AuthSettingScope }): string {
    return opt.value
  }
}
