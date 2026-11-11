import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AuthSettingDto } from '@whoz-oss/agentos-api-client'
import { ButtonDirective, SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { catchError, finalize, forkJoin, map, of, Subscription, switchMap } from 'rxjs'
import {
  AuthSettingConfigStateService,
  AuthSettingConfigViewModel,
  AuthSettingScope,
  ConnectionOutcome,
} from '../../services/auth-setting-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { openOAuthPopup } from '../../services/oauth-popup'
import { defaultCreateScope } from '../_shared/default-create-scope'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/** Auth types whose connection (tokens) is stored per user: the ones that can be connected. */
const OAUTH_AUTH_TYPES: ReadonlySet<string> = new Set([
  'OAuthDiscoverableAuthSetting',
  'OAuthMcpDiscoverableAuthSetting',
  'OAuthCustomAuthSetting',
  'OAuthRegisteredAuthSetting',
])

/** `unknown` while it is being read, or when it could not be (no claim is made then). */
type ConnectionStatus = 'connected' | 'disconnected' | 'unknown'

interface ActionMessage {
  kind: 'error' | 'info'
  text: string
}

interface AuthSettingRow {
  key: string
  config: AuthSettingDto
  scope: AuthSettingScope
  scopeLabel: 'Platform' | 'Namespace' | 'Personal'
  canMutate: boolean
}

@Component({
  selector: 'agentos-auth-settings-all-scopes',
  imports: [
    ButtonDirective,
    SectionHeaderComponent,
    TableDirective,
    TagComponent,
    TableFilterComponent,
    RowDeleteActionComponent,
  ],
  templateUrl: './auth-settings-all-scopes.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthSettingsAllScopesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(AuthSettingConfigStateService)
  private readonly namespaceRole = inject(NamespaceRoleStateService)

  protected namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly isAdmin = signal(false)
  protected readonly isLoading = signal(true)
  protected readonly rows = signal<AuthSettingRow[]>([])
  protected readonly deletion = createRowDeletionState()

  /** Connection state per row key, for the OAuth rows. */
  protected readonly connections = signal<Record<string, ConnectionStatus>>({})
  /** Row whose authorization is in progress (popup open, waiting for the connection to land). */
  protected readonly connectingKey = signal<string | null>(null)
  protected readonly resetConfirmingKey = signal<string | null>(null)
  protected readonly resettingKey = signal<string | null>(null)
  protected readonly actionMessage = signal<ActionMessage | null>(null)
  private connectAttempt: Subscription | null = null
  private connectPopup: Window | null = null
  private connectionsLoad: Subscription | null = null

  protected readonly rowFilter = createRowFilter(this.rows, (row) =>
    [row.config.name, row.config.authType, row.scopeLabel].join(' ')
  )

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const namespaceId = params.get('namespaceId') ?? ''
      if (namespaceId && namespaceId !== this.namespaceId) this.namespaceId = namespaceId
      this.state.setNamespace(namespaceId)
    })
    this.route.paramMap
      .pipe(
        map((params) => params.get('namespaceId') ?? ''),
        switchMap((id) => (id ? this.namespaceRole.isAdminOfNamespace$(id) : of(false))),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((isAdmin) => this.isAdmin.set(isAdmin))
    this.state.vm$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (viewModel) => {
        this.rows.set(this.toRows(viewModel))
        this.isLoading.set(false)
        this.loadConnections()
      },
      error: () => this.isLoading.set(false),
    })
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', this.namespaceId, 'auth-settings', 'new'], {
      queryParams: { scope: defaultCreateScope(this.isAdmin()) },
    })
  }

  protected edit(row: AuthSettingRow): void {
    if (!row.canMutate || !row.config.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'auth-settings', row.config.id, 'edit'], {
      queryParams: { scope: row.scope },
    })
  }

  protected confirmDelete(row: AuthSettingRow): void {
    if (!row.canMutate || !row.config.id) return
    this.deletion.execute(row.key, {
      request$: this.state.delete(row.config.id, row.scope).pipe(takeUntilDestroyed(this.destroyRef)),
      errorMessage: `Deleting "${row.config.name}" failed. Try again.`,
    })
  }

  protected isOAuth(row: AuthSettingRow): boolean {
    return OAUTH_AUTH_TYPES.has(row.config.authType)
  }

  protected connectionOf(row: AuthSettingRow): ConnectionStatus {
    return this.connections()[row.key] ?? 'unknown'
  }

  /**
   * Authorizes the caller's own account for this setting, or authorizes it again when already connected.
   * The existing connection is only replaced once the new authorization completes, so abandoning leaves it as is.
   */
  protected connect(row: AuthSettingRow): void {
    const id = row.config.id
    if (!id || this.connectingKey() || this.resettingKey()) return
    // Opened blank inside the click so the browser lets it through; pointed at the provider once the URL is known.
    const popup = openOAuthPopup()
    if (!popup) {
      this.actionMessage.set({
        kind: 'error',
        text: 'The browser blocked the authorization window. Allow pop-ups for this site and try again.',
      })
      return
    }
    this.actionMessage.set(null)
    this.resetConfirmingKey.set(null)
    this.connectPopup = popup
    this.connectingKey.set(row.key)
    this.connectAttempt = this.state
      .connect(
        id,
        this.namespaceParam(),
        (authorizationUrl) => (popup.location.href = authorizationUrl),
        () => popup.closed
      )
      .pipe(
        finalize(() => {
          this.connectingKey.set(null)
          this.connectAttempt = null
          this.connectPopup = null
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (outcome) => this.onConnectOutcome(row, outcome, popup),
        error: () => {
          popup.close()
          this.actionMessage.set({
            kind: 'error',
            text: `Connecting "${row.config.name}" failed. Check that its OAuth settings are complete, then try again.`,
          })
        },
      })
  }

  protected cancelConnect(): void {
    this.connectAttempt?.unsubscribe()
    this.connectPopup?.close()
  }

  protected requestReset(row: AuthSettingRow): void {
    if (this.resettingKey() || this.connectingKey()) return
    this.actionMessage.set(null)
    this.resetConfirmingKey.set(row.key)
  }

  protected cancelReset(): void {
    if (this.resettingKey()) return
    this.resetConfirmingKey.set(null)
  }

  /** Forgets the caller's connection: the row flips to "Not connected" once the new state is read. */
  protected confirmReset(row: AuthSettingRow): void {
    const id = row.config.id
    if (!id || this.resettingKey() || this.resetConfirmingKey() !== row.key) return
    this.resettingKey.set(row.key)
    this.state
      .resetCredential(id, this.namespaceParam())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.resettingKey.set(null)
          this.resetConfirmingKey.set(null)
          this.loadConnections()
        },
        error: () => {
          this.resettingKey.set(null)
          this.actionMessage.set({ kind: 'error', text: `Disconnecting "${row.config.name}" failed. Try again.` })
        },
      })
  }

  protected duplicate(row: AuthSettingRow): void {
    if (!row.config.id) return
    const destinationScope =
      row.scope === 'platform' || (row.scope === 'namespace' && !this.isAdmin()) ? 'userOnNs' : row.scope
    void this.router.navigate(['/agentos', this.namespaceId, 'auth-settings', 'new'], {
      queryParams: { scope: destinationScope, template: row.config.id, templateScope: row.scope },
    })
  }

  private onConnectOutcome(row: AuthSettingRow, outcome: ConnectionOutcome, popup: Window): void {
    popup.close()
    // Whatever happened, the rows tell it: the status tag is the feedback, read again from the backend.
    this.loadConnections()
    if (outcome === 'abandoned') {
      this.actionMessage.set({
        kind: 'info',
        text: `The authorization window was closed before "${row.config.name}" got connected.`,
      })
    } else if (outcome === 'timeout') {
      this.actionMessage.set({
        kind: 'info',
        text: `No authorization was received for "${row.config.name}" in time. Try again.`,
      })
    }
  }

  /** The namespace the settings are used from; the backend resolves a setting name against it. */
  private namespaceParam(): string | undefined {
    return this.namespaceId || undefined
  }

  private loadConnections(): void {
    this.connectionsLoad?.unsubscribe()
    const reads = this.rows()
      .filter((row) => this.isOAuth(row) && row.config.id)
      .map((row) =>
        this.state.getConnection(row.config.id as string, this.namespaceParam()).pipe(
          map((connection) => [row.key, connection.connected ? 'connected' : 'disconnected'] as const),
          // An unreadable state is shown as unknown rather than as "not connected", which would be a claim.
          catchError(() => of([row.key, 'unknown'] as const))
        )
      )
    if (reads.length === 0) {
      this.connections.set({})
      return
    }
    this.connectionsLoad = forkJoin(reads)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((entries) => this.connections.set(Object.fromEntries(entries)))
  }

  private toRows(vm: AuthSettingConfigViewModel): AuthSettingRow[] {
    return [
      ...this.mapScope(vm.platform, 'platform', 'Platform', false),
      ...this.mapScope(vm.namespace, 'namespace', 'Namespace', this.isAdmin()),
      ...this.mapScope(vm.userOnNs, 'userOnNs', 'Personal', true),
      ...this.mapScope(vm.userGlobal, 'userGlobal', 'Personal', true),
    ]
  }

  private mapScope(
    configs: AuthSettingDto[],
    scope: AuthSettingScope,
    scopeLabel: AuthSettingRow['scopeLabel'],
    canMutate: boolean
  ): AuthSettingRow[] {
    return configs.map((config, index) => ({
      key: `${scope}:${config.id ?? index}`,
      config,
      scope,
      scopeLabel,
      canMutate,
    }))
  }
}
