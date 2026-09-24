import { ComponentFixture, TestBed } from '@angular/core/testing'
import { By } from '@angular/platform-browser'
import { FactoryWorkflowProjectionComponent } from './factory-workflow-projection.component'
import {
  durableControllerExecution,
  latestNegativeAgentResultReason,
  WorkflowProjectionSnapshotDto,
} from '../../services/factory-workflow-projection.model'
import { FactoryApiService } from '../../services/factory-api.service'
import { of } from 'rxjs'

describe('FactoryWorkflowProjectionComponent', () => {
  let fixture: ComponentFixture<FactoryWorkflowProjectionComponent>
  let api: Record<string, jest.Mock>
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
    api = {
      getWorkflowOperationalMetrics: jest.fn(() => of({ data: metrics })),
      listWorkflowHumanInteractions: jest.fn(() => of({ data: { items: [] } })),
      listWorkflowEvidence: jest.fn(() => of({ data: { items: [] } })),
      requestWorkflowRetry: jest.fn(() => of({ data: { status: 'WAITING_HUMAN' } })),
      continueWorkflow: jest.fn(() => of({ data: { status: 'RUNNING' } })),
      getWorkflowEnvironment: jest.fn(() => of({ data: null })),
      getDelivery: jest.fn(() => of({ data: null })),
    }
    await TestBed.configureTestingModule({
      imports: [FactoryWorkflowProjectionComponent],
      providers: [{ provide: FactoryApiService, useValue: api }],
    }).compileComponents()
    fixture = TestBed.createComponent(FactoryWorkflowProjectionComponent)
    fixture.componentRef.setInput('snapshot', projection)
    fixture.componentRef.setInput('namespaceId', '11111111-1111-4111-8111-111111111111')
    fixture.detectChanges()
  })

  it('prefers the durable instance controller and falls back for legacy snapshots', () => {
    const legacy = {
      kind: 'agentos' as const,
      runtimeId: 'worker',
      caseId: 'worker-case',
      agentId: 'Worker',
      observedAt: '2026-01-01T00:00:00Z',
    }
    const durable = { ...legacy, runtimeId: 'controller', caseId: 'controller-case', agentId: 'ProductEngineer' }
    expect(
      durableControllerExecution({
        ...projection,
        controllerExecution: legacy,
        instance: { controllerExecution: durable },
      })
    ).toBe(durable)
    expect(durableControllerExecution({ ...projection, controllerExecution: legacy })).toBe(legacy)
  })

  it('derives only the latest valid negative agent result reason', () => {
    expect(
      latestNegativeAgentResultReason(
        [
          {
            evidenceId: '1',
            workflowId: 'workflow-1',
            stepId: 'execute',
            kind: 'agent-result',
            outcome: 'fail',
            facts: { resultCode: 'OLD' },
          },
          {
            evidenceId: '2',
            workflowId: 'workflow-1',
            stepId: 'execute',
            kind: 'agent-result',
            outcome: 'indeterminate',
            facts: { resultCode: 'EXACT_REASON' },
          },
        ],
        'execute'
      )
    ).toBe('EXACT_REASON')
    expect(
      latestNegativeAgentResultReason(
        [
          {
            evidenceId: '3',
            workflowId: 'workflow-1',
            stepId: 'execute',
            kind: 'agent-result',
            outcome: 'fail',
            facts: { resultCode: 42 },
          },
        ],
        'execute'
      )
    ).toBeNull()
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

  it('requests retry with authoritative revision and exact evidence reason, then reloads interactions', () => {
    const governed: WorkflowProjectionSnapshotDto = {
      ...projection,
      revision: 9,
      projection: {
        ...projection.projection,
        schemaVersion: '2',
        status: 'blocked',
        steps: [
          { id: 'execute', name: 'Execute', status: 'blocked', dependsOn: [], responsibility: { kind: 'agent' } },
        ],
      },
    }
    api['listWorkflowEvidence'].mockReturnValue(
      of({
        data: {
          items: [
            {
              evidenceId: 'e1',
              workflowId: 'workflow-1',
              stepId: 'execute',
              kind: 'agent-result',
              outcome: 'fail',
              facts: { resultCode: 'RESULT_NOT_JSON' },
            },
          ],
        },
      })
    )
    fixture.componentRef.setInput('snapshot', governed)
    fixture.detectChanges()
    const button = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) =>
      item.textContent.includes('Request retry')
    ) as HTMLButtonElement
    expect(button.disabled).toBe(false)
    button.click()
    expect(api['requestWorkflowRetry']).toHaveBeenCalledWith('workflow-1', {
      namespaceId: '11111111-1111-4111-8111-111111111111',
      stepId: 'execute',
      expectedRevision: 9,
      reasonCode: 'RESULT_NOT_JSON',
    })
    expect(api['listWorkflowHumanInteractions']).toHaveBeenCalledTimes(2)
  })

  it('prioritizes an open human interaction over blocked retry and renders one action surface', () => {
    const governed: WorkflowProjectionSnapshotDto = {
      ...projection,
      projection: {
        ...projection.projection,
        schemaVersion: '2',
        status: 'blocked',
        steps: [
          { id: 'execute', name: 'Execute', status: 'blocked', dependsOn: [], responsibility: { kind: 'agent' } },
        ],
      },
    }
    api['listWorkflowHumanInteractions'].mockReturnValue(
      of({
        data: {
          items: [
            {
              interactionId: 'decision-1',
              workflowId: 'workflow-1',
              stepId: 'execute',
              expectedRevision: 3,
              revision: 3,
              kind: 'approval',
              prompt: 'Approve retry?',
              status: 'open',
              openedAt: '2026-01-01T00:00:00Z',
              actions: [
                { id: 'approve', label: 'Approve', requestedStatus: 'ready' },
                { id: 'reject', label: 'Reject', requestedStatus: 'failed' },
              ],
            },
          ],
        },
      })
    )
    fixture.componentRef.setInput('snapshot', governed)
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('Human decision required')
    expect(fixture.nativeElement.textContent).not.toContain('Action required')
    expect(fixture.nativeElement.querySelectorAll('.workflow-projection__governed-action')).toHaveLength(1)
    expect(
      [...fixture.nativeElement.querySelectorAll('button')].filter((button: HTMLButtonElement) =>
        button.textContent.includes('Request retry')
      )
    ).toHaveLength(0)
  })

  it('keeps retry disabled without valid evidence and exposes continue only for ready steps', () => {
    const v2 = {
      ...projection,
      projection: {
        ...projection.projection,
        schemaVersion: '2' as const,
        status: 'blocked' as const,
        steps: [
          {
            id: 'execute',
            name: 'Execute',
            status: 'blocked' as const,
            dependsOn: [],
            responsibility: { kind: 'agent' as const },
          },
        ],
      },
    }
    fixture.componentRef.setInput('snapshot', v2)
    fixture.detectChanges()
    let action = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) =>
      item.textContent.includes('Request retry')
    ) as HTMLButtonElement
    expect(action.disabled).toBe(true)
    fixture.componentRef.setInput('snapshot', {
      ...v2,
      projection: { ...v2.projection, status: 'ready', steps: [{ ...v2.projection.steps[0], status: 'ready' }] },
    })
    fixture.detectChanges()
    action = [...fixture.nativeElement.querySelectorAll('button')].find((item: HTMLButtonElement) =>
      item.textContent.includes('Continue run')
    ) as HTMLButtonElement
    action.click()
    expect(api['continueWorkflow']).toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111', 'workflow-1')
  })

  it('does not request Delivery for an uninitialized workflow and clears its presentation', () => {
    const controlled: WorkflowProjectionSnapshotDto = {
      ...projection,
      instance: {
        controllerExecution: {
          runtimeId: 'controller',
          kind: 'agentos',
          caseId: 'case-1',
          agentId: 'controller',
          observedAt: '2026-01-01T00:00:00Z',
        },
        deliveryRef: null,
      },
    }
    fixture.componentRef.setInput('snapshot', controlled)
    fixture.detectChanges()
    expect(api['getDelivery']).not.toHaveBeenCalled()
    expect(fixture.nativeElement.textContent).toContain('Delivery not initialized')
    expect(fixture.nativeElement.textContent).not.toContain('INVALID_DELIVERY_SNAPSHOT')
  })

  it('renders an honest empty step state', () => {
    fixture.componentRef.setInput('snapshot', { ...projection, projection: { ...projection.projection, steps: [] } })
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('No steps are currently projected.')
  })
})
