import { ComponentFixture, TestBed } from '@angular/core/testing'
import { By } from '@angular/platform-browser'
import { FactoryWorkflowProjectionComponent } from './factory-workflow-projection.component'
import { WorkflowProjectionSnapshotDto } from '../../services/factory-workflow-projection.model'
import { FactoryApiService } from '../../services/factory-api.service'
import { of } from 'rxjs'

describe('FactoryWorkflowProjectionComponent', () => {
  let fixture: ComponentFixture<FactoryWorkflowProjectionComponent>
  const projection: WorkflowProjectionSnapshotDto = {
    workflowId: 'workflow-1',
    revision: 3,
    projectionHash: 'hash',
    projection: {
      schemaVersion: '1',
      workflowId: 'workflow-1',
      workflowType: 'delivery',
      title: 'Generic delivery',
      status: 'running',
      steps: [
        { id: 'prepare', name: 'Prepare', status: 'completed', dependsOn: [] },
        { id: 'execute', name: 'Execute', status: 'ready', dependsOn: ['prepare'] },
      ],
    },
  }

  beforeEach(async () => {
    const metrics = {
      schemaVersion: '1' as const,
      observedAt: '2026-01-02T03:04:05.000Z',
      scope: { kind: 'self' as const, workflowId: 'workflow-1', includedWorkflowIds: ['workflow-1'] },
      metrics: {
        cycleTime: {
          available: true,
          complete: true,
          reasons: [],
          sourceCategories: ['workflow-journal'],
          value: {
            semantics: 'workflow_interval' as const,
            startAt: '2026-01-01T00:00:00Z',
            endAt: '2026-01-01T01:00:00Z',
            durationMs: 3600000,
          },
        },
        reviewTime: {
          available: false,
          complete: false,
          reasons: ['review_unavailable'],
          sourceCategories: ['human-interaction-journal'],
        },
        currentWip: {
          available: true,
          complete: true,
          reasons: [],
          sourceCategories: ['workflow-snapshot'],
          value: { semantics: 'unique_workflows_in_non_terminal_states' as const, count: 1, byState: { running: 1 } },
        },
        deploymentDelay: {
          available: false,
          complete: false,
          reasons: ['delivery_promotion_proof_unavailable'],
          sourceCategories: ['delivery-operation-journal'],
        },
        workflowCreatedToProductionVerified: {
          available: false,
          complete: false,
          reasons: ['authoritative_creation_or_production_verification_unavailable'],
          sourceCategories: ['delivery-operation-journal'],
        },
      },
      capabilities: {
        llmUsage: { available: false as const, reason: 'llm_usage_capture_not_implemented' },
        cost: { available: false as const, reason: 'pricing_and_cost_projection_not_implemented' },
        dora: { available: false as const, reason: 'dora_metrics_not_implemented' },
        rollbackRate: { available: false as const, reason: 'rollback_rate_not_implemented' },
      },
    }
    await TestBed.configureTestingModule({
      imports: [FactoryWorkflowProjectionComponent],
      providers: [
        {
          provide: FactoryApiService,
          useValue: {
            getWorkflowOperationalMetrics: () => of({ data: metrics }),
            listWorkflowHumanInteractions: () => of({ data: { items: [] } }),
          },
        },
      ],
    }).compileComponents()
    fixture = TestBed.createComponent(FactoryWorkflowProjectionComponent)
    fixture.componentRef.setInput('snapshot', projection)
    fixture.componentRef.setInput('namespaceId', '11111111-1111-4111-8111-111111111111')
    fixture.detectChanges()
  })

  it('renders generic metadata and ordered steps with dependencies', () => {
    const text = fixture.nativeElement.textContent
    expect(text).toContain('Generic delivery')
    expect(text).toContain('delivery · Revision 3')
    expect(text).not.toContain('Runtime origin')
    expect(text).not.toContain('Workflow status: running')
    expect(text).toContain('running')
    const steps = fixture.debugElement.queryAll(By.css('ol > li'))
    expect(steps).toHaveLength(2)
    expect(steps[1].nativeElement.textContent).toContain('prepare')
  })

  it('associates the article with its visible heading and exposes status labels', () => {
    const article = fixture.debugElement.query(By.css('article')).nativeElement as HTMLElement
    const heading = fixture.debugElement.query(By.css('h3')).nativeElement as HTMLElement
    expect(article.getAttribute('aria-labelledby')).toBe(heading.id)
    expect(fixture.nativeElement.textContent).toContain('Workflow status:')
    expect(fixture.nativeElement.textContent).toContain('Step status:')
  })

  it('provides accessible destructive confirmations, exact scope warning, and double-confirm purge', () => {
    expect(fixture.nativeElement.textContent).toContain('Remove from Factory')
    ;(fixture.nativeElement.querySelector('footer button') as HTMLButtonElement).click()
    fixture.detectChanges()
    let dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement
    expect(dialog.textContent).toContain(
      'does not delete BMAD, Jira, Git, CRM, Coday, AgentOS cases, conversations, branches, or worktrees'
    )
    ;(dialog.querySelector('.workflow-projection__dialog-actions button:first-child') as HTMLButtonElement).click()
    fixture.componentRef.setInput('mode', 'removed')
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('Restore')
    const buttons = fixture.nativeElement.querySelectorAll('footer button') as NodeListOf<HTMLButtonElement>
    buttons[1].click()
    fixture.detectChanges()
    dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement
    expect(dialog.textContent).toContain('cannot be undone')
    const permanent = dialog.querySelector(
      '.workflow-projection__dialog-actions button:last-child'
    ) as HTMLButtonElement
    expect(permanent.disabled).toBe(true)
  })

  it('disables conflicting actions while pending without removing the card', () => {
    fixture.componentRef.setInput('pending', true)
    fixture.detectChanges()
    expect(fixture.debugElement.query(By.css('article'))).not.toBeNull()
    expect((fixture.nativeElement.querySelector('footer button') as HTMLButtonElement).disabled).toBe(true)
    expect(fixture.nativeElement.textContent).toContain('Submitting')
  })

  it('renders compact scoped operational metrics without unsupported capability cards', () => {
    const text = fixture.nativeElement.textContent
    expect(text).toContain('Operational metrics')
    expect(text).toContain('Scope: This workflow')
    expect(text).toContain('Cycle time')
    expect(text).toContain('Current WIP')
    expect(text).toContain('Unavailable')
    expect(text).not.toContain('LLM usage')
    expect(text).not.toContain('Rollback rate')
  })

  it('renders an honest empty step state', () => {
    fixture.componentRef.setInput('snapshot', { ...projection, projection: { ...projection.projection, steps: [] } })
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('No steps are currently projected.')
  })
})
