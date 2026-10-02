import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { FactoryApiError, FactoryApiService } from './factory-api.service'

describe('FactoryApiService', () => {
  let service: FactoryApiService
  let http: HttpTestingController

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    })
    service = TestBed.inject(FactoryApiService)
    http = TestBed.inject(HttpTestingController)
  })

  afterEach(() => http.verify())

  it('unwraps the { data } envelope for the workflow list', () => {
    let result: unknown[] | undefined
    service.getWorkflows('active').subscribe((items) => (result = items))

    const request = http.expectOne((r) => r.url === '/api/factory/workflows')
    expect(request.request.method).toBe('GET')
    expect(request.request.params.get('state')).toBe('active')
    request.flush({ data: { namespaceId: '', state: 'active', items: [{ workflowId: 'wf-1' }] } })

    expect(result).toEqual([{ workflowId: 'wf-1' }])
  })

  it('returns raw arrays that are not wrapped in an envelope', () => {
    let result: unknown[] | undefined
    service.getWorkflows('removed').subscribe((items) => (result = items))

    http.expectOne((r) => r.url === '/api/factory/workflows').flush([{ workflowId: 'wf-removed' }])

    expect(result).toEqual([{ workflowId: 'wf-removed' }])
  })

  it('always sends an X-Correlation-Id header', () => {
    service.getWorkflow('wf-1').subscribe()

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1')
    expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
    expect(request.request.headers.has('X-Namespace-Id')).toBe(false)
    expect(request.request.params.has('namespaceId')).toBe(false)
    request.flush({ data: { workflowId: 'wf-1' } })
  })

  it('adds the namespace query param and header only when non-blank', () => {
    service.getTiming('wf-1', '  ns-42  ').subscribe()

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/timing')
    expect(request.request.params.get('namespaceId')).toBe('ns-42')
    expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-42')
    request.flush({ data: { workflowId: 'wf-1' } })
  })

  it('omits the namespace param and header for a blank namespace', () => {
    service.getEvidence('wf-1', '   ').subscribe()

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/evidence')
    expect(request.request.params.has('namespaceId')).toBe(false)
    expect(request.request.headers.has('X-Namespace-Id')).toBe(false)
    request.flush({ data: { items: [] } })
  })

  it('normalizes HTTP failures into a structured error', () => {
    let error: FactoryApiError | undefined
    service.getMetrics('missing').subscribe({ error: (e: FactoryApiError) => (error = e) })

    http
      .expectOne((r) => r.url === '/api/factory/workflows/missing/metrics')
      .flush(
        { error: { code: 'WORKFLOW_NOT_FOUND', message: 'Unknown workflow' } },
        { status: 404, statusText: 'Not Found' }
      )

    expect(error).toEqual(
      expect.objectContaining({ code: 'WORKFLOW_NOT_FOUND', message: 'Unknown workflow', status: 404 })
    )
  })

  it('returns an empty list when the payload carries no items', () => {
    let result: unknown[] | undefined
    service.getWorkflows().subscribe((items) => (result = items))

    http.expectOne((r) => r.url === '/api/factory/workflows').flush({ data: { state: 'active' } })

    expect(result).toEqual([])
  })
})
