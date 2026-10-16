import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms'
import { Router, RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatSelectModule } from '@angular/material/select'
import {
  FactoryApiError,
  FactoryApiService,
  NamespaceOption,
  extractNamespaceOptions,
  generateCorrelationId,
} from '../../core/factory-api.service'
import { FactoryStore } from '../../core/factory.store'
import { ShellState } from '../../core/shell-state'

/** Extract the unique, non-blank `workflowType`s from a definitions payload. */
export function extractWorkflowTypes(payload: unknown): string[] {
  const items = Array.isArray(payload)
    ? payload
    : Array.isArray((payload as { items?: unknown } | null)?.items)
      ? ((payload as { items: unknown[] }).items as unknown[])
      : []
  const types = new Set<string>()
  for (const item of items) {
    if (typeof item === 'string') {
      if (item.trim()) types.add(item.trim())
      continue
    }
    const value = (item as { workflowType?: unknown } | null)?.workflowType
    if (typeof value === 'string' && value.trim()) types.add(value.trim())
  }
  return Array.from(types)
}

/** Human-readable, non-fabricated launch error message. */
export function formatLaunchError(error: FactoryApiError | null | undefined): string {
  const code = error?.code ?? 'UNKNOWN_ERROR'
  const message = error?.message?.trim()
  return message ? `${message} (${code})` : `Launch failed (${code}).`
}

/**
 * Dedicated `/lancer` screen using Factory's canonical create-and-submit use
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
})
export class LaunchPageComponent {
  private readonly api = inject(FactoryApiService)
  private readonly store = inject(FactoryStore)
  private readonly router = inject(Router)

  protected readonly workflowTypes = signal<string[]>([])
  protected readonly namespaces = signal<NamespaceOption[]>([])
  protected readonly submitting = signal(false)
  protected readonly successMessage = signal<string | null>(null)
  protected readonly errorMessage = signal<string | null>(null)
  protected readonly definitionsLoading = signal(false)
  protected readonly definitionsError = signal<string | null>(null)
  protected readonly launchTargetsLoading = signal(false)
  protected readonly launchTargetsError = signal<string | null>(null)

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
    this.loadDefinitions()
    this.loadLaunchTargets()
  }

  /** Fetch the registered workflow definitions and expose their types. */
  private loadDefinitions(): void {
    this.definitionsLoading.set(true)
    this.definitionsError.set(null)
    this.api.getWorkflowDefinitions().subscribe({
      next: (payload) => {
        const types = extractWorkflowTypes(payload)
        this.workflowTypes.set(types)
        if (types.length === 1) {
          const [onlyType] = types
          if (onlyType) this.form.controls.workflowType.setValue(onlyType)
        }
        this.definitionsLoading.set(false)
      },
      error: (error: FactoryApiError) => {
        this.definitionsError.set(error?.message ?? 'Workflow definitions unavailable.')
        this.definitionsLoading.set(false)
      },
    })
  }

  /** AgentOS' list is a launch UX aid, not an individual backend authorization. */
  private loadLaunchTargets(): void {
    this.launchTargetsLoading.set(true)
    this.launchTargetsError.set(null)
    this.api.getNamespaces().subscribe({
      next: (payload) => {
        const options = extractNamespaceOptions(payload)
        this.namespaces.set(options)
        this.form.controls.namespaceId.setValue(options.length === 1 ? (options[0]?.id ?? '') : '')
        this.launchTargetsLoading.set(false)
      },
      error: (error: FactoryApiError) => {
        this.namespaces.set([])
        this.form.controls.namespaceId.setValue('')
        this.launchTargetsError.set(error?.message ?? 'Namespaces unavailable.')
        this.launchTargetsLoading.set(false)
      },
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
    const idempotencyKey = generateCorrelationId()

    this.submitting.set(true)
    this.successMessage.set(null)
    this.errorMessage.set(null)

    this.api
      .createWorkflowRun(
        {
          namespaceId,
          workflowType,
          ...(trimmedTitle ? { title: trimmedTitle } : {}),
          ...(trimmedInitialRequest ? { initialRequest: trimmedInitialRequest } : {}),
          ...(trimmedTicket ? { parameters: { ticket: trimmedTicket } } : {}),
        },
        idempotencyKey
      )
      .subscribe({
        next: (response) => {
          this.submitting.set(false)
          const submissionId = response?.submissionId
          this.successMessage.set(submissionId ? `Launch accepted (id: ${submissionId})` : 'Launch accepted.')
          this.store.refresh()
          void this.router.navigate(['/sessions', response.workflowId])
        },
        error: (error: FactoryApiError) => {
          this.submitting.set(false)
          this.errorMessage.set(formatLaunchError(error))
        },
      })
  }
}
