import { TestBed } from '@angular/core/testing'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { provideHttpClient } from '@angular/common/http'
import {
  FactoryApiService,
  FactoryLaunchRequest,
  FactoryLaunchResponse,
  FactoryStopResponse,
  JiraTicketResponse,
} from './factory-api.service'

describe('FactoryApiService', () => {
  let service: FactoryApiService
  let http: HttpTestingController

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [FactoryApiService, provideHttpClient(), provideHttpClientTesting()],
    })
    service = TestBed.inject(FactoryApiService)
    http = TestBed.inject(HttpTestingController)
  })

  afterEach(() => {
    http.verify()
    TestBed.resetTestingModule()
  })

  describe('workflow lifecycle', () => {
    it('lists removed workflows with the removed state query', () => {
      service.listRemovedWorkflowProjections('namespace-1').subscribe()
      const req = http.expectOne(
        (r) =>
          r.url === '/api/factory/workflows' &&
          r.params.get('namespaceId') === 'namespace-1' &&
          r.params.get('state') === 'removed'
      )
      expect(req.request.method).toBe('GET')
      req.flush({ data: { namespaceId: 'namespace-1', state: 'removed', items: [] } })
    })

    it('uses Angular DELETE options for the optional actor body', () => {
      service.removeWorkflowProjection('namespace-1', 'workflow/1', 'actor-1').subscribe()
      const req = http.expectOne(
        (r) => r.url === '/api/factory/workflows/workflow%2F1' && r.params.get('namespaceId') === 'namespace-1'
      )
      expect(req.request.method).toBe('DELETE')
      expect(req.request.body).toEqual({ actorId: 'actor-1' })
      req.flush({ data: { namespaceId: 'namespace-1', workflowId: 'workflow/1', state: 'removed' } })
    })

    it('restores and purges without inventing actor identity', () => {
      service.restoreWorkflowProjection('namespace-1', 'workflow-1').subscribe()
      const restore = http.expectOne((r) => r.url.endsWith('/workflow-1/restore'))
      expect(restore.request.method).toBe('POST')
      expect(restore.request.body).toEqual({})
      restore.flush({ data: { namespaceId: 'namespace-1', workflowId: 'workflow-1', revision: 2, state: 'active' } })
      service.purgeWorkflowProjection('namespace-1', 'workflow-1').subscribe()
      const purge = http.expectOne((r) => r.url.endsWith('/workflow-1/purge'))
      expect(purge.request.method).toBe('DELETE')
      expect(purge.request.body).toBeNull()
      purge.flush({ data: { namespaceId: 'namespace-1', workflowId: 'workflow-1', state: 'purged' } })
    })
  })

  describe('stopRun', () => {
    it('POSTs to /api/factory/runs/:id/stop', () => {
      service.stopRun('run-abc').subscribe()

      const req = http.expectOne('/api/factory/runs/run-abc/stop')
      expect(req.request.method).toBe('POST')
      req.flush({ runId: 'run-abc', stopping: true } satisfies FactoryStopResponse)
    })

    it('returns the stop response from the server', () => {
      let result: FactoryStopResponse | undefined
      service.stopRun('run-xyz').subscribe((r) => (result = r))

      http.expectOne('/api/factory/runs/run-xyz/stop').flush({ runId: 'run-xyz', stopping: true })

      expect(result).toEqual({ runId: 'run-xyz', stopping: true })
    })

    it('encodes the runId in the URL', () => {
      service.stopRun('run/with/slashes').subscribe()

      const req = http.expectOne('/api/factory/runs/run%2Fwith%2Fslashes/stop')
      expect(req.request.method).toBe('POST')
      req.flush({ runId: 'run/with/slashes', stopping: true })
    })
  })

  describe('launchRun', () => {
    const request: FactoryLaunchRequest = {
      workflow: 'fix-loop',
      FACTORY_NAMESPACE_ID: 'ns-1',
      FACTORY_TASK: 'Fix the bug',
      FACTORY_AGENT: 'factory-editor',
      FACTORY_DOMAIN: 'front',
    }

    it('POSTs to /api/factory/runs with the request body', () => {
      service.launchRun(request).subscribe()

      const req = http.expectOne('/api/factory/runs')
      expect(req.request.method).toBe('POST')
      expect(req.request.body).toEqual(request)
      req.flush({ pid: 1234, runId: 'run-abc' } satisfies FactoryLaunchResponse)
    })

    it('returns the pid and runId from the server response', () => {
      let result: FactoryLaunchResponse | undefined
      service.launchRun(request).subscribe((r) => (result = r))

      http.expectOne('/api/factory/runs').flush({ pid: 999, runId: 'run-xyz' })

      expect(result).toEqual({ pid: 999, runId: 'run-xyz' })
    })

    it('returns runId as null when the server has not yet discovered the JSONL file', () => {
      let result: FactoryLaunchResponse | undefined
      service.launchRun(request).subscribe((r) => (result = r))

      http.expectOne('/api/factory/runs').flush({ pid: 777, runId: null })

      expect(result?.runId).toBeNull()
    })
  })

  describe('listForgeRuns', () => {
    it('GETs the server-projected Forge endpoint under the Factory proxy', () => {
      service.listForgeRuns().subscribe()

      const req = http.expectOne('/api/factory/forge/runs')
      expect(req.request.method).toBe('GET')
      req.flush([])
    })
  })

  describe('getJiraTicket', () => {
    const mockTicket: JiraTicketResponse = {
      ticketId: 'PROJ-1234',
      ticketContent: '## Summary\nFix login timeout',
      summary: 'Fix login timeout',
      fieldCount: 2,
      commentCount: 3,
      commentsIncluded: 3,
      commentsTruncated: false,
      fetchedAt: '2025-01-01T10:00:00.000Z',
    }

    it('GETs /api/factory/jira/:ticketId — not /api/jira/', () => {
      // Regression guard: the URL MUST be under /api/factory/ so the Angular
      // dev-server proxy rule (proxy.conf.json) forwards it to port 3141.
      // /api/jira/* has no proxy rule and falls through to the SPA index.
      service.getJiraTicket('PROJ-1234').subscribe()

      const req = http.expectOne('/api/factory/jira/PROJ-1234')
      expect(req.request.method).toBe('GET')
      req.flush(mockTicket)
    })

    it('returns the JiraTicketResponse from the server', () => {
      let result: JiraTicketResponse | undefined
      service.getJiraTicket('PROJ-1234').subscribe((r) => (result = r))

      http.expectOne('/api/factory/jira/PROJ-1234').flush(mockTicket)

      expect(result).toEqual(mockTicket)
    })

    it('percent-encodes special characters in ticketId', () => {
      service.getJiraTicket('PROJ/99').subscribe()

      const req = http.expectOne('/api/factory/jira/PROJ%2F99')
      expect(req.request.method).toBe('GET')
      req.flush(mockTicket)
    })
  })
})
