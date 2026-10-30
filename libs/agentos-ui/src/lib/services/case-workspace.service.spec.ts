import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import {
  CaseWorkspaceControllerService,
  CaseWorkspaceViewStatusEnum,
  GitWorkspacesConfigurationControllerService,
  GitWorkspaceSummary,
  GitWorkspaceSummaryBranchStateEnum as BranchState,
  GitWorkspaceSummaryPrStateEnum as PrState,
} from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
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
    for (const branchState of [
      BranchState.DETACHED,
      BranchState.LOCAL_ONLY,
      BranchState.PUSHED,
      BranchState.UNPUSHED_COMMITS,
    ]) {
      for (const prState of [PrState.NONE, PrState.UNKNOWN]) {
        expect(
          pullRequestIndicator({ ...workspace, git: { branchState, prState, dirty: true, unpushedCommits: 2 } })
        ).toBeNull()
      }
    }
  })
  it('distinguishes draft, open, merged and closed PRs', () => {
    const icon = (prState: PrState) =>
      pullRequestIndicator({ ...workspace, git: { branchState: BranchState.PUSHED, prState, prNumber: 42 } })
    expect(
      new Set([PrState.DRAFT, PrState.OPEN, PrState.MERGED, PrState.CLOSED_UNMERGED].map((s) => icon(s)?.icon)).size
    ).toBe(4)
    expect(icon(PrState.MERGED)?.label).toBe('PR #42 — Merged')
  })
  it('keeps branch modifications out of the PR indicator', () => {
    const git: GitWorkspaceSummary = { branchState: BranchState.PUSHED, prState: PrState.OPEN, prNumber: 42 }
    expect(
      pullRequestIndicator({
        ...workspace,
        git: { ...git, branchState: BranchState.UNPUSHED_COMMITS, dirty: true, unpushedCommits: 2 },
      })
    ).toEqual(pullRequestIndicator({ ...workspace, git }))
  })
  it('hides unavailable or no longer associated PR status', () => {
    const git: GitWorkspaceSummary = { branchState: BranchState.PUSHED, prState: PrState.OPEN }
    expect(pullRequestIndicator({ ...workspace, git: { ...git, error: 'HTTP 401' } })).toBeNull()
    expect(pullRequestIndicator({ ...workspace, branchName: undefined, git })).toBeNull()
    expect(pullRequestIndicator({ ...workspace, equipped: false, git })).toBeNull()
  })
})

function serviceWithConfiguration(api: object, getGitWorkspacesConfiguration: jest.Mock): CaseWorkspaceService {
  TestBed.configureTestingModule({
    providers: [
      { provide: CaseWorkspaceControllerService, useValue: api },
      { provide: GitWorkspacesConfigurationControllerService, useValue: { getGitWorkspacesConfiguration } },
    ],
  })
  return TestBed.inject(CaseWorkspaceService)
}

describe('case workspaces when the configuration turns them off', () => {
  function disabled(): jest.Mock {
    return jest.fn().mockReturnValue(of({ enabled: false }))
  }

  it('lists nothing and reports every case unavailable without asking the workspace endpoints', fakeAsync(() => {
    const listCaseWorkspace = jest.fn()
    const getCaseWorkspace = jest.fn()
    const service = serviceWithConfiguration({ listCaseWorkspace, getCaseWorkspace }, disabled())
    const lists: WorkspaceView[][] = []
    const states: WorkspaceState[] = []
    service.watchNamespace('ns-1').subscribe((items) => lists.push(items))
    service.watch('case-1').subscribe((state) => states.push(state))
    tick(30_000)
    expect(listCaseWorkspace).not.toHaveBeenCalled()
    expect(getCaseWorkspace).not.toHaveBeenCalled()
    expect(lists).toEqual([[]])
    expect(states).toEqual([{ view: null, errorStatus: 404 }])
    expect(service.unavailable).toBe(true)
  }))

  it('reads the configuration once for every namespace and case', fakeAsync(() => {
    const configuration = disabled()
    const service = serviceWithConfiguration({}, configuration)
    service.watchNamespace('ns-1').subscribe()
    service.watch('case-1').subscribe()
    service.watchNamespace('ns-2').subscribe()
    service.watch('case-2').subscribe()
    tick(30_000)
    expect(configuration).toHaveBeenCalledTimes(1)
  }))

  it('waits for the configuration before asking about a case', fakeAsync(() => {
    const configuration = new Subject<{ enabled: boolean }>()
    const getCaseWorkspace = jest.fn()
    const service = serviceWithConfiguration({ getCaseWorkspace }, jest.fn().mockReturnValue(configuration))
    service.watch('case-1').subscribe()
    tick()
    configuration.next({ enabled: false })
    configuration.complete()
    tick(30_000)
    expect(getCaseWorkspace).not.toHaveBeenCalled()
  }))

  it('polls the workspace endpoints when the configuration turns them on', fakeAsync(() => {
    const listCaseWorkspace = jest.fn().mockReturnValue(of([]))
    const service = serviceWithConfiguration({ listCaseWorkspace }, jest.fn().mockReturnValue(of({ enabled: true })))
    const subscription = service.watchNamespace('ns-1').subscribe()
    tick(20_000)
    expect(listCaseWorkspace).toHaveBeenCalledTimes(3)
    expect(service.unavailable).toBe(false)
    subscription.unsubscribe()
  }))

  it('asks the configuration again after a transient failure, probing the endpoints meanwhile', fakeAsync(() => {
    const configuration = jest
      .fn()
      .mockReturnValueOnce(throwError(() => ({ status: 502 })))
      .mockReturnValue(of({ enabled: false }))
    const listCaseWorkspace = jest.fn().mockReturnValue(of([]))
    const service = serviceWithConfiguration({ listCaseWorkspace }, configuration)
    const first = service.watchNamespace('ns-1').subscribe()
    tick()
    expect(listCaseWorkspace).toHaveBeenCalledTimes(1)
    first.unsubscribe()
    const lists: WorkspaceView[][] = []
    service.watchNamespace('ns-2').subscribe((items) => lists.push(items))
    tick(30_000)
    expect(configuration).toHaveBeenCalledTimes(2)
    expect(listCaseWorkspace).toHaveBeenCalledTimes(1)
    expect(lists).toEqual([[]])
  }))
})

describe('case workspaces on an instance without Git workspaces', () => {
  const notFound = () => throwError(() => ({ status: 404 }))
  // An AgentOS without the configuration endpoint falls back on the 404 its workspace endpoints answer.
  const configuration = jest.fn().mockImplementation(notFound)
  function serviceWith(api: object): CaseWorkspaceService {
    return serviceWithConfiguration(api, configuration)
  }
  beforeEach(() => configuration.mockClear())

  it('asks an AgentOS without the configuration endpoint only once', fakeAsync(() => {
    const service = serviceWith({ listCaseWorkspace: jest.fn().mockReturnValue(of([])) })
    service.watchNamespace('ns-1').subscribe().unsubscribe()
    service.watchNamespace('ns-2').subscribe().unsubscribe()
    tick()
    expect(configuration).toHaveBeenCalledTimes(1)
  }))

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
