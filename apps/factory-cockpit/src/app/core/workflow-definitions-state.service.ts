import { Injectable, inject, signal } from '@angular/core'
import { Observable, catchError, finalize, map, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService, WorkflowDefinition } from './factory-api.service'

/** Normalize supported registry payload envelopes without fabricating entries. */
export function extractWorkflowDefinitions(payload: unknown): WorkflowDefinition[] {
  if (Array.isArray(payload)) return payload as WorkflowDefinition[]
  const object = (typeof payload === 'object' && payload !== null ? payload : {}) as {
    items?: unknown
    data?: unknown
  }
  if (Array.isArray(object.items)) return object.items as WorkflowDefinition[]
  if (Array.isArray(object.data)) return object.data as WorkflowDefinition[]
  const nested = (typeof object.data === 'object' && object.data !== null ? object.data : {}) as { items?: unknown }
  return Array.isArray(nested.items) ? (nested.items as WorkflowDefinition[]) : []
}

/** Page-scoped coordination for the workflow-definition registry. */
@Injectable()
export class WorkflowDefinitionsStateService {
  private readonly api = inject(FactoryApiService)

  readonly definitions = signal<WorkflowDefinition[]>([])
  readonly loading = signal(false)
  readonly error = signal<FactoryApiError | null>(null)
  readonly uploading = signal(false)
  readonly deletingKey = signal<string | null>(null)

  load(): void {
    this.loading.set(true)
    this.error.set(null)
    this.api
      .getWorkflowDefinitions()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (payload) => this.definitions.set(extractWorkflowDefinitions(payload)),
        error: (error: FactoryApiError) => {
          this.definitions.set([])
          this.error.set(error)
        },
      })
  }

  upload(file: File): Observable<void> {
    this.uploading.set(true)
    this.error.set(null)
    return this.api.uploadWorkflowDefinition(file).pipe(
      map(() => undefined),
      catchError((error: FactoryApiError) => {
        this.error.set(error)
        return throwError(() => error)
      }),
      finalize(() => this.uploading.set(false))
    )
  }

  delete(workflowType: string, version: string): Observable<void> {
    const key = `${workflowType}@${version}`
    this.deletingKey.set(key)
    this.error.set(null)
    return this.api.deleteWorkflowDefinition(workflowType, version).pipe(
      map(() => undefined),
      catchError((error: FactoryApiError) => {
        this.error.set(error)
        return throwError(() => error)
      }),
      finalize(() => this.deletingKey.set(null))
    )
  }
}
