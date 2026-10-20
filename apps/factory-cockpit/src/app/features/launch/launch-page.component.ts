import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms'
import { Router, RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatSelectModule } from '@angular/material/select'
import { FactoryApiError } from '../../core/factory-api.service'
import { LaunchStateService } from '../../core/launch-state.service'
import { FactoryStore } from '../../core/factory.store'
import { ShellState } from '../../core/shell-state'

/** Human-readable, non-fabricated launch error message. */
export function formatLaunchError(error: FactoryApiError | null | undefined): string {
  const code = error?.code ?? 'UNKNOWN_ERROR'
  const message = error?.message?.trim()
  return message ? `${message} (${code})` : `Launch failed (${code}).`
}

/**
 * Dedicated `/launch` screen using Factory's canonical create-and-submit use
 * case. Factory owns workflow identity and execution attribution. The browser
 * selects one of the namespaces AgentOS exposes to the current user. Factory
 * resolves or initializes the organization-scoped workstream association.
 * A backend failure keeps the form visible and surfaces a readable banner.
 */
@Component({
  selector: 'sf-launch-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './launch-page.component.html',
  styleUrl: './launch-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [LaunchStateService],
})
export class LaunchPageComponent {
  private readonly launchState = inject(LaunchStateService)
  private readonly store = inject(FactoryStore)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly workflowTypes = this.launchState.workflowTypes
  protected readonly namespaces = this.launchState.namespaces
  protected readonly submitting = this.launchState.submitting
  protected readonly successMessage = signal<string | null>(null)
  protected readonly errorMessage = signal<string | null>(null)
  protected readonly definitionsLoading = this.launchState.loading
  protected readonly definitionsError = computed(() => this.launchState.definitionsError()?.message ?? null)
  protected readonly launchTargetsLoading = this.launchState.loading
  protected readonly launchTargetsError = computed(() => this.launchState.namespacesError()?.message ?? null)

  protected readonly form = inject(NonNullableFormBuilder).group({
    workflowType: ['', Validators.required],
    namespaceId: ['', Validators.required],
    title: ['', Validators.maxLength(200)],
    ticket: ['', Validators.maxLength(64)],
    initialRequest: ['', Validators.maxLength(4000)],
  })

  private readonly selectedNamespaceId = toSignal(this.form.controls.namespaceId.valueChanges, {
    initialValue: this.form.controls.namespaceId.value,
  })
  protected readonly selectedNamespace = computed(
    () => this.namespaces().find((item) => item.id === this.selectedNamespaceId()) ?? null
  )

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Sandboxes', link: '/sandboxes' }, { label: 'Launch a run' }])
    this.launchState.loadOptions()
    effect(() => {
      const types = this.workflowTypes()
      if (types.length === 1 && !this.form.controls.workflowType.value) {
        this.form.controls.workflowType.setValue(types[0] ?? '')
      }
      const namespaces = this.namespaces()
      if (namespaces.length === 1 && !this.form.controls.namespaceId.value) {
        this.form.controls.namespaceId.setValue(namespaces[0]?.id ?? '')
      }
    })
  }

  /** Invoke the single Factory-owned create-and-submit use case. */
  protected onSubmit(): void {
    if (this.submitting()) return
    if (this.form.invalid) {
      this.form.markAllAsTouched()
      return
    }

    const { workflowType, namespaceId, title, ticket, initialRequest } = this.form.getRawValue()
    if (
      this.launchTargetsLoading() ||
      this.launchTargetsError() ||
      !this.namespaces().some((item) => item.id === namespaceId)
    ) {
      this.errorMessage.set('Select an available namespace before launching.')
      return
    }
    const trimmedTitle = title.trim()
    const trimmedTicket = ticket.trim()
    const trimmedInitialRequest = initialRequest.trim()
    this.submitting.set(true)
    this.successMessage.set(null)
    this.errorMessage.set(null)

    this.launchState
      .launch({
        namespaceId,
        workflowType,
        ...(trimmedTitle ? { title: trimmedTitle } : {}),
        ...(trimmedInitialRequest ? { initialRequest: trimmedInitialRequest } : {}),
        ...(trimmedTicket ? { parameters: { ticket: trimmedTicket } } : {}),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          const submissionId = response?.submissionId
          this.successMessage.set(submissionId ? `Launch accepted (id: ${submissionId})` : 'Launch accepted.')
          this.store.refresh()
          void this.router.navigate(['/sessions', response.workflowId])
        },
        error: (error: FactoryApiError) => {
          this.errorMessage.set(formatLaunchError(error))
        },
      })
  }
}
