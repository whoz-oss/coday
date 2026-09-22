import { inject, Injectable, signal } from '@angular/core'
import { CaseWorkspaceControllerService, CaseWorkspaceView, WorkspaceRetryRequest } from '@whoz-oss/agentos-api-client'
import { catchError, EMPTY, Observable, switchMap, tap, timer } from 'rxjs'

export type WorkspaceView = CaseWorkspaceView
export type WorkspaceAction = 'retry' | 'recover'

@Injectable({ providedIn: 'root' })
export class CaseWorkspaceService {
  private readonly api = inject(CaseWorkspaceControllerService)
  readonly byRoot = signal<Record<string, WorkspaceView>>({})
  watchNamespace(namespaceId: string): Observable<WorkspaceView[]> {
    this.byRoot.set({})
    return timer(0, 10000).pipe(
      switchMap(() => this.api.listCaseWorkspace(namespaceId).pipe(catchError(() => EMPTY))),
      tap((items) =>
        this.byRoot.set(Object.fromEntries(items.filter((v) => v.rootCaseId).map((v) => [v.rootCaseId!, v])))
      )
    )
  }
  get(caseId: string): Observable<WorkspaceView> {
    return this.api.getCaseWorkspace(caseId)
  }
  act(
    caseId: string,
    action: WorkspaceAction,
    body: WorkspaceRetryRequest = { acknowledgeSetupReplay: false }
  ): Observable<WorkspaceView> {
    const request =
      action === 'recover' ? this.api.recoverCaseWorkspace(caseId) : this.api.retryCaseWorkspace(caseId, body)
    return request.pipe(
      tap((view) => {
        if (view.rootCaseId) this.byRoot.update((map) => ({ ...map, [view.rootCaseId!]: view }))
      })
    )
  }
}
