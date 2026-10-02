import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { COCKPIT_V2_API_BASE, CockpitV2ApiService } from './cockpit-v2-api.service'

describe('CockpitV2ApiService', () => {
  let service: CockpitV2ApiService
  let httpMock: HttpTestingController

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    })
    service = TestBed.inject(CockpitV2ApiService)
    httpMock = TestBed.inject(HttpTestingController)
  })

  afterEach(() => httpMock.verify())

  it('fetches the service descriptor', () => {
    const payload = {
      service: 'cockpit-v2',
      status: 'ok',
      version: '3.21.0',
      endpoints: [`GET ${COCKPIT_V2_API_BASE}`],
    }

    service.getServiceInfo().subscribe((info) => expect(info).toEqual(payload))

    const req = httpMock.expectOne(COCKPIT_V2_API_BASE)
    expect(req.request.method).toBe('GET')
    req.flush(payload)
  })

  it('fetches the live status', () => {
    const payload = {
      service: 'cockpit-v2',
      status: 'ok',
      version: '3.21.0',
      timestamp: '2026-01-02T03:04:05.000Z',
    }

    service.getStatus().subscribe((status) => expect(status).toEqual(payload))

    const req = httpMock.expectOne(`${COCKPIT_V2_API_BASE}/status`)
    expect(req.request.method).toBe('GET')
    req.flush(payload)
  })
})
