import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { NamespaceControllerService, NamespaceListItem } from '@whoz-oss/agentos-api-client'
import { ToolInvokeStateService } from '../../services/tool-invoke-state.service'
import { UserStateService } from '../../services/user-state.service'

/** Minimal namespace shape needed by the select — id + name only. */
type NamespaceOption = Pick<NamespaceListItem, 'id' | 'name'>

/**
 * ToolInvokeComponent — debug screen for calling a named tool directly.
 *
 * Supports two modes:
 *
 * **Platform/admin mode** (route: `/agentos/admin/tool-invoke`)
 *   No `namespaceId` in route params. Loads the full namespace list and renders
 *   a `<select>` so a super-admin can target any namespace.
 *
 * **Namespace mode** (route: `/agentos/:namespaceId/tool-invoke`)
 *   A `namespaceId` is present in route params. Skips the namespace list fetch
 *   entirely (avoids a wasted HTTP call and avoids leaking the list into a
 *   namespace-scoped screen). Resolves the namespace name for display only.
 *   The namespace field is rendered as read-only context, not a `<select>`.
 */
@Component({
  selector: 'agentos-tool-invoke',
  imports: [ReactiveFormsModule],
  templateUrl: './tool-invoke.component.html',
  styleUrl: './tool-invoke.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ToolInvokeComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef)
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly namespaceController = inject(NamespaceControllerService)
  private readonly userState = inject(UserStateService)
  protected readonly state = inject(ToolInvokeStateService)

  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined

  /** True when accessed via /admin/tool-invoke (no namespaceId in route). */
  protected readonly isPlatformMode = !this.namespaceId

  /** Namespace list — populated only in platform mode. */
  protected readonly namespaces = signal<NamespaceOption[]>([])
  protected readonly namespacesLoading = signal(true)

  /** Resolved namespace name — populated only in namespace mode. */
  protected readonly namespaceName = signal<string | null>(null)

  protected readonly form = new FormGroup({
    namespaceId: new FormControl<string>('', { nonNullable: true, validators: [Validators.required] }),
    userId: new FormControl<string>('', { nonNullable: true }),
    toolName: new FormControl<string>('', { nonNullable: true, validators: [Validators.required] }),
    payload: new FormControl<string>('', { nonNullable: true }),
  })

  ngOnInit(): void {
    // Pre-populate userId with the current user's id so the caller doesn't have to
    // look it up manually. The field remains editable so a super-admin can override it.
    const currentUserId = this.userState.currentUser()?.id
    if (currentUserId) {
      this.form.controls.userId.patchValue(currentUserId)
    } else {
      // User not yet loaded — fetch and patch once resolved.
      this.userState
        .loadMe()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (user) => {
            if (user.id) this.form.controls.userId.patchValue(user.id)
          },
        })
    }

    if (this.isPlatformMode) {
      // Platform mode: load the namespace list for the <select>.
      this.namespaceController
        .listAllNamespace()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (list) => {
            this.namespaces.set(list.map((item): NamespaceOption => ({ id: item.id, name: item.name })))
            this.namespacesLoading.set(false)
          },
          error: () => this.namespacesLoading.set(false),
        })
    } else {
      // Namespace mode: patch the form immediately so it is valid without user interaction.
      this.form.controls.namespaceId.patchValue(this.namespaceId!)
      this.namespacesLoading.set(false)

      // Resolve the namespace name for display only — fall back to the id on error.
      this.namespaceController
        .getByIdNamespace(this.namespaceId!)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (ns) => this.namespaceName.set(ns.name),
          error: () => this.namespaceName.set(this.namespaceId!),
        })
    }
  }

  protected submit(): void {
    if (this.form.invalid || this.state.isLoading()) return

    const { namespaceId, userId, toolName, payload } = this.form.getRawValue()

    this.state
      .invoke(namespaceId, userId.trim() || undefined, toolName.trim(), payload.trim() || undefined)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe()
  }

  protected reset(): void {
    this.state.reset()
  }

  /**
   * Pretty-print the metadata object for display.
   * Returns null when the metadata is empty so the section is hidden.
   */
  protected formatMetadata(metadata: Record<string, unknown>): string | null {
    if (!metadata || Object.keys(metadata).length === 0) return null
    return JSON.stringify(metadata, null, 2)
  }

  protected back(): void {
    if (this.isPlatformMode) {
      // Platform mode: return to the admin hub.
      this.router.navigate(['/agentos', 'admin'])
    } else {
      // Namespace mode: return to the namespace agent-configs list, which is the
      // primary landing page for namespace admins navigating from the namespace card.
      this.router.navigate(['/agentos', this.namespaceId, 'agent-configs'])
    }
  }
}
