import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing'
import { Configuration } from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, of } from 'rxjs'
import { CaseWorkspaceService, WorkspaceState } from '../../services/case-workspace.service'
import { CaseStateService } from '../../services/case-state.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { CaseWorkspaceComponent } from './case-workspace.component'

describe('actionable workspace notice', () => {
  const ready = { equipped: true, rootCaseId: 'root', status: 'READY' }
  let state: BehaviorSubject<WorkspaceState>
  let service: { watch: jest.Mock; act: jest.Mock }
  beforeEach(() => {
    state = new BehaviorSubject<WorkspaceState>({ view: ready })
    service = { watch: jest.fn().mockReturnValue(state), act: jest.fn().mockReturnValue(of(ready)) }
    TestBed.configureTestingModule({
      imports: [CaseWorkspaceComponent],
      providers: [
        { provide: CaseWorkspaceService, useValue: service },
        { provide: CaseStateService, useValue: { reloadCurrent: jest.fn() } },
        { provide: ExchangeStateService, useValue: { refreshCase: jest.fn() } },
      ],
    })
  })
  const setup = () => {
    const fixture = TestBed.createComponent(CaseWorkspaceComponent)
    fixture.componentRef.setInput('caseId', 'root')
    fixture.componentRef.setInput('canWrite', true)
    fixture.detectChanges()
    return fixture
  }
  it('hides the banner for a ready workspace', () => {
    const fixture = setup()
    expect(fixture.nativeElement.querySelector('.workspace')).toBeNull()
  })
  it('shows preparation failures and retry at the root, then removes notices after access loss', () => {
    const fixture = setup()
    state.next({ view: { ...ready, status: 'FAILED', failureReason: 'Setup failed' } })
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('Setup failed')
    fixture.nativeElement.querySelector('button').click()
    expect(service.act).toHaveBeenCalledWith('root', 'retry', { acknowledgeSetupReplay: false })
    state.next({ view: null, errorStatus: 403 })
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelector('.workspace')).toBeNull()
  })
  it('allows preparation retry only for a writable root case', () => {
    const fixture = setup()
    state.next({ view: { ...ready, status: 'FAILED' } })
    fixture.componentRef.setInput('canWrite', false)
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelector('button')).toBeNull()
    fixture.componentInstance.act('retry')
    expect(service.act).not.toHaveBeenCalled()

    fixture.componentRef.setInput('canWrite', true)
    fixture.componentRef.setInput('caseId', 'child')
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelector('button')).toBeNull()
    fixture.componentInstance.act('retry')
    expect(service.act).not.toHaveBeenCalled()

    fixture.componentRef.setInput('caseId', 'root')
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelector('button')).not.toBeNull()
    fixture.componentInstance.act('retry')
    expect(service.act).toHaveBeenCalledTimes(1)
  })
  it('releases the old case stream on case changes and destruction', () => {
    const fixture = setup()
    expect(state.observed).toBe(true)
    const next = new BehaviorSubject<WorkspaceState>({ view: { ...ready, rootCaseId: 'next' } })
    service.watch.mockReturnValue(next)
    fixture.componentRef.setInput('caseId', 'next')
    fixture.detectChanges()
    expect(state.observed).toBe(false)
    expect(next.observed).toBe(true)
    fixture.destroy()
    expect(next.observed).toBe(false)
  })
})

