import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, effect, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  ApiKeyAuthSetting,
  AuthSettingDto,
  BasicAuthAuthSetting,
  BearerTokenAuthSetting,
  OAuthCustomAuthSetting,
  OAuthDiscoverableAuthSetting,
  OAuthRegisteredAuthSetting,
} from '@whoz-oss/agentos-api-client'
import {
  AUTH_SETTING_TYPE_LABEL,
  AUTH_SETTING_TYPES,
  AuthSettingConfigStateService,
  AuthSettingScope,
  AuthSettingType,
} from '../../services/auth-setting-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'

const VALID_SCOPES: ReadonlySet<AuthSettingScope> = new Set(['namespace', 'userOnNs', 'userGlobal'])

const SCOPE_LABEL: Readonly<Record<AuthSettingScope, string>> = Object.freeze({
  platform: 'Plateforme (lecture seule)',
  namespace: 'Configuration du namespace',
  userOnNs: 'Pour moi sur ce namespace',
  userGlobal: 'Pour moi globalement',
})

/**
 * Credential fields per auth type.
 * `secret: true` → rendered as `type="password"` with masking-sentinel tracking.
 */
interface CredentialField {
  key: string
  label: string
  placeholder: string
  secret: boolean
  required: boolean
}

const CREDENTIAL_FIELDS: Readonly<Record<AuthSettingType, ReadonlyArray<CredentialField>>> = {
  ApiKeyAuthSetting: [{ key: 'apiKey', label: 'API Key', placeholder: 'sk-…', secret: true, required: false }],
  BasicAuthAuthSetting: [
    { key: 'username', label: 'Username', placeholder: 'user@example.com', secret: false, required: false },
    { key: 'password', label: 'Password', placeholder: '••••••••', secret: true, required: false },
  ],
  BearerTokenAuthSetting: [{ key: 'token', label: 'Bearer token', placeholder: 'eyJ…', secret: true, required: false }],
  OAuthDiscoverableAuthSetting: [
    {
      key: 'discoveryUrl',
      label: 'Discovery URL',
      placeholder: 'https://…/.well-known/openid-configuration',
      secret: false,
      required: false,
    },
    { key: 'clientId', label: 'Client ID', placeholder: '', secret: false, required: false },
    { key: 'clientSecret', label: 'Client secret', placeholder: '', secret: true, required: false },
    { key: 'scopes', label: 'Scopes', placeholder: 'openid profile email', secret: false, required: false },
  ],
  OAuthCustomAuthSetting: [
    {
      key: 'authorizationUrl',
      label: 'Authorization URL',
      placeholder: 'https://…/authorize',
      secret: false,
      required: false,
    },
    { key: 'tokenUrl', label: 'Token URL', placeholder: 'https://…/token', secret: false, required: false },
    { key: 'clientId', label: 'Client ID', placeholder: '', secret: false, required: false },
    { key: 'clientSecret', label: 'Client secret', placeholder: '', secret: true, required: false },
    { key: 'scopes', label: 'Scopes', placeholder: 'openid profile email', secret: false, required: false },
  ],
  OAuthRegisteredAuthSetting: [
    {
      key: 'authorizationUrl',
      label: 'Authorization URL',
      placeholder: 'https://…/authorize',
      secret: false,
      required: false,
    },
    { key: 'tokenUrl', label: 'Token URL', placeholder: 'https://…/token', secret: false, required: false },
    { key: 'clientId', label: 'Client ID', placeholder: '', secret: false, required: false },
    { key: 'clientSecret', label: 'Client secret', placeholder: '', secret: true, required: false },
    { key: 'scopes', label: 'Scopes', placeholder: 'openid profile email', secret: false, required: false },
  ],
}

