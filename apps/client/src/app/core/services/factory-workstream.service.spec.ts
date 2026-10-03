import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import {
  FactoryApiError,
  FactoryWorkstreamService,
  normalizeFactoryError,
  unwrapEnvelope,
} from './factory-workstream.service'

describe('FactoryWorkstreamService', () => {
  let service: FactoryWorkstreamService
  let http: HttpTestingController

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    })
    service = TestBed.inject(FactoryWorkstreamService)
    http = TestBed.inject(HttpTestingController)
  })

  afterEach(() => {
    http.verify()
  })

  it('GET getWorkstream unwraps the { data } envelope and updates freshness', () => {
    let result: unknown
    service.getWorkstream('ws-1').subscribe((value) => (result = value))

    const req = http.expectOne('/api/factory/workstreams/ws-1')
    expect(req.request.method).toBe('GET')
    req.flush({
      data: { workstreamId: 'ws-1', organizationId: 'org', name: 'Demo', status: 'active', revision: 4 },
    })

    expect(result).toMatchObject({ workstreamId: 'ws-1', revision: 4 })
    expect(service.lastRevision()).toBe(4)
    expect(service.lastSyncAsOf()).toBeTruthy()
  })

  it('GET getWorkflow passes a bare (un-enveloped) payload through', () => {
    let result: { workflowId?: string } | undefined
    service.getWorkflow('wf-1').subscribe((value) => (result = value as { workflowId?: string }))

    http.expectOne('/api/factory/workflows/wf-1').flush({
      workflowId: 'wf-1',
      state: 'existing',
      revision: 2,
      workflowType: 'feature-delivery',
      status: 'running',
      steps: [{ stepId: 'step-a', status: 'running', revision: 1 }],
      blockers: [],
    })

    expect(result?.workflowId).toBe('wf-1')
    expect(service.lastRevision()).toBe(2)
  })

  it('captures the workstream projection ETag into lastETag', () => {
    service.getWorkstreamProjection('ws-1').subscribe()

    const req = http.expectOne('/api/factory/workstreams/ws-1/projection')
    req.flush({ workstreamId: 'ws-1', workstreamRevision: 'abc123', workflows: [] }, { headers: { ETag: '"abc123"' } })

    expect(service.lastETag()).toBe('abc123')
  })

  it('GET listWorkflows unwraps the items payload', () => {
    let result: { items: unknown[]; nextCursor: string | null } | undefined
    service.listWorkflows('ws-1').subscribe((value) => (result = value))

    const req = http.expectOne((r) => r.url === '/api/factory/workflows')
    expect(req.request.params.get('state')).toBe('active')
    expect(req.request.params.get('workstreamId')).toBe('ws-1')
    req.flush({
      data: {
        items: [{ workflowId: 'wf-1', workflowType: 'bugfix', title: 'Fix', status: 'running', revision: 3 }],
        nextCursor: 'cursor-2',
      },
    })

    expect(result?.items.length).toBe(1)
    expect(result?.nextCursor).toBe('cursor-2')
  })

  it('GET getStepAttempts filters by stepId', () => {
    let result: Array<{ stepId: string }> = []
    service.getStepAttempts('wf-1', 'step-a').subscribe((value) => (result = value))

    const req = http.expectOne('/api/factory/workflows/wf-1/attempts?stepId=step-a')
    req.flush({
      data: [
        { attemptId: 'a1', stepId: 'step-a', status: 'failed', revision: 2 },
        { attemptId: 'a2', stepId: 'step-b', status: 'running', revision: 1 },
      ],
    })

    expect(result.length).toBe(1)
    expect(result[0].stepId).toBe('step-a')
  })

  it('GET getAllowedActions normalizes allowedActions and blockers', () => {
    let result: { allowedActions: Array<{ type?: string }>; blockers: Array<{ code: string }> } | undefined
    service.getAllowedActions('wf-1').subscribe((value) => (result = value))

    http.expectOne('/api/factory/workflows/wf-1/actions').flush({
      data: {
        allowedActions: [
          { type: 'retry', stepId: 'step-a', expectedRevision: 5 },
          { type: 'reply', interactionId: 'int-1', expectedRevision: 6 },
        ],
        blockers: [{ code: 'ATTEMPT_FAILED', stepId: 'step-a', message: 'boom' }],
      },
    })

    expect(result?.allowedActions.length).toBe(2)
    expect(result?.allowedActions[1].type).toBe('reply')
    expect(result?.blockers[0].code).toBe('ATTEMPT_FAILED')
  })

  it('delegates to the mock service (no HTTP) when useMock is enabled', () => {
    service.setUseMock(true)
    let result: { workstreamId?: string } | undefined
    service.getWorkstream('ws-demo').subscribe((value) => (result = value))

    expect(result?.workstreamId).toBe('ws-demo')
    // http.verify() in afterEach asserts no outstanding HTTP requests were made.
  })

  it('POST requestAgentRetry sends the bounded body and unwraps the ack', () => {
    let ack: { status?: string; revision?: number } | undefined
    service.requestAgentRetry('wf-1', 'step-a', 7, 'COCKPIT_MANUAL_RETRY').subscribe((value) => (ack = value))

    const req = http.expectOne('/api/factory/workflows/wf-1/retries')
    expect(req.request.method).toBe('POST')
    expect(req.request.body).toEqual({ stepId: 'step-a', expectedRevision: 7, reasonCode: 'COCKPIT_MANUAL_RETRY' })
    req.flush({ data: { status: 'pending-human', revision: 8, interactionId: 'int-9' } })

    expect(ack?.status).toBe('pending-human')
    expect(service.lastRevision()).toBe(8)
  })

  it('POST respondToInteraction sends actionId + expectedRevision', () => {
    service.respondToInteraction('wf-1', 'int-1', 'approve', 9).subscribe()

    const req = http.expectOne('/api/factory/workflows/wf-1/interactions/int-1/reply')
    expect(req.request.method).toBe('POST')
    expect(req.request.body).toEqual({ actionId: 'approve', expectedRevision: 9 })
    req.flush({ data: { status: 'accepted', revision: 10 } })
  })

  it('POST decidePlanChange threads workflowId + decision body', () => {
    service.decidePlanChange('prop-1', 'approve', { workflowId: 'wf-1', expectedRevision: 3 }).subscribe()

    const req = http.expectOne((r) => r.url === '/api/factory/plan-change-proposals/prop-1/decide')
    expect(req.request.method).toBe('POST')
    expect(req.request.params.get('workflowId')).toBe('wf-1')
    expect(req.request.body).toEqual({ decision: 'approve', expectedRevision: 3 })
    req.flush({ data: { status: 'accepted', revision: 4 } })
  })

  it('normalizes a Factory error envelope into a structured error', () => {
    let error: FactoryApiError | undefined
    service.getWorkflow('wf-missing').subscribe({ error: (err) => (error = err) })

    http
      .expectOne('/api/factory/workflows/wf-missing')
      .flush(
        { error: { code: 'WORKFLOW_NOT_FOUND', message: 'No such workflow', details: { workflowId: 'wf-missing' } } },
        { status: 404, statusText: 'Not Found' }
      )

    expect(error?.code).toBe('WORKFLOW_NOT_FOUND')
    expect(error?.message).toBe('No such workflow')
    expect(error?.status).toBe(404)
  })

  it('degrades a controller-history 404 to an empty history', () => {
    let result: { entries: unknown[] } | undefined
    service.getControllerHistory('wf-1', 'ws-1').subscribe((value) => (result = value))

    http
      .expectOne('/api/factory/workstreams/ws-1/controller-case/history')
      .flush({ error: { code: 'WORKFLOW_NOT_FOUND', message: 'none' } }, { status: 404, statusText: 'Not Found' })

    expect(result?.entries).toEqual([])
  })

  it('unwrapEnvelope handles enveloped and bare payloads', () => {
    expect(unwrapEnvelope<number>({ data: 5 })).toBe(5)
    expect(unwrapEnvelope<number>(5)).toBe(5)
    expect(unwrapEnvelope<number[]>([1, 2])).toEqual([1, 2])
  })

  it('normalizeFactoryError falls back to HTTP_<status> when no code is present', () => {
    const error = normalizeFactoryError({ status: 0 })
    expect(error.code).toBe('UNKNOWN_ERROR')
  })
})
