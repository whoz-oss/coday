import { HttpErrorResponse } from '@angular/common/http'
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AuthSettingDto, NamespaceGit, NamespaceGitControllerService } from '@whoz-oss/agentos-api-client'
import { catchError, forkJoin, of } from 'rxjs'
import { AuthSettingConfigStateService } from '../../services/auth-setting-config-state.service'

/**
 * Auth settings usable as a Git service account over HTTPS.
 *
 * These are the **TS discriminants**, not the backend enum names: the auth-setting state service
 * normalises `authType` from the Jackson name (`BEARER_TOKEN`) to the generated union's
 * discriminant (`BearerTokenAuthSetting`) when it loads. Filtering on the backend names here
 * would silently match nothing and leave the picker empty.
 */
const GIT_CAPABLE_AUTH_TYPES = ['BearerTokenAuthSetting', 'BasicAuthAuthSetting', 'ApiKeyAuthSetting']

/** Short label for the picker, since the discriminant is not user-facing wording. */
const AUTH_TYPE_LABELS: Readonly<Record<string, string>> = {
  BearerTokenAuthSetting: 'token',
  BasicAuthAuthSetting: 'basic auth',
  ApiKeyAuthSetting: 'API key',
}

/**
 * NamespaceGitComponent — associate a namespace with a Git repository.
 *
 * Loaded at `/:namespaceId/git`. A dedicated page rather than a section of
 * `NamespaceFormComponent`, for the same reason `NamespaceMembersComponent` is one: the
 * association is orthogonal to the name/description/configPath entity form, and cannot apply to a
 * namespace that does not exist yet.
 *
 * It deliberately does not reuse the generic integration-configuration screens:
 *
 * - the association is a capability of the namespace, not a tool an agent may select, and the
 *   generic screens offer every namespace configuration as agent-selectable;
 * - the service account must be referenced by **id**. The generic form's auth picker stores a
 *   *name*, and a name resolves through the user-level overlay layers — so a member with a
 *   homonymous personal setting would silently become the Git identity of the namespace.
 *
 * The secret itself is never handled here: the picker lists auth settings and submits an id.
 */
