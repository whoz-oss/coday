import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, convertToParamMap } from '@angular/router'
import { WorkstreamCockpitComponent } from './workstream-cockpit.component'

describe('WorkstreamCockpitComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WorkstreamCockpitComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ projectName: 'demo-project' }) },
          },
        },
      ],
    }).compileComponents()
  })

  it('should create the cockpit', () => {
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()
    expect(fixture.componentInstance).toBeTruthy()
  })

  it('should populate mock workflows and select the first one', () => {
    const fixture = TestBed.createComponent(WorkstreamCockpitComponent)
    fixture.detectChanges()
    const component = fixture.componentInstance as any

    expect(component.workflowList().items.length).toBeGreaterThan(0)
    expect(component.selectedWorkflowId()).toBe(component.workflowList().items[0].workflowId)
    expect(component.workflowDetail()).toBeTruthy()
    expect(component.workflowDetail().steps.length).toBeGreaterThan(0)
  })

  it('should render the freshness badge with the current revision', () => {
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
})
