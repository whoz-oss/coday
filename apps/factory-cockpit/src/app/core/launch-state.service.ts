import { Injectable, inject, signal } from '@angular/core'
import { Observable, catchError, finalize, forkJoin, of, tap } from 'rxjs'
import {
  CreateWorkflowRunRequest,
  CreateWorkflowRunResponse,
  FactoryApiError,
  FactoryApiService,
  NamespaceOption,
  extractNamespaceOptions,
  generateCorrelationId,
} from './factory-api.service'
import { extractWorkflowDefinitions } from './workflow-definitions-state.service'

/** Page-scoped state and coordination for launching a Factory run. */
@Injectable()
export class LaunchStateService {
  private readonly api = inject(FactoryApiService)

  readonly workflowTypes = signal<string[]>([])
  readonly namespaces = signal<NamespaceOption[]>([])
  readonly loading = signal(false)
  readonly definitionsError = signal<FactoryApiError | null>(null)
  readonly namespacesError = signal<FactoryApiError | null>(null)
  readonly submitting = signal(false)
  readonly submissionError = signal<FactoryApiError | null>(null)

  loadOptions(): void {
    this.loading.set(true)
    this.definitionsError.set(null)
    this.namespacesError.set(null)
    forkJoin({
      definitions: this.api.getWorkflowDefinitions().pipe(
        catchError((error: FactoryApiError) => {
          this.definitionsError.set(error)
          return of([])
        })
      ),
      namespaces: this.api.getNamespaces().pipe(
        catchError((error: FactoryApiError) => {
          this.namespacesError.set(error)
          return of([])
        })
      ),
    })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe(({ definitions, namespaces }) => {
        const types = new Set<string>()
        for (const definition of extractWorkflowDefinitions(definitions)) {
          const type = definition.workflowType
          if (typeof type === 'string' && type.trim()) types.add(type.trim())
        }
        this.workflowTypes.set([...types])
        this.namespaces.set(extractNamespaceOptions(namespaces))
      })
  }

  launch(request: CreateWorkflowRunRequest): Observable<CreateWorkflowRunResponse> {
    this.submitting.set(true)
    this.submissionError.set(null)
    return this.api.createWorkflowRun(request, generateCorrelationId()).pipe(
      tap({ error: (error: FactoryApiError) => this.submissionError.set(error) }),
      finalize(() => this.submitting.set(false))
    )
  }
}
