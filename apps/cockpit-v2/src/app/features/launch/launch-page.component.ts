import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms'
import { Router, RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatSelectModule } from '@angular/material/select'
import { FactoryApiError, FactoryApiService, generateCorrelationId } from '../../core/factory-api.service'
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

/** Namespace option displayed by name while submitting its stable id. */
export interface NamespaceOption {
  id: string
  name: string
}

/** Extract unique namespace options from the AgentOS namespace API payload. */
export function extractNamespaceOptions(payload: unknown): NamespaceOption[] {
  const items = Array.isArray(payload) ? payload : []
  const options = new Map<string, NamespaceOption>()
  for (const item of items) {
    if (typeof item === 'string') {
      const id = item.trim()
      if (id) options.set(id, { id, name: id })
      continue
    }
    const record = item as { namespaceId?: unknown; id?: unknown; name?: unknown } | null
    const rawId = record?.namespaceId ?? record?.id
    if (typeof rawId !== 'string' || !rawId.trim()) continue
    const id = rawId.trim()
    const name = typeof record?.name === 'string' && record.name.trim() ? record.name.trim() : id
    options.set(id, { id, name })
  }
  return Array.from(options.values()).sort((left, right) => left.name.localeCompare(right.name))
}

/** Human-readable, non-fabricated launch error message. */
export function formatLaunchError(error: FactoryApiError | null | undefined): string {
  const code = error?.code ?? 'UNKNOWN_ERROR'
  const message = error?.message?.trim()
  return message ? `${message} (${code})` : `Lancement impossible (${code}).`
}

/**
 * Dedicated `/lancer` screen using Factory's canonical create-and-submit use
 * case. Factory owns workflow identity and execution attribution. The browser
 * supplies the selected namespace only through the trusted/local HTTP boundary.
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

  protected readonly form = inject(NonNullableFormBuilder).group({
    workflowType: ['', Validators.required],
    namespaceId: ['', Validators.required],
    title: ['', Validators.maxLength(200)],
    ticket: ['', Validators.maxLength(64)],
    initialRequest: ['', Validators.maxLength(4000)],
  })

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Sandboxes', link: '/sandboxes' }, { label: 'Lancer un run' }])
    this.loadDefinitions()
    this.loadNamespaces()
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
        this.definitionsError.set(error?.message ?? 'Définitions de workflow indisponibles.')
        this.definitionsLoading.set(false)
      },
    })
  }

  /** Fetch the AgentOS namespaces (the API degrades to `[]` on failure). */
  private loadNamespaces(): void {
    this.api.getNamespaces().subscribe((payload) => {
      const options = extractNamespaceOptions(payload)
      this.namespaces.set(options)
      const [firstNamespace] = options
      if (firstNamespace && !this.form.controls.namespaceId.value) {
        this.form.controls.namespaceId.setValue(firstNamespace.id)
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
          workflowType,
          ...(trimmedTitle ? { title: trimmedTitle } : {}),
          ...(trimmedInitialRequest ? { initialRequest: trimmedInitialRequest } : {}),
          ...(trimmedTicket ? { parameters: { ticket: trimmedTicket } } : {}),
        },
        namespaceId,
        idempotencyKey
      )
      .subscribe({
        next: (response) => {
          this.submitting.set(false)
          const submissionId = response?.submissionId
          this.successMessage.set(submissionId ? `Lancement accepté (id: ${submissionId})` : 'Lancement accepté.')
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
