import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { CaseWorkspaceControllerService, CaseWorkspaceViewStatusEnum } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { CaseWorkspaceService, pullRequestIndicator, WorkspaceState, WorkspaceView } from './case-workspace.service'

describe('case pull request indicator', () => {
  const workspace: WorkspaceView = {
    equipped: true,
    status: CaseWorkspaceViewStatusEnum.READY,
    branchName: 'agent/chosen',
  }
  it('shows no icon without a known associated PR, even for a pushed or modified branch', () => {
    expect(pullRequestIndicator()).toBeNull()
    expect(pullRequestIndicator(workspace)).toBeNull()
    for (const branchState of ['DETACHED', 'LOCAL_ONLY', 'PUSHED', 'UNPUSHED_COMMITS']) {
      for (const prState of ['NONE', 'UNKNOWN']) {
        expect(
          pullRequestIndicator({ ...workspace, git: { branchState, prState, dirty: true, unpushedCommits: 2 } })
        ).toBeNull()
      }
    }
  })
  it('distinguishes draft, open, merged and closed PRs', () => {
    const icon = (prState: string) =>
      pullRequestIndicator({ ...workspace, git: { branchState: 'PUSHED', prState, prNumber: 42 } })
    expect(new Set(['DRAFT', 'OPEN', 'MERGED', 'CLOSED_UNMERGED'].map((s) => icon(s)?.icon)).size).toBe(4)
    expect(icon('MERGED')?.label).toBe('PR #42 — Merged')
  })
  it('keeps branch modifications out of the PR indicator', () => {
    const git = { branchState: 'PUSHED', prState: 'OPEN', prNumber: 42 }
    expect(
      pullRequestIndicator({
        ...workspace,
        git: { ...git, branchState: 'UNPUSHED_COMMITS', dirty: true, unpushedCommits: 2 },
      })
    ).toEqual(pullRequestIndicator({ ...workspace, git }))
  })
  it('hides unavailable or no longer associated PR status', () => {
    const git = { branchState: 'PUSHED', prState: 'OPEN' }
    expect(pullRequestIndicator({ ...workspace, git: { ...git, error: 'HTTP 401' } })).toBeNull()
    expect(pullRequestIndicator({ ...workspace, branchName: undefined, git })).toBeNull()
    expect(pullRequestIndicator({ ...workspace, equipped: false, git })).toBeNull()
  })
})

describe('case workspaces on an instance without Git workspaces', () => {
  const notFound = () => throwError(() => ({ status: 404 }))
  function serviceWith(api: object): CaseWorkspaceService {
    TestBed.configureTestingModule({ providers: [{ provide: CaseWorkspaceControllerService, useValue: api }] })
    return TestBed.inject(CaseWorkspaceService)
  }

  it('stops polling the namespace after a 404', fakeAsync(() => {
    const listCaseWorkspace = jest.fn().mockImplementation(notFound)
    const subscription = serviceWith({ listCaseWorkspace }).watchNamespace('ns-1').subscribe()
    tick(30_000)
    expect(listCaseWorkspace).toHaveBeenCalledTimes(1)
    subscription.unsubscribe()
  }))

  it('reports a 404 once for a case, then stops polling', fakeAsync(() => {
    const getCaseWorkspace = jest.fn().mockImplementation(notFound)
    const states: WorkspaceState[] = []
    const subscription = serviceWith({ getCaseWorkspace })
      .watch('case-1')
      .subscribe((state) => states.push(state))
    tick(30_000)
    expect(getCaseWorkspace).toHaveBeenCalledTimes(1)
    expect(states).toEqual([{ view: null, errorStatus: 404 }])
    subscription.unsubscribe()
  }))

  it('asks no other namespace or case once the instance answered 404', fakeAsync(() => {
    const listCaseWorkspace = jest.fn().mockImplementation(notFound)
    const getCaseWorkspace = jest.fn().mockImplementation(notFound)
    const service = serviceWith({ listCaseWorkspace, getCaseWorkspace })
    service.watchNamespace('ns-1').subscribe()
    tick()
    const states: WorkspaceState[] = []
    service.watchNamespace('ns-2').subscribe()
    service.watch('case-2').subscribe((state) => states.push(state))
    tick(30_000)
    expect(listCaseWorkspace).toHaveBeenCalledTimes(1)
    expect(getCaseWorkspace).not.toHaveBeenCalled()
    expect(states).toEqual([{ view: null, errorStatus: 404 }])
  }))

  it('keeps asking about other cases after a 404 for a single case', fakeAsync(() => {
    const getCaseWorkspace = jest.fn().mockImplementation(notFound)
    const service = serviceWith({ listCaseWorkspace: jest.fn().mockReturnValue(of([])), getCaseWorkspace })
    service.watch('missing').subscribe()
    tick()
    service.watch('other').subscribe()
    tick()
    expect(getCaseWorkspace).toHaveBeenCalledTimes(2)
  }))
})
