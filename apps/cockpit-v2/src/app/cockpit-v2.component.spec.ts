import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { provideRouter } from '@angular/router'
import { CockpitV2Component } from './cockpit-v2.component'
import { COCKPIT_V2_API_BASE } from './shared'

describe('CockpitV2Component', () => {
  let httpMock: HttpTestingController

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CockpitV2Component],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents()

    httpMock = TestBed.inject(HttpTestingController)
  })

  afterEach(() => httpMock.verify())

  it('creates the shell and renders the shared header', () => {
    const fixture = TestBed.createComponent(CockpitV2Component)
    fixture.detectChanges()

    // Flush the status request issued by the root component on creation.
    httpMock.expectOne(`${COCKPIT_V2_API_BASE}/status`).flush({
      service: 'cockpit-v2',
      status: 'ok',
      version: '3.21.0',
      timestamp: '2026-01-02T03:04:05.000Z',
    })

    const root = fixture.nativeElement as HTMLElement
    expect(fixture.componentInstance).toBeTruthy()
    expect(root.querySelector('cockpit-v2-header')).toBeTruthy()
    expect(root.querySelector('.cockpit-shell')).toBeTruthy()
  })
})
