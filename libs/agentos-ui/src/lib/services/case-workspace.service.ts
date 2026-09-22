import { inject, Injectable, signal } from '@angular/core'
import { CaseWorkspaceControllerService, CaseWorkspaceView, WorkspaceRetryRequest } from '@whoz-oss/agentos-api-client'
import { catchError, EMPTY, Observable, switchMap, tap, timer } from 'rxjs'

export type WorkspaceView = CaseWorkspaceView
export type WorkspaceAction = 'refresh' | 'retry' | 'recover'

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
      action === 'refresh'
        ? this.api.refreshCaseWorkspace(caseId)
        : action === 'recover'
          ? this.api.recoverCaseWorkspace(caseId)
          : this.api.retryCaseWorkspace(caseId, body)
    return request.pipe(
      tap((view) => {
        if (view.rootCaseId) this.byRoot.update((map) => ({ ...map, [view.rootCaseId!]: view }))
      })
    )
  }
}

export function pullRequestIndicator(view?: WorkspaceView): { icon: string; label: string; color: string } | null {
  if (!view?.equipped || !view.branchName || !view.git || view.git.error) return null
  let state: { icon: string; label: string; color: string }
  switch (view.git.prState) {
    case 'DRAFT':
      state = { icon: 'edit_note', label: 'Draft', color: 'var(--color-text-secondary, currentColor)' }
      break
    case 'OPEN':
      state = { icon: 'call_split', label: 'Open', color: 'var(--color-success, #218739)' }
      break
    case 'MERGED':
      state = { icon: 'merge', label: 'Merged', color: 'var(--color-info, #8957e5)' }
      break
    case 'CLOSED_UNMERGED':
      state = { icon: 'cancel', label: 'Closed', color: 'var(--color-error, #d34444)' }
      break
    default:
      return null
  }
  return {
    ...state,
    label: `PR${view.git.prNumber ? ` #${view.git.prNumber}` : ''} — ${state.label}`,
  }
}
