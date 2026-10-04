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

  describe('workflow lifecycle endpoints (remove, restore, purge)', () => {
    it('removeWorkflow sends a DELETE to the encoded route with namespace and correlation id', () => {
      let result: unknown
      service.removeWorkflow('wf/1 2', 'ns/1').subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf%2F1%202')
      expect(request.request.method).toBe('DELETE')
      expect(request.request.params.get('namespaceId')).toBe('ns/1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns/1')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({ data: { status: 'removed', workflowId: 'wf/1 2' } })

      expect(result).toEqual({ status: 'removed', workflowId: 'wf/1 2' })
    })

    it('removeWorkflow omits the namespace when none is given', () => {
      service.removeWorkflow('wf-1').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1')
      expect(request.request.method).toBe('DELETE')
      expect(request.request.params.has('namespaceId')).toBe(false)
      expect(request.request.headers.has('X-Namespace-Id')).toBe(false)
      request.flush({ data: { status: 'removed' } })
    })

    it('removeWorkflow normalizes HTTP failures into a structured error', () => {
      let error: FactoryApiError | undefined
      service.removeWorkflow('missing').subscribe({ error: (e: FactoryApiError) => (error = e) })

      http
        .expectOne((r) => r.url === '/api/factory/workflows/missing')
        .flush({ error: { code: 'WORKFLOW_NOT_FOUND' } }, { status: 404, statusText: 'Not Found' })

      expect(error).toEqual(expect.objectContaining({ code: 'WORKFLOW_NOT_FOUND', status: 404 }))
    })

    it('restoreWorkflow posts an empty body to the encoded /restore route', () => {
      let result: unknown
      service.restoreWorkflow('wf/1 2', 'ns/1').subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf%2F1%202/restore')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({})
      expect(request.request.params.get('namespaceId')).toBe('ns/1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns/1')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({ data: { status: 'active' } })

      expect(result).toEqual({ status: 'active' })
    })

    it('restoreWorkflow omits the namespace and normalizes failures', () => {
      let error: FactoryApiError | undefined
      service.restoreWorkflow('wf-1').subscribe({ error: (e: FactoryApiError) => (error = e) })

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/restore')
      expect(request.request.method).toBe('POST')
      expect(request.request.params.has('namespaceId')).toBe(false)
      request.flush({ error: { code: 'WORKFLOW_NOT_REMOVED' } }, { status: 409, statusText: 'Conflict' })

      expect(error).toEqual(expect.objectContaining({ code: 'WORKFLOW_NOT_REMOVED', status: 409 }))
    })

    it('purgeWorkflow posts an empty body to the encoded /purge route', () => {
      let result: unknown
      service.purgeWorkflow('wf/1 2', 'ns/1').subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf%2F1%202/purge')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({})
      expect(request.request.params.get('namespaceId')).toBe('ns/1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns/1')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({ data: { status: 'purged' } })

      expect(result).toEqual({ status: 'purged' })
    })

    it('purgeWorkflow omits the namespace and normalizes failures', () => {
      let error: FactoryApiError | undefined
      service.purgeWorkflow('wf-1').subscribe({ error: (e: FactoryApiError) => (error = e) })

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/purge')
      expect(request.request.params.has('namespaceId')).toBe(false)
      request.flush({ error: { code: 'FORBIDDEN_ADMIN_REQUIRED' } }, { status: 403, statusText: 'Forbidden' })

      expect(error).toEqual(expect.objectContaining({ code: 'FORBIDDEN_ADMIN_REQUIRED', status: 403 }))
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

  describe('admin artifact operations', () => {
    it('runGarbageCollection posts to /admin/artifacts/gc with the dryRun body and a correlation id', () => {
      let result: unknown
      service.runGarbageCollection({ dryRun: true }).subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/admin/artifacts/gc')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({ dryRun: true })
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      expect(request.request.params.has('namespaceId')).toBe(false)
      request.flush({ data: { scannedMetadataRows: 3, reclaimedStagingKeys: ['a'] } })

      expect(result).toEqual({ scannedMetadataRows: 3, reclaimedStagingKeys: ['a'] })
    })

    it('runGarbageCollection posts an empty body by default and forwards the namespace', () => {
      service.runGarbageCollection(undefined, '  ns-42  ').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/admin/artifacts/gc')
      expect(request.request.body).toEqual({})
      expect(request.request.params.get('namespaceId')).toBe('ns-42')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-42')
      request.flush({ data: {} })
    })

    it('purgeArtifact posts to the encoded purge route with the reason body and namespace', () => {
      let result: unknown
      service.purgeArtifact('art/1 2', { reason: 'expired' }, 'ns-1').subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/admin/artifacts/art%2F1%202/purge')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({ reason: 'expired' })
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-1')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({ data: { status: 'purged', artifactId: 'art/1 2' } })

      expect(result).toEqual({ status: 'purged', artifactId: 'art/1 2' })
    })

    it('purgeArtifact omits the reason when none is provided', () => {
      service.purgeArtifact('art-1').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/admin/artifacts/art-1/purge')
      expect(request.request.body).toEqual({})
      request.flush({ data: { status: 'purged' } })
    })

    it('setLegalHold posts the legal-hold body to the encoded route', () => {
      service.setLegalHold('art-1', { legalHold: false, reason: 'audit' }).subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/admin/artifacts/art-1/legal-hold')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({ legalHold: false, reason: 'audit' })
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({ data: { legalHold: false, id: 'art-1' } })
    })

    it('normalizes a 403 FORBIDDEN_ADMIN_REQUIRED failure', () => {
      let error: FactoryApiError | undefined
      service.runGarbageCollection().subscribe({ error: (e: FactoryApiError) => (error = e) })

      http
        .expectOne((r) => r.url === '/api/factory/admin/artifacts/gc')
        .flush(
          { error: { code: 'FORBIDDEN_ADMIN_REQUIRED', message: 'droits requis' } },
          { status: 403, statusText: 'Forbidden' }
        )

      expect(error).toEqual(
        expect.objectContaining({ code: 'FORBIDDEN_ADMIN_REQUIRED', message: 'droits requis', status: 403 })
      )
    })
  })

  describe('workflow definitions', () => {
    it('getWorkflowDefinitions issues a GET and forwards the namespace', () => {
      let result: unknown
      service.getWorkflowDefinitions('ns-1').subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflow-definitions')
      expect(request.request.method).toBe('GET')
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-1')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({ data: { items: [{ workflowType: 't', version: 'v1' }] } })

      expect(result).toEqual({ items: [{ workflowType: 't', version: 'v1' }] })
    })

    it('uploadWorkflowDefinition posts multipart FormData without an explicit Content-Type', () => {
      const file = new File(['{}'], 'definition.json', { type: 'application/json' })
      service.uploadWorkflowDefinition(file, 'ns-1').subscribe()

      const request = http.expectOne((r) => r.url === '/api/factory/workflow-definitions/upload')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toBeInstanceOf(FormData)
      const data = request.request.body as FormData
      expect(data.get('file')).toBeInstanceOf(File)
      expect((data.get('file') as File).name).toBe('definition.json')
      expect(request.request.headers.has('Content-Type')).toBe(false)
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-1')
      request.flush({ data: { workflowType: 't', version: 'v1' } })
    })

    it('deleteWorkflowDefinition issues a DELETE on the encoded type/version route', () => {
      let result: unknown
      service.deleteWorkflowDefinition('my/type', 'v 1', 'ns-1').subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflow-definitions/my%2Ftype/v%201')
      expect(request.request.method).toBe('DELETE')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-1')
      request.flush({ data: { status: 'deleted' } })

      expect(result).toEqual({ status: 'deleted' })
    })

    it('normalizes a 403 FORBIDDEN_ADMIN_REQUIRED failure for definitions', () => {
      let error: FactoryApiError | undefined
      service.deleteWorkflowDefinition('t', 'v1').subscribe({ error: (e: FactoryApiError) => (error = e) })

      http
        .expectOne((r) => r.url === '/api/factory/workflow-definitions/t/v1')
        .flush(
          { error: { code: 'FORBIDDEN_ADMIN_REQUIRED', message: 'admin only' } },
          { status: 403, statusText: 'Forbidden' }
        )

      expect(error).toEqual(
        expect.objectContaining({ code: 'FORBIDDEN_ADMIN_REQUIRED', message: 'admin only', status: 403 })
      )
    })
  })

  describe('workflow launch', () => {
    const startPayload = {
      workflow: { workflowId: 'wf-1', workflowType: 'adw_simple_sdlc', title: 'Run adw_simple_sdlc', ticket: 'ABC-1' },
      execution: { namespaceId: 'ns-1', runtimeId: 'factory-dashboard', kind: 'agentos', agentId: 'factory-agent' },
      controllerRequest: 'Please build the feature',
    }

    it('startWorkflow posts the payload to the encoded /start route with namespace and correlation id', () => {
      let result: unknown
      service.startWorkflow('wf/1 2', startPayload, 'ns-1').subscribe((r) => (result = r))

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf%2F1%202/start')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual(startPayload)
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-1')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush({ data: { workflowId: 'wf/1 2', status: 'materialized' } })

      expect(result).toEqual({ workflowId: 'wf/1 2', status: 'materialized' })
    })

    it('startWorkflow normalizes an identity conflict into a structured error', () => {
      let error: FactoryApiError | undefined
      service.startWorkflow('wf-1', startPayload).subscribe({ error: (e: FactoryApiError) => (error = e) })

      http
        .expectOne((r) => r.url === '/api/factory/workflows/wf-1/start')
        .flush(
          { error: { code: 'WORKFLOW_IDENTITY_CONFLICT', message: 'already exists' } },
          { status: 409, statusText: 'Conflict' }
        )

      expect(error).toEqual(expect.objectContaining({ code: 'WORKFLOW_IDENTITY_CONFLICT', status: 409 }))
    })

    it('runWorkflow posts the run payload and unwraps the 202 accepted envelope', () => {
      let result: { status?: string; submissionId?: string } | undefined
      service
        .runWorkflow('wf-1', { namespaceId: 'ns-1', ticket: 'ABC-1', repoRoot: '/repo' }, 'ns-1')
        .subscribe((r) => {
          result = r
        })

      const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/run')
      expect(request.request.method).toBe('POST')
      expect(request.request.body).toEqual({ namespaceId: 'ns-1', ticket: 'ABC-1', repoRoot: '/repo' })
      expect(request.request.params.get('namespaceId')).toBe('ns-1')
      expect(request.request.headers.get('X-Namespace-Id')).toBe('ns-1')
      expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
      request.flush(
        { data: { status: 'accepted', submissionId: 'sub-1', workflowId: 'wf-1' } },
        { status: 202, statusText: 'Accepted' }
      )

      expect(result).toEqual({ status: 'accepted', submissionId: 'sub-1', workflowId: 'wf-1' })
    })

    it('getNamespaces unwraps a raw array and a { items } payload', () => {
      let raw: unknown[] | undefined
      service.getNamespaces().subscribe((items) => (raw = items))
      http.expectOne((r) => r.url === '/api/namespaces').flush([{ namespaceId: 'ns-1' }])
      expect(raw).toEqual([{ namespaceId: 'ns-1' }])

      let wrapped: unknown[] | undefined
      service.getNamespaces().subscribe((items) => (wrapped = items))
      http.expectOne((r) => r.url === '/api/namespaces').flush({ data: { items: [{ id: 'ns-2' }] } })
      expect(wrapped).toEqual([{ id: 'ns-2' }])
    })

    it('getNamespaces degrades gracefully to an empty array on error', () => {
      let result: unknown[] | undefined
      service.getNamespaces().subscribe((items) => (result = items))

      http
        .expectOne((r) => r.url === '/api/namespaces')
        .flush({ error: { code: 'NOT_FOUND' } }, { status: 404, statusText: 'Not Found' })

      expect(result).toEqual([])
    })
  })
})