/**
 * AuthSettingFormComponent — full-page create / edit form for an auth setting
 * (Issue #1095).
 *
 * Mode is determined by the route param `:authSettingId`:
 * - `/:namespaceId/auth-settings/new`                    → create mode
 * - `/:namespaceId/auth-settings/:authSettingId/edit`    → edit mode
 *
 * The active scope is driven by the `?scope=` query param in create mode (radio selector
 * exposed). In edit mode the scope is **derived from the loaded resource** (presence of
 * `userId`/`namespaceId`) — the query param is ignored to prevent forged URLs from routing
 * the update to the wrong controller.
 *
 * Type-specific credential fields: selecting a different `authType` swaps the credential
 * fieldset. Each concrete auth type exposes its own named fields (e.g. `apiKey` for
 * ApiKeyAuthSetting, `username`+`password` for BasicAuth). The generic key-value `data`
 * map is gone — the backend now uses typed subtypes.
 *
 * Data masking (NFR-SEC-1): on edit, the loaded credential values are the masked sentinels
 * returned by the backend. The form tracks `initialCredentials` (a snapshot of the loaded
 * values). On submit, each credential field is compared to its snapshot:
 *   - unchanged value → field is set to `undefined` in the payload (backend keeps persisted cred)
 *   - changed value   → included as-is
 *   - empty string    → included (clears the credential on the backend)
 *
 * Cross-link `?template=<id>` (create only) hydrates name/authType/description from the
 * referenced setting; credential fields are intentionally NOT hydrated (NFR-SEC-1).
 *
 * authType is immutable in edit mode.
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

  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined
  protected readonly isPlatformMode = this.namespaceId === undefined

  protected readonly isAdmin = this.isPlatformMode
    ? signal(true)
    : toSignal(this.namespaceRole.isAdminOfNamespace$(this.namespaceId!), { initialValue: false })

  protected readonly authTypeOptions = AUTH_SETTING_TYPES
  protected readonly authTypeLabel = AUTH_SETTING_TYPE_LABEL

  protected readonly scopeOptions: ReadonlyArray<{ value: AuthSettingScope; label: string }> = [
    { value: 'namespace', label: SCOPE_LABEL.namespace },
    { value: 'userOnNs', label: SCOPE_LABEL.userOnNs },
    { value: 'userGlobal', label: SCOPE_LABEL.userGlobal },
  ]

  // ── Shared fields (common to all auth types) ─────────────────────────────
  protected readonly nameControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(1)],
  })
  protected readonly descriptionControl = new FormControl<string | null>(null)
  protected readonly authTypeControl = new FormControl<AuthSettingType>('ApiKeyAuthSetting', {
    nonNullable: true,
    validators: [Validators.required],
  })
  protected readonly scopeControl = new FormControl<AuthSettingScope>('namespace', { nonNullable: true })

  protected readonly form = new FormGroup({
    name: this.nameControl,
    description: this.descriptionControl,
    authType: this.authTypeControl,
    scope: this.scopeControl,
  })

  // ── Credential controls — one per concrete field across all types ─────────
  // All optional; only the fields relevant to the selected authType are included
  // in the submit payload. Controls are always present in the DOM (simpler than
  // dynamic FormGroup manipulation) but only the active type's fields are shown.
  protected readonly apiKeyControl = new FormControl<string>('', { nonNullable: true })
  protected readonly usernameControl = new FormControl<string>('', { nonNullable: true })
  protected readonly passwordControl = new FormControl<string>('', { nonNullable: true })
  protected readonly tokenControl = new FormControl<string>('', { nonNullable: true })
  protected readonly discoveryUrlControl = new FormControl<string>('', { nonNullable: true })
  protected readonly authorizationUrlControl = new FormControl<string>('', { nonNullable: true })
  protected readonly tokenUrlControl = new FormControl<string>('', { nonNullable: true })
  protected readonly clientIdControl = new FormControl<string>('', { nonNullable: true })
  protected readonly clientSecretControl = new FormControl<string>('', { nonNullable: true })
  protected readonly scopesControl = new FormControl<string>('', { nonNullable: true })

  // ── Reactive derived state ────────────────────────────────────────────────
  protected readonly selectedAuthType = toSignal(this.authTypeControl.valueChanges, {
    initialValue: this.authTypeControl.value,
  })

  protected readonly credentialFields = computed<ReadonlyArray<CredentialField>>(
    () => CREDENTIAL_FIELDS[this.selectedAuthType()]
  )

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /** Kept for the update payload (preserves server-side userId/namespaceId/authType). */
  private existingConfig: AuthSettingDto | null = null

  /**
   * Snapshot of credential values loaded from the server (typically masked sentinels).
   * On submit, each credential field is compared to its snapshot — if unchanged, it is
   * set to `undefined` in the payload so the backend keeps the persisted credential (NFR-SEC-1).
   */
  private initialCredentials: Partial<Record<string, string>> = {}

  constructor() {
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

  private deriveScopeFromConfig(config: AuthSettingDto): AuthSettingScope {
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
          // Hydrate metadata only — credentials never carry across resources (NFR-SEC-1).
          this.nameControl.setValue(config.name)
          this.descriptionControl.setValue(config.description ?? null)
          this.authTypeControl.setValue(config.authType as AuthSettingType)
          this.clearCredentialControls()
          this.initialCredentials = {}
          this.isLoading.set(false)
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

  private applyConfigToForm(config: AuthSettingDto): void {
    this.nameControl.setValue(config.name)
    this.descriptionControl.setValue(config.description ?? null)
    this.authTypeControl.setValue(config.authType as AuthSettingType)
    this.clearCredentialControls()
    this.initialCredentials = {}

    // Hydrate the type-specific credential fields from the loaded config.
    // Each field value is stored in initialCredentials for masking on submit.
    switch (config.authType) {
      case 'ApiKeyAuthSetting': {
        const v = (config as ApiKeyAuthSetting).apiKey ?? ''
        this.apiKeyControl.setValue(v)
        this.initialCredentials['apiKey'] = v
        break
      }
      case 'BasicAuthAuthSetting': {
        const c = config as BasicAuthAuthSetting
        const u = c.username ?? ''
        const p = c.password ?? ''
        this.usernameControl.setValue(u)
        this.passwordControl.setValue(p)
        this.initialCredentials['username'] = u
        this.initialCredentials['password'] = p
        break
      }
      case 'BearerTokenAuthSetting': {
        const v = (config as BearerTokenAuthSetting).token ?? ''
        this.tokenControl.setValue(v)
        this.initialCredentials['token'] = v
        break
      }
      case 'OAuthDiscoverableAuthSetting': {
        const c = config as OAuthDiscoverableAuthSetting
        this.discoveryUrlControl.setValue(c.discoveryUrl ?? '')
        this.clientIdControl.setValue(c.clientId ?? '')
        this.clientSecretControl.setValue(c.clientSecret ?? '')
        this.scopesControl.setValue(c.scopes ?? '')
        this.initialCredentials['discoveryUrl'] = c.discoveryUrl ?? ''
        this.initialCredentials['clientId'] = c.clientId ?? ''
        this.initialCredentials['clientSecret'] = c.clientSecret ?? ''
        this.initialCredentials['scopes'] = c.scopes ?? ''
        break
      }
      case 'OAuthCustomAuthSetting': {
        const c = config as OAuthCustomAuthSetting
        this.authorizationUrlControl.setValue(c.authorizationUrl ?? '')
        this.tokenUrlControl.setValue(c.tokenUrl ?? '')
        this.clientIdControl.setValue(c.clientId ?? '')
        this.clientSecretControl.setValue(c.clientSecret ?? '')
        this.scopesControl.setValue(c.scopes ?? '')
        this.initialCredentials['authorizationUrl'] = c.authorizationUrl ?? ''
        this.initialCredentials['tokenUrl'] = c.tokenUrl ?? ''
        this.initialCredentials['clientId'] = c.clientId ?? ''
        this.initialCredentials['clientSecret'] = c.clientSecret ?? ''
        this.initialCredentials['scopes'] = c.scopes ?? ''
        break
      }
      case 'OAuthRegisteredAuthSetting': {
        const c = config as OAuthRegisteredAuthSetting
        this.authorizationUrlControl.setValue(c.authorizationUrl ?? '')
        this.tokenUrlControl.setValue(c.tokenUrl ?? '')
        this.clientIdControl.setValue(c.clientId ?? '')
        this.clientSecretControl.setValue(c.clientSecret ?? '')
        this.scopesControl.setValue(c.scopes ?? '')
        this.initialCredentials['authorizationUrl'] = c.authorizationUrl ?? ''
        this.initialCredentials['tokenUrl'] = c.tokenUrl ?? ''
        this.initialCredentials['clientId'] = c.clientId ?? ''
        this.initialCredentials['clientSecret'] = c.clientSecret ?? ''
        this.initialCredentials['scopes'] = c.scopes ?? ''
        break
      }
    }
  }

  private clearCredentialControls(): void {
    this.apiKeyControl.setValue('')
    this.usernameControl.setValue('')
    this.passwordControl.setValue('')
    this.tokenControl.setValue('')
    this.discoveryUrlControl.setValue('')
    this.authorizationUrlControl.setValue('')
    this.tokenUrlControl.setValue('')
    this.clientIdControl.setValue('')
    this.clientSecretControl.setValue('')
    this.scopesControl.setValue('')
  }

  /**
   * Reads a credential control value, applying the masking rule:
   * in edit mode, if the value is unchanged from the initial sentinel, return `undefined`
   * so the backend keeps the persisted credential (NFR-SEC-1).
   */
  private credentialValue(key: string, value: string): string | undefined {
    if (this.isEditMode() && value === this.initialCredentials[key]) return undefined
    return value || undefined
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return
    if (this.isEditMode() && !this.existingConfig?.id) {
      this.navigateBack()
      return
    }

    this.isSubmitting.set(true)
    const name = this.nameControl.value.trim()
    const description = this.descriptionControl.value?.trim() || undefined
    const authType = this.authTypeControl.value
    const scope = this.form.getRawValue().scope
    const namespaceIdForCreate = this.isPlatformMode ? null : (this.namespaceId ?? null)

    const typed = this.buildTypedPayload(authType, name, description)

    const call$ =
      this.isEditMode() && this.existingConfig?.id
        ? this.state.update(this.existingConfig.id, typed, this.existingConfig)
        : this.state.create(typed, scope, namespaceIdForCreate)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  private buildTypedPayload(authType: AuthSettingType, name: string, description: string | undefined): AuthSettingDto {
    const base = { name, description }
    switch (authType) {
      case 'ApiKeyAuthSetting':
        return {
          ...base,
          authType: 'ApiKeyAuthSetting',
          apiKey: this.credentialValue('apiKey', this.apiKeyControl.value),
        } satisfies ApiKeyAuthSetting
      case 'BasicAuthAuthSetting':
        return {
          ...base,
          authType: 'BasicAuthAuthSetting',
          username: this.credentialValue('username', this.usernameControl.value),
          password: this.credentialValue('password', this.passwordControl.value),
        } satisfies BasicAuthAuthSetting
      case 'BearerTokenAuthSetting':
        return {
          ...base,
          authType: 'BearerTokenAuthSetting',
          token: this.credentialValue('token', this.tokenControl.value),
        } satisfies BearerTokenAuthSetting
      case 'OAuthDiscoverableAuthSetting':
        return {
          ...base,
          authType: 'OAuthDiscoverableAuthSetting',
          discoveryUrl: this.credentialValue('discoveryUrl', this.discoveryUrlControl.value),
          clientId: this.credentialValue('clientId', this.clientIdControl.value),
          clientSecret: this.credentialValue('clientSecret', this.clientSecretControl.value),
          scopes: this.credentialValue('scopes', this.scopesControl.value),
        } satisfies OAuthDiscoverableAuthSetting
      case 'OAuthCustomAuthSetting':
        return {
          ...base,
          authType: 'OAuthCustomAuthSetting',
          authorizationUrl: this.credentialValue('authorizationUrl', this.authorizationUrlControl.value),
          tokenUrl: this.credentialValue('tokenUrl', this.tokenUrlControl.value),
          clientId: this.credentialValue('clientId', this.clientIdControl.value),
          clientSecret: this.credentialValue('clientSecret', this.clientSecretControl.value),
          scopes: this.credentialValue('scopes', this.scopesControl.value),
        } satisfies OAuthCustomAuthSetting
      case 'OAuthRegisteredAuthSetting':
        return {
          ...base,
          authType: 'OAuthRegisteredAuthSetting',
          authorizationUrl: this.credentialValue('authorizationUrl', this.authorizationUrlControl.value),
          tokenUrl: this.credentialValue('tokenUrl', this.tokenUrlControl.value),
          clientId: this.credentialValue('clientId', this.clientIdControl.value),
          clientSecret: this.credentialValue('clientSecret', this.clientSecretControl.value),
          scopes: this.credentialValue('scopes', this.scopesControl.value),
        } satisfies OAuthRegisteredAuthSetting
    }
  }

  protected controlForField(key: string): FormControl<string> | undefined {
    const map: Record<string, FormControl<string>> = {
      apiKey: this.apiKeyControl,
      username: this.usernameControl,
      password: this.passwordControl,
      token: this.tokenControl,
      discoveryUrl: this.discoveryUrlControl,
      authorizationUrl: this.authorizationUrlControl,
      tokenUrl: this.tokenUrlControl,
      clientId: this.clientIdControl,
      clientSecret: this.clientSecretControl,
      scopes: this.scopesControl,
    }
    return map[key]
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

  protected trackByField(_index: number, field: CredentialField): string {
    return field.key
  }
}
