import { TestBed } from '@angular/core/testing'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { provideHttpClient } from '@angular/common/http'
import { FactoryApiService, FactoryLaunchRequest, FactoryLaunchResponse } from './factory-api.service'

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
})
