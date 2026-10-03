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

  describe('getInteractions', () => {
    it('unwraps the { data } envelope and sends state=all by default', () => {
      let result: unknown[] | undefined
      service.getInteractions('wf-1').subscribe((items) => (result = items))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/interactions')
      expect(request.request.method).toBe('GET')
      expect(request.request.params.get('state')).toBe('all')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({ data: [{ interactionId: 'i-1' }] })

      expect(result).toEqual([{ interactionId: 'i-1' }])
    })

    it('unwraps a nested { data: { items: [...] } } payload and forwards namespace/state', () => {
      let result: unknown[] | undefined
      service.getInteractions('wf-1', '  ns-42  ', 'open').subscribe((items) => (result = items))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/interactions')
      expect(request.request.params.get('state')).toBe('open')
      expect(request.request.params.get('namespaceId')).toBe('ns-42')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-42')
      request.flush({ data: { items: [{ interactionId: 'i-2' }] } })

      expect(result).toEqual([{ interactionId: 'i-2' }])
    })

    it('returns raw arrays and empty arrays for malformed payloads', () => {
      let raw: unknown[] | undefined
      service.getInteractions('wf-1').subscribe((items) => (raw = items))
      http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/interactions').flush([{ interactionId: 'i-3' }])
      expect(raw).toEqual([{ interactionId: 'i-3' }])

      let empty: unknown[] | undefined
      service.getInteractions('wf-1').subscribe((items) => (empty = items))
      http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/interactions').flush({ data: { foo: 'bar' } })
      expect(empty).toEqual([])
    })

    it('encodes the workflow id in the path', () => {
      service.getInteractions('wf/1 2').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf%2F1%202/interactions')
      request.flush({ data: [] })
    })
  })

  describe('getAttempts', () => {
    it('unwraps the { data: [...] } envelope', () => {
      let result: unknown[] | undefined
      service.getAttempts('wf-1').subscribe((items) => (result = items))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/attempts')
      expect(request.request.method).toBe('GET')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      expect(request.request.params.has('namespaceId')).toBe(false)
      request.flush({ data: [{ attemptId: 'a-1', stepId: 'build', attemptNumber: 1 }] })

      expect(result).toEqual([{ attemptId: 'a-1', stepId: 'build', attemptNumber: 1 }])
    })

    it('unwraps a nested { data: { items: [...] } } payload and forwards the namespace', () => {
      let result: unknown[] | undefined
      service.getAttempts('wf-1', '  ns-42  ').subscribe((items) => (result = items))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/attempts')
      expect(request.request.params.get('namespaceId')).toBe('ns-42')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-42')
      request.flush({ data: { items: [{ attemptId: 'a-2' }] } })

      expect(result).toEqual([{ attemptId: 'a-2' }])
    })

    it('returns raw arrays and empty arrays for malformed payloads', () => {
      let raw: unknown[] | undefined
      service.getAttempts('wf-1').subscribe((items) => (raw = items))
      http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/attempts').flush([{ attemptId: 'a-3' }])
      expect(raw).toEqual([{ attemptId: 'a-3' }])

      let empty: unknown[] | undefined
      service.getAttempts('wf-1').subscribe((items) => (empty = items))
      http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/attempts').flush({ data: { foo: 'bar' } })
      expect(empty).toEqual([])
    })

    it('encodes the workflow id in the path', () => {
      service.getAttempts('wf/1 2').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf%2F1%202/attempts')
      request.flush({ data: [] })
    })

    it('normalizes HTTP failures into a structured error', () => {
      let error: FactoryApiError | undefined
      service.getAttempts('missing').subscribe({ error: (e: FactoryApiError) => (error = e) })

      http
        .expectOne((r) => r.url === '/api/factory/workflows/missing/attempts')
        .flush({ error: { code: 'WORKFLOW_NOT_FOUND' } }, { status: 404, statusText: 'Not Found' })

      expect(error).toEqual(expect.objectContaining({ code: 'WORKFLOW_NOT_FOUND', status: 404 }))
    })
  })

  describe('getActions', () => {
    it('unwraps { data: { allowedActions, blockers } } and forwards the namespace', () => {
      let result: { allowedActions: unknown[]; blockers: unknown[] } | undefined
      service.getActions('wf-1', '  ns-42  ').subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/actions')
      expect(request.request.method).toBe('GET')
      expect(request.request.params.get('namespaceId')).toBe('ns-42')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-42')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({
        data: {
          allowedActions: [{ type: 'retry', stepId: 'build', expectedRevision: 7 }],
          blockers: [{ code: 'STEP_BLOCKED', stepId: 'build', message: 'Step build blocked' }],
        },
      })

      expect(result).toEqual({
        allowedActions: [{ type: 'retry', stepId: 'build', expectedRevision: 7 }],
        blockers: [{ code: 'STEP_BLOCKED', stepId: 'build', message: 'Step build blocked' }],
      })
    })

    it('normalizes malformed payloads to empty arrays', () => {
      let result: { allowedActions: unknown[]; blockers: unknown[] } | undefined
      service.getActions('wf-1').subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/actions')
      expect(request.request.params.has('namespaceId')).toBe(false)
      expect(request.request.headers.has('X-Namespace-Id')).toBe(false)
      request.flush({ data: { foo: 'bar' } })

      expect(result).toEqual({ allowedActions: [], blockers: [] })
    })

    it('encodes the workflow id and normalizes failures', () => {
      let error: FactoryApiError | undefined
      service.getActions('wf/1 2').subscribe({ error: (e: FactoryApiError) => (error = e) })

      http
        .expectOne((r) => r.url === '/api/factory/workflows/wf%2F1%202/actions')
        .flush({ error: { code: 'WORKFLOW_NOT_FOUND' } }, { status: 404, statusText: 'Not Found' })

      expect(error).toEqual(expect.objectContaining({ code: 'WORKFLOW_NOT_FOUND', status: 404 }))
    })
  })

  describe('governed action POSTs', () => {
    it('replyInteraction posts to the reply route with the payload, namespace and correlation id', () => {
      let result: unknown
      service
        .replyInteraction('wf-1', 'i/1', { actionId: 'approve', text: 'ok', expectedRevision: 3 }, 'ns-1')
        .subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/interactions/i%2F1/reply')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({ actionId: 'approve', text: 'ok', expectedRevision: 3 })
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-1')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({ data: { interactionId: 'i/1' } })

      expect(result).toEqual({ interactionId: 'i/1' })
    })

    it('openRetry posts to /retries', () => {
      let result: unknown
      service
        .openRetry('wf-1', { stepId: 'build', expectedRevision: 7, reasonCode: 'human_retry' })
        .subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/retries')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({ stepId: 'build', expectedRevision: 7, reasonCode: 'human_retry' })
      expect(request.request.params.has('namespaceId')).toBe(false)
      request.flush({ data: { ok: true } })

      expect(result).toEqual({ ok: true })
    })

    it('cancelAttempt posts to /attempts/:id/cancel and encodes ids', () => {
      service.cancelAttempt('wf/1', 'a/2', { expectedRevision: 4, reason: 'operator' }, 'ns-1').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf%2F1/attempts/a%2F2/cancel')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({ expectedRevision: 4, reason: 'operator' })
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      request.flush({ data: { status: 'interrupted' } })
    })

    it('continueCost posts an optional threshold to /cost/continue', () => {
      service.continueCost('wf-1', { expectedThreshold: 12 }, 'ns-1').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/cost/continue')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({ expectedThreshold: 12 })
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      request.flush({ data: { ok: true } })
    })

    it('continueCost omits the body when no payload is given', () => {
      service.continueCost('wf-1').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/cost/continue')
      expect(request.request.body).toEqual({})
      request.flush({ data: { ok: true } })
    })

    it('stopCost posts an empty body to /cost/stop', () => {
      service.stopCost('wf-1', 'ns-1').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/cost/stop')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({})
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-1')
      request.flush({ data: { ok: true } })
    })

    it('normalizes HTTP failures for a POST', () => {
      let error: FactoryApiError | undefined
      service.stopCost('missing').subscribe({ error: (e: FactoryApiError) => (error = e) })

      http
        .expectOne((r) => r.url === '/api/factory/workflows/missing/cost/stop')
        .flush({ error: { code: 'SERVICE_UNAVAILABLE' } }, { status: 503, statusText: 'Service Unavailable' })

      expect(error).toEqual(expect.objectContaining({ code: 'SERVICE_UNAVAILABLE', status: 503 }))
    })
  })
})
