import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { DashboardComponent } from './dashboard.component'
import { COCKPIT_V2_API_BASE } from '../../shared'

describe('DashboardComponent', () => {
  let httpMock: HttpTestingController

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents()

    httpMock = TestBed.inject(HttpTestingController)
  })

  afterEach(() => httpMock.verify())

  it('displays the backend service descriptor', () => {
    const fixture = TestBed.createComponent(DashboardComponent)
    fixture.detectChanges()

    httpMock.expectOne(COCKPIT_V2_API_BASE).flush({
      service: 'cockpit-v2',
      status: 'ok',
      version: '3.21.0',
      endpoints: [`GET ${COCKPIT_V2_API_BASE}`],
    })
    fixture.detectChanges()

    const root = fixture.nativeElement as HTMLElement
    const info = root.querySelector('[data-testid="service-info"]')
    expect(info).toBeTruthy()
    expect(info?.textContent).toContain('3.21.0')
  })

  it('shows an empty state when the API is unavailable', () => {
    const fixture = TestBed.createComponent(DashboardComponent)
    fixture.detectChanges()

    httpMock.expectOne(COCKPIT_V2_API_BASE).error(new ProgressEvent('error'))
    fixture.detectChanges()

    const root = fixture.nativeElement as HTMLElement
    expect(root.querySelector('[data-testid="service-info-empty"]')).toBeTruthy()
  })
})