@Component({
  selector: 'agentos-namespace-git',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './namespace-git.component.html',
  styleUrl: './namespace-git.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceGitComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly gitApi = inject(NamespaceGitControllerService)
  private readonly authSettingState = inject(AuthSettingConfigStateService)
  private readonly destroyRef = inject(DestroyRef)

  readonly isLoading = signal(true)
  readonly isSaving = signal(false)
  readonly associationLoaded = signal(false)
  readonly errorMessage = signal<string | null>(null)
  readonly association = signal<NamespaceGit | null>(null)
  readonly authSettings = signal<AuthSettingDto[]>([])

  // Form state
  readonly repositoryUrl = signal('')
  readonly mainBranch = signal('main')
  readonly serviceAuthSettingId = signal('')
  readonly autoWorktree = signal(false)
  readonly setupCommand = signal('')

  private namespaceId = ''

  readonly isAssociated = computed(() => this.association()?.associated === true)

  /** Only settings that can actually authenticate an HTTPS remote are offered. */
  readonly eligibleAuthSettings = computed(() =>
    this.authSettings().filter((setting) => GIT_CAPABLE_AUTH_TYPES.includes(setting.authType as string))
  )

  /** User-facing wording for an auth setting's kind. */
  authTypeLabel(authType: string | undefined): string {
    return AUTH_TYPE_LABELS[authType ?? ''] ?? authType ?? 'unknown'
  }

  readonly canSave = computed(
    () =>
      this.associationLoaded() &&
      !this.isLoading() &&
      !this.isSaving() &&
      this.repositoryUrl().trim().length > 0 &&
      this.serviceAuthSettingId().length > 0
  )

  /** Preparation state of the managed clone, when one has been attempted. */
  readonly checkoutStatus = computed(() => this.association()?.checkoutStatus ?? null)

  ngOnInit(): void {
    this.namespaceId = this.route.snapshot.paramMap.get('namespaceId') ?? ''
    this.load()
  }

  retryLoad(): void {
    if (this.isLoading() || this.isSaving()) return
    this.load()
  }

  private load(): void {
    this.isLoading.set(true)
    this.associationLoaded.set(false)
    this.errorMessage.set(null)
    forkJoin({
      association: this.gitApi.getAssociationNamespaceGit(this.namespaceId),
      authSettings: this.authSettingState.loadNamespaceSettings(this.namespaceId),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ association, authSettings }) => {
          this.authSettings.set(authSettings ?? [])
          this.acceptAssociation(association)
          this.isLoading.set(false)
        },
        error: (error: HttpErrorResponse) => {
          console.error('Failed to load the Git repository settings', error)
          this.errorMessage.set(this.messageOf(error))
          this.isLoading.set(false)
        },
      })
  }

  /** An incomplete error response must never turn existing setup/automation into empty defaults. */
  private acceptAssociation(association: NamespaceGit): void {
    this.association.set(association)
    const complete =
      association?.associated === false ||
      (association?.associated === true &&
        typeof association.repositoryUrl === 'string' &&
        typeof association.mainBranch === 'string' &&
        typeof association.serviceAuthSettingId === 'string' &&
        typeof association.autoWorktreeForRootCases === 'boolean' &&
        (association.setupCommand == null || typeof association.setupCommand === 'string'))
    this.associationLoaded.set(complete)
    if (complete) {
      this.applyToForm(association)
    } else {
      this.errorMessage.set('The repository settings could not be loaded completely. Retry before making changes.')
    }
  }

  private applyToForm(association: NamespaceGit): void {
    this.repositoryUrl.set(association.repositoryUrl ?? '')
    this.mainBranch.set(association.mainBranch ?? 'main')
    this.serviceAuthSettingId.set(association.serviceAuthSettingId ?? '')
    this.autoWorktree.set(association.autoWorktreeForRootCases ?? false)
    this.setupCommand.set(association.setupCommand ?? '')
  }

  save(): void {
    if (!this.canSave()) return
    this.isSaving.set(true)
    this.errorMessage.set(null)

    this.gitApi
      .setAssociationNamespaceGit(this.namespaceId, {
        repositoryUrl: this.repositoryUrl().trim(),
        mainBranch: this.mainBranch().trim() || undefined,
        serviceAuthSettingId: this.serviceAuthSettingId(),
        autoWorktreeForRootCases: this.autoWorktree(),
        setupCommand: this.setupCommand().trim() || undefined,
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((error: HttpErrorResponse) => {
          console.error('Failed to save the Git association', error)
          // The server validates the URL scheme, the branch name and the auth reference; surface
          // its message rather than a generic one, since it says exactly what to fix.
          this.errorMessage.set(this.messageOf(error))
          this.isSaving.set(false)
          return of(null)
        })
      )
      .subscribe((association) => {
        if (association) {
          this.acceptAssociation(association)
        }
        this.isSaving.set(false)
      })
  }

  remove(): void {
    if (!this.associationLoaded() || this.isLoading() || this.isSaving() || !this.isAssociated()) return
    if (!confirm('Remove the repository association? Existing workspaces keep their worktree.')) return
    this.isSaving.set(true)

    this.gitApi
      .removeAssociationNamespaceGit(this.namespaceId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((error: HttpErrorResponse) => {
          console.error('Failed to remove the Git association', error)
          this.errorMessage.set(this.messageOf(error))
          this.isSaving.set(false)
          return of(null)
        })
      )
      .subscribe((association) => {
        if (association) {
          this.acceptAssociation(association)
        }
        this.isSaving.set(false)
      })
  }

  cancel(): void {
    void this.router.navigate(['/agentos/namespaces'])
  }

  private messageOf(error: HttpErrorResponse): string {
    return error.error?.message ?? error.message ?? 'Unexpected error'
  }
}
