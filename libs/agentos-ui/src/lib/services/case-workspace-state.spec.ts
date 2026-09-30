import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { Configuration } from '@whoz-oss/agentos-api-client'
import { CaseWorkspaceService, WorkspaceState, WorkspaceView } from './case-workspace.service'

const root: WorkspaceView = { equipped: true, rootCaseId: 'root', status: 'PREPARING' }

describe('shared workspace state', () => {
  let http: HttpTestingController
  let service: CaseWorkspaceService
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Configuration, useValue: new Configuration({ basePath: '/agentos-api' }) },
      ],
    })
    http = TestBed.inject(HttpTestingController)
    service = TestBed.inject(CaseWorkspaceService)
  })
  afterEach(() => {
    http.verify()
    TestBed.resetTestingModule()
  })
  const namespaceUrl = '/agentos-api/api/namespaces/ns/workspaces'
  const caseUrl = (id: string) => `/agentos-api/api/cases/${id}/workspace`

  it('shares the authorized namespace root with header and preparation, without duplicate or overlapping requests', fakeAsync(() => {
    const namespace = service.watchNamespace('ns').subscribe()
    const headerStates: WorkspaceState[] = []
    const fileStates: WorkspaceState[] = []
    const header = service.watch('root').subscribe((view) => headerStates.push(view))
    const files = service.watch('root').subscribe((view) => fileStates.push(view))
    tick(0)
    http.expectOne(namespaceUrl).flush([root])
    http.expectNone(caseUrl('root'))
    expect(headerStates).toEqual([{ view: root }])
    expect(fileStates).toEqual(headerStates)
    tick(10000)
    const pending = http.expectOne(namespaceUrl)
    tick(20000)
    http.expectNone(namespaceUrl)
    expect(headerStates).toHaveLength(1)
    pending.flush([{ ...root, status: 'READY' }])
    expect(headerStates.map(({ view }) => view?.status)).toEqual(['PREPARING', 'READY'])
    expect(service.byRoot()['root']?.status).toBe('READY')
    header.unsubscribe()
    files.unsubscribe()
    namespace.unsubscribe()
    tick(20000)
    http.expectNone(namespaceUrl)
    expect(service.byRoot()).toEqual({})
  }))

  it('shares fallback reads and aborts them when the final consumer leaves', fakeAsync(() => {
    const first = service.watch('child').subscribe()
    const second = service.watch('child').subscribe()
    tick(0)
    const request = http.expectOne(caseUrl('child'))
    first.unsubscribe()
    expect(request.cancelled).toBe(false)
    second.unsubscribe()
    expect(request.cancelled).toBe(true)
    tick(20000)
    http.expectNone(caseUrl('child'))
    const other = service.watch('other').subscribe()
    tick(0)
    const switched = http.expectOne(caseUrl('other'))
    other.unsubscribe()
    expect(switched.cancelled).toBe(true)
  }))

  it('clears revoked root data and can recover on a subsequent authorized poll', fakeAsync(() => {
    const namespace = service.watchNamespace('ns').subscribe()
    const states: WorkspaceState[] = []
    const header = service.watch('root').subscribe((state) => states.push(state))
    tick(0)
    http.expectOne(namespaceUrl).flush([root])
    tick(10000)
    http.expectOne(namespaceUrl).flush([])
    http.expectOne(caseUrl('root')).flush({}, { status: 403, statusText: 'Forbidden' })
    expect(states.at(-1)).toEqual({ view: null, errorStatus: 403 })
    expect(service.byRoot()).toEqual({})
    tick(10000)
    http.expectOne(namespaceUrl).flush([{ ...root, status: 'READY' }])
    expect(states.at(-1)?.view?.status).toBe('READY')
    header.unsubscribe()
    namespace.unsubscribe()
  }))

  it('cancels the previous namespace request and does not retain its badges on a switch', fakeAsync(() => {
    const previous = service.watchNamespace('ns').subscribe()
    tick(0)
    const request = http.expectOne(namespaceUrl)
    previous.unsubscribe()
    expect(request.cancelled).toBe(true)
    const next = service.watchNamespace('other').subscribe()
    expect(service.byRoot()).toEqual({})
    tick(0)
    http.expectOne('/agentos-api/api/namespaces/other/workspaces').flush([])
    next.unsubscribe()
  }))
})
