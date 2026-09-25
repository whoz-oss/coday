import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { Configuration } from '@whoz-oss/agentos-api-client'
import { CaseWorkspaceService, WorkspaceAction } from './case-workspace.service'

describe('Workspace API consumers', () => {
  let http: HttpTestingController
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Configuration, useValue: new Configuration({ basePath: '/agentos-api' }) },
      ],
    })
    http = TestBed.inject(HttpTestingController)
  })
  afterEach(() => {
    http.verify()
    TestBed.resetTestingModule()
  })

  it('decodes workspace JSON through the generated client', () => {
    const view = { equipped: true, rootCaseId: 'root', status: 'READY' }
    const received = jest.fn()
    TestBed.inject(CaseWorkspaceService).get('root').subscribe(received)
    const request = http.expectOne('/agentos-api/api/cases/root/workspace')
    expect(request.request.responseType).toBe('json')
    request.flush(view)
    expect(received).toHaveBeenCalledWith(view)
  })

  it('decodes namespace workspace lists before updating workspace state', fakeAsync(() => {
    const service = TestBed.inject(CaseWorkspaceService)
    const subscription = service.watchNamespace('namespace').subscribe()
    tick(0)
    const request = http.expectOne('/agentos-api/api/namespaces/namespace/workspaces')
    expect(request.request.responseType).toBe('json')
    request.flush([{ equipped: true, rootCaseId: 'root', status: 'READY' }])
    expect(service.byRoot()['root']?.status).toBe('READY')
    subscription.unsubscribe()
  }))

  it.each<WorkspaceAction>(['retry'])('routes the typed %s action through the generated client', (action) => {
    const service = TestBed.inject(CaseWorkspaceService)
    service.act('root', action, { acknowledgeSetupReplay: true }).subscribe()
    const request = http.expectOne(`/agentos-api/api/cases/root/workspace/${action}`)
    expect(request.request.method).toBe('POST')
    expect(request.request.responseType).toBe('json')
    if (action === 'retry') expect(request.request.body).toEqual({ acknowledgeSetupReplay: true })
    request.flush({ equipped: true, rootCaseId: 'root' })
    expect(service.byRoot()['root']?.equipped).toBe(true)
  })
})
