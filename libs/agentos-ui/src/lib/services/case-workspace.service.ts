import { inject, Injectable, signal } from '@angular/core'
import { CaseWorkspaceControllerService, CaseWorkspaceView, WorkspaceRetryRequest } from '@whoz-oss/agentos-api-client'
import {
  BehaviorSubject,
  catchError,
  defer,
  exhaustMap,
  filter,
  finalize,
  map,
  merge,
  Observable,
  of,
  shareReplay,
  Subject,
  switchMap,
  takeWhile,
  tap,
  timer,
} from 'rxjs'

export type WorkspaceView = CaseWorkspaceView
export type WorkspaceAction = 'refresh' | 'retry'
export interface WorkspaceState {
  view: WorkspaceView | null
  errorStatus?: number
}

const POLL_INTERVAL = 10000
const UNAVAILABLE: WorkspaceState = { view: null, errorStatus: 404 }

/** The workspace endpoints answer 404 on an instance without Git workspaces: stop asking. */
export function workspacesUnavailable(state: WorkspaceState): boolean {
  return state.errorStatus === 404
}

@Injectable({ providedIn: 'root' })
export class CaseWorkspaceService {
  private readonly api = inject(CaseWorkspaceControllerService)
  readonly byRoot = signal<Record<string, WorkspaceView>>({})
  private readonly namespace = new BehaviorSubject<Observable<WorkspaceView[] | null> | null>(null)
  private readonly watchedCases = new Map<string, Observable<WorkspaceState>>()
  private readonly actions = new Subject<{ caseId: string; view: WorkspaceView }>()
  /** Set when the namespace list answers 404: no Git workspaces here, so nothing asks again until reload. */
  private noWorkspaces = false

  /** Whether this instance already answered that it has no Git workspaces. */
  get unavailable(): boolean {
    return this.noWorkspaces
  }

  /** The shell owns the namespace subscription; case consumers reuse its authorized root views. */
  watchNamespace(namespaceId: string): Observable<WorkspaceView[]> {
    const source = timer(0, POLL_INTERVAL).pipe(
      exhaustMap(() =>
        this.api.listCaseWorkspace(namespaceId).pipe(
          map((items): WorkspaceView[] | null | undefined => items),
          catchError((error: { status?: number }) => {
            // The namespace list only answers 404 when Git workspaces are off on this instance.
            if (error.status === 404) this.noWorkspaces = true
            return of(error.status === 404 ? undefined : null)
          })
        )
      ),
      // Without Git workspaces there is nothing to list: publish an empty list once, then stop.
      takeWhile((items) => items !== undefined, true),
      map((items) => (items === undefined ? [] : items)),
      tap((items) =>
        this.byRoot.set(Object.fromEntries((items ?? []).filter((v) => v.rootCaseId).map((v) => [v.rootCaseId!, v])))
      ),
      shareReplay({ bufferSize: 1, refCount: true })
    )
    return defer(() => {
      this.byRoot.set({})
      if (this.noWorkspaces) return of([])
      this.namespace.next(source)
      return source.pipe(
        filter((items): items is WorkspaceView[] => items !== null),
        finalize(() => {
          if (this.namespace.value === source) {
            this.namespace.next(null)
            this.byRoot.set({})
          }
        })
      )
    })
  }

  /** Header, file preparation and the open panel share one stream, including permission failures. */
  watch(caseId: string): Observable<WorkspaceState> {
    if (this.noWorkspaces) return of(UNAVAILABLE)
    const existing = this.watchedCases.get(caseId)
    if (existing) return existing
    const read = () =>
      this.get(caseId).pipe(
        map((view): WorkspaceState => ({ view })),
        catchError((error: { status?: number }) => of({ view: null, errorStatus: error.status ?? 0 }))
      )
    const polling = this.namespace.pipe(
      switchMap((namespace) =>
        namespace
          ? namespace.pipe(
              exhaustMap((items) => {
                // Only roots occur in this endpoint. A child still needs its own authorized READ result.
                const root = items?.find((view) => view.rootCaseId === caseId)
                return root ? of({ view: root }) : read()
              })
            )
          : timer(0, POLL_INTERVAL).pipe(
              exhaustMap(read),
              takeWhile((state) => !workspacesUnavailable(state), true)
            )
      )
    )
    const source = merge(
      polling,
      this.actions.pipe(
        filter((action) => action.caseId === caseId),
        map(({ view }) => ({ view }))
      )
    ).pipe(
      finalize(() => this.watchedCases.delete(caseId)),
      shareReplay({ bufferSize: 1, refCount: true })
    )
    this.watchedCases.set(caseId, source)
    return source
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
      action === 'refresh' ? this.api.refreshCaseWorkspace(caseId) : this.api.retryCaseWorkspace(caseId, body)
    return request.pipe(
      tap((view) => {
        // A child's response must never overwrite the root's state in the sidebar.
        if (view.rootCaseId === caseId) this.byRoot.update((items) => ({ ...items, [caseId]: view }))
        this.actions.next({ caseId, view })
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
