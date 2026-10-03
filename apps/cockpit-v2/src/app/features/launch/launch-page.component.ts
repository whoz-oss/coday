import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms'
import { Router, RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatSelectModule } from '@angular/material/select'
import { catchError, of, switchMap, throwError } from 'rxjs'
import {
  FactoryApiError,
  FactoryApiService,
  RunWorkflowRequest,
  StartWorkflowRequest,
  isWorkflowConflict,
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

/** Extract the unique, non-blank namespace ids from a namespaces payload. */
export function extractNamespaceIds(payload: unknown): string[] {
  const items = Array.isArray(payload) ? payload : []
  const ids = new Set<string>()
  for (const item of items) {
    if (typeof item === 'string') {
      if (item.trim()) ids.add(item.trim())
      continue
    }
    const record = item as { namespaceId?: unknown; id?: unknown; name?: unknown } | null
    const value = record?.namespaceId ?? record?.id ?? record?.name
    if (typeof value === 'string' && value.trim()) ids.add(value.trim())
  }
  return Array.from(ids)
}

/** Human-readable, non-fabricated launch error message. */
export function formatLaunchError(error: FactoryApiError | null | undefined): string {
  const code = error?.code ?? 'UNKNOWN_ERROR'
  const message = error?.message?.trim()
  return message ? `${message} (${code})` : `Lancement impossible (${code}).`
}

/**
 * Dedicated `/lancer` screen: materializes a workflow instance from a registered
 * definition (`start`) then triggers the durable run (`run`) on the real
 * factory-service endpoints.
 *
 * The cockpit never fabricates a success: a backend failure keeps the form on
 * screen and surfaces a readable banner.
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
  protected readonly namespaces = signal<string[]>([])
  protected readonly submitting = signal(false)
  protected readonly successMessage = signal<string | null>(null)
  protected readonly errorMessage = signal<string | null>(null)
  protected readonly definitionsLoading = signal(false)
  protected readonly definitionsError = signal<string | null>(null)

  protected readonly form = inject(NonNullableFormBuilder).group({
    workflowType: ['', Validators.required],
    namespaceId: ['', Validators.required],
    controllerRequest: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(4000)]],
    repoRoot: [''],
    ticket: [''],
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

  /** Fetch the AgentOS namespaces (already degrades to `[]` on failure). */
  private loadNamespaces(): void {
    this.api.getNamespaces().subscribe((payload) => {
      const ids = extractNamespaceIds(payload)
      this.namespaces.set(ids)
      const [firstNamespace] = ids
      if (firstNamespace && !this.form.controls.namespaceId.value) {
        this.form.controls.namespaceId.setValue(firstNamespace)
      }
    })
  }

  /** Materialize then run the workflow, driving the form's feedback banners. */
  protected onSubmit(): void {
    if (this.submitting()) return
    if (this.form.invalid) {
      this.form.markAllAsTouched()
      return
    }

    const { workflowType, namespaceId, controllerRequest, repoRoot, ticket } = this.form.getRawValue()
    const trimmedTicket = ticket.trim()
    const trimmedRepoRoot = repoRoot.trim()
    const workflowId = `wf-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`

    const startPayload: StartWorkflowRequest = {
      workflow: {
        workflowId,
        workflowType,
        title: `Run ${workflowType}`,
        ...(trimmedTicket ? { ticket: trimmedTicket } : {}),
      },
      execution: {
        namespaceId,
        runtimeId: 'factory-dashboard',
        kind: 'agentos',
        agentId: 'factory-agent',
      },
      controllerRequest,
    }

    const runPayload: RunWorkflowRequest = {
      namespaceId,
      ...(trimmedTicket ? { ticket: trimmedTicket } : {}),
      ...(trimmedRepoRoot ? { repoRoot: trimmedRepoRoot } : {}),
    }

    this.submitting.set(true)
    this.successMessage.set(null)
    this.errorMessage.set(null)

    this.api
      .startWorkflow(workflowId, startPayload, namespaceId)
      .pipe(
        catchError((error: FactoryApiError) => {
          // An already-materialized instance is fine: proceed to the /run phase.
          if (isWorkflowConflict(error)) return of(null)
          return throwError(() => error)
        }),
        switchMap(() => this.api.runWorkflow(workflowId, runPayload, namespaceId))
      )
      .subscribe({
        next: (response) => {
          this.submitting.set(false)
          const submissionId = response?.submissionId
          this.successMessage.set(submissionId ? `Lancement accepté (id: ${submissionId})` : 'Lancement accepté.')
          this.store.refresh()
          void this.router.navigate(['/sessions', workflowId])
        },
        error: (error: FactoryApiError) => {
          this.submitting.set(false)
          this.errorMessage.set(formatLaunchError(error))
        },
      })
  }
}