describe('workspace preparation retry and case files', () => {
  const workspaceUrl = '/agentos-api/api/cases/root/workspace'
  const fileUrl = '/agentos-api/api/cases/root/files/directory'
  const namespaceFileUrl = '/agentos-api/api/namespaces/ns/files/directory'
  const listing = { path: '', entries: [], totalEntries: 0, page: 0, pageSize: 200, hasMore: false, capability: 'READ' }
  let http: HttpTestingController
  let files: ExchangeStateService
  let fixture: ComponentFixture<CaseWorkspaceComponent>

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CaseWorkspaceComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Configuration, useValue: new Configuration({ basePath: '/agentos-api' }) },
      ],
    })
    http = TestBed.inject(HttpTestingController)
    files = TestBed.inject(ExchangeStateService)
    fixture = TestBed.createComponent(CaseWorkspaceComponent)
    fixture.componentRef.setInput('caseId', 'root')
    fixture.componentRef.setInput('canWrite', true)
  })

  afterEach(() => {
    fixture.destroy()
    files.clear()
    http.verify()
  })

  const failPreparation = (): void => {
    fixture.detectChanges()
    files.initializeForCase('ns', 'root')
    http.expectOne((request) => request.url === fileUrl).flush({}, { status: 409, statusText: 'Conflict' })
    http.expectOne((request) => request.url === namespaceFileUrl).flush(listing)
    tick(0)
    http.expectOne(workspaceUrl).flush({
      equipped: true,
      rootCaseId: 'root',
      status: 'FAILED',
      failureReason: 'Network unavailable',
    })
    fixture.detectChanges()
    expect(files.caseStatus()).toBe('error')
  }

  it('reloads files after an accepted retry finishes preparing', fakeAsync(() => {
    failPreparation()
    fixture.nativeElement.querySelector('button').click()
    const retry = http.expectOne(`${workspaceUrl}/retry`)
    expect(retry.request.body).toEqual({ acknowledgeSetupReplay: false })
    retry.flush({ equipped: true, rootCaseId: 'root', status: 'REQUESTED' })
    expect(fixture.componentInstance.view()?.status).toBe('REQUESTED')

    http.expectOne((request) => request.url === fileUrl).flush({}, { status: 409, statusText: 'Conflict' })
    expect(files.caseStatus()).toBe('preparing')
    expect(files.canWriteCase()).toBe(false)
    tick(10000)
    http.expectOne(workspaceUrl).flush({ equipped: true, rootCaseId: 'root', status: 'PREPARING' })
    expect(files.caseStatus()).toBe('preparing')
    http.expectNone((request) => request.url === fileUrl)

    tick(10000)
    http.expectOne(workspaceUrl).flush({ equipped: true, rootCaseId: 'root', status: 'READY' })
    const entry = { path: 'README.md', directory: false }
    http
      .expectOne((request) => request.url === fileUrl)
      .flush({
        ...listing,
        entries: [entry],
        totalEntries: 1,
        capability: 'READ_WRITE',
      })
    fixture.detectChanges()
    expect(files.caseStatus()).toBe('ready')
    expect(files.caseFiles()).toEqual([entry])
    expect(files.canWriteCase()).toBe(true)
    expect(fixture.nativeElement.querySelector('.workspace')).toBeNull()
    http.expectNone((request) => request.url === namespaceFileUrl)
    fixture.destroy()
  }))

  it('keeps the files error when preparation retry is rejected', fakeAsync(() => {
    failPreparation()
    fixture.nativeElement.querySelector('button').click()
    http
      .expectOne(`${workspaceUrl}/retry`)
      .flush({ message: 'Retry is unavailable' }, { status: 409, statusText: 'Conflict' })
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Retry is unavailable')
    expect(fixture.componentInstance.busy()).toBe(false)
    expect(files.caseStatus()).toBe('error')
    http.expectNone((request) => request.url === fileUrl)
    fixture.destroy()
  }))

  it('does not refresh the new case when an old retry response arrives during navigation', fakeAsync(() => {
    failPreparation()
    fixture.nativeElement.querySelector('button').click()
    const retry = http.expectOne(`${workspaceUrl}/retry`)

    fixture.componentRef.setInput('caseId', 'next')
    files.initializeForCase('ns', 'next')
    const nextFileUrl = '/agentos-api/api/cases/next/files/directory'
    const entry = { path: 'next.txt', directory: false }
    http.expectOne((request) => request.url === nextFileUrl).flush({ ...listing, entries: [entry], totalEntries: 1 })
    http.expectOne((request) => request.url === namespaceFileUrl).flush(listing)

    // Deliver before the input effect cancels the old subscription to exercise its case-id guard.
    retry.flush({ equipped: true, rootCaseId: 'root', status: 'REQUESTED' })
    expect(files.caseStatus()).toBe('ready')
    expect(files.caseFiles()).toEqual([entry])
    http.expectNone((request) => request.url === fileUrl || request.url === nextFileUrl)

    fixture.detectChanges()
    tick(0)
    http.expectOne('/agentos-api/api/cases/next/workspace').flush({ equipped: false, rootCaseId: 'next' })
    expect(fixture.componentInstance.view()?.rootCaseId).toBe('next')
    expect(fixture.componentInstance.busy()).toBe(false)
    fixture.destroy()
  }))
})
