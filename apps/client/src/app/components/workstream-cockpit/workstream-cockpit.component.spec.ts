import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, convertToParamMap } from '@angular/router'
import { FactoryWorkstreamService } from '../../core/services/factory-workstream.service'
import { WorkstreamCockpitComponent } from './workstream-cockpit.component'

const ACTIVATED_ROUTE_PROVIDER = {
  provide: ActivatedRoute,
  useValue: {
    snapshot: { paramMap: convertToParamMap({ projectName: 'demo-project' }) },
  },
}

describe('WorkstreamCockpitComponent (mock mode)', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WorkstreamCockpitComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), ACTIVATED_ROUTE_PROVIDER],
    }).compileComponents()
    TestBed.inject(FactoryWorkstreamService).setUseMock(true)
  })

  it('should create the cockpit', () => {
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()
    expect(fixture.componentInstance).toBeTruthy()
  })

  it('should populate workflows, preload details and select the first one', () => {
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()
    const component = fixture.componentInstance as any

    expect(component.workflowList().items.length).toBeGreaterThan(0)
    expect(component.selectedWorkflowId()).toBe(component.workflowList().items[0].workflowId)
    expect(component.workflowDetail()).toBeTruthy()
    expect(component.workflowDetail().steps.length).toBeGreaterThan(0)
    expect(Object.keys(component.detailsById()).length).toBeGreaterThan(0)
  })

  it('should render the freshness badge with the current revision and mock marker', () => {
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()
    const compiled = fixture.nativeElement as HTMLElement

    expect(compiled.querySelector('.ws-cockpit__freshness')?.textContent).toContain('rev')
    expect(compiled.querySelector('.ws-cockpit__freshness')?.textContent).toContain('mock data')
  })

  it('should load step attempts when a step is selected', () => {
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()
    const component = fixture.componentInstance as any

    component.onSelectStep('step-implement')

    expect(component.selectedStepId()).toBe('step-implement')
    expect(component.stepAttempts().length).toBeGreaterThan(0)
    expect(component.stepAttempts()[0].stepId).toBe('step-implement')
  })

  it('should expose allowed actions derived from the backend read (no hardcoded actions)', () => {
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()
    const component = fixture.componentInstance as any

    const actions = component.allowedActions() as Array<{ type?: string }>
    expect(actions.length).toBeGreaterThan(0)
    expect(actions.some((action) => action.type === 'reply')).toBe(true)
    expect(actions.some((action) => action.type === 'retry')).toBe(true)
  })

  it('should not be loading once the mock data resolves', () => {
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()
    const component = fixture.componentInstance as any

    expect(component.isLoading()).toBe(false)
    expect(component.errorMessage()).toBeNull()
  })

  it('should re-read the selected workflow after a plan-change decision', () => {
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()
    const component = fixture.componentInstance as any

    component.onDecide({ proposalId: 'prop-9', workflowId: 'wf-101', expectedRevision: 12, decision: 'approve' })

    expect(component.errorMessage()).toBeNull()
    expect(component.workflowDetail()).toBeTruthy()
  })
})

describe('WorkstreamCockpitComponent (live HTTP)', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WorkstreamCockpitComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), ACTIVATED_ROUTE_PROVIDER],
    }).compileComponents()
    TestBed.inject(FactoryWorkstreamService).setUseMock(false)
  })

  it('should show an error banner when the workflow list read fails', () => {
    const http = TestBed.inject(HttpTestingController)
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()

    http
      .expectOne((req) => req.url === '/api/factory/workstreams/ws-demo')
      .flush({ data: { workstreamId: 'ws-demo', organizationId: 'org', name: 'Demo', status: 'active', revision: 1 } })
    http
      .expectOne((req) => req.url === '/api/factory/workflows')
      .flush(
        { error: { code: 'FACTORY_UNAVAILABLE', message: 'Factory is down' } },
        { status: 503, statusText: 'Service Unavailable' }
      )

    fixture.detectChanges()

    const component = fixture.componentInstance as any
    expect(component.isLoading()).toBe(false)
    expect(component.errorMessage()).toContain('FACTORY_UNAVAILABLE')

    const compiled = fixture.nativeElement as HTMLElement
    expect(compiled.querySelector('.ws-cockpit__error')?.textContent).toContain('Factory is down')
  })

  it('should render live workflow projections from the real endpoints', () => {
    const http = TestBed.inject(HttpTestingController)
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()

    http
      .match((req) => req.url === '/api/factory/workstreams/ws-demo')
      .forEach((req) =>
        req.flush({
          data: { workstreamId: 'ws-demo', organizationId: 'org', name: 'Demo', status: 'active', revision: 1 },
        })
      )
    http
      .match((req) => req.url === '/api/factory/workflows')
      .forEach((req) =>
        req.flush({
          data: {
            items: [
              {
                workflowId: 'wf-live',
                workflowType: 'feature-delivery',
                title: 'Live',
                status: 'running',
                revision: 5,
              },
            ],
            nextCursor: null,
          },
        })
      )

    // Preloads + auto-selection of the first workflow queue the downstream reads.
    const detail = {
      state: 'existing',
      workflowId: 'wf-live',
      revision: 5,
      workflowType: 'feature-delivery',
      status: 'running',
      steps: [{ stepId: 'step-live', status: 'running', revision: 2 }],
      blockers: [],
    }
    http.match((req) => req.url === '/api/factory/workflows/wf-live').forEach((req) => req.flush({ data: detail }))
    http
      .match((req) => req.url === '/api/factory/workflows/wf-live/interactions')
      .forEach((req) => req.flush({ data: [] }))
    http
      .match((req) => req.url === '/api/factory/workflows/wf-live/actions')
      .forEach((req) =>
        req.flush({
          data: { allowedActions: [{ type: 'retry', stepId: 'step-live', expectedRevision: 5 }], blockers: [] },
        })
      )
    http.match((req) => req.url === '/api/factory/plan-change-proposals').forEach((req) => req.flush({ data: [] }))
    http
      .match((req) => req.url === '/api/factory/workstreams/ws-demo/controller-case/history')
      .forEach((req) => req.flush({ data: { workstreamId: 'ws-demo', activeCaseId: null, cases: [] } }))

    fixture.detectChanges()

    const component = fixture.componentInstance as any
    expect(component.workflowDetail()?.workflowId).toBe('wf-live')
    expect(component.currentRevision()).toBe(5)
    expect((component.allowedActions() as Array<{ type?: string }>)[0].type).toBe('retry')
  })
})
