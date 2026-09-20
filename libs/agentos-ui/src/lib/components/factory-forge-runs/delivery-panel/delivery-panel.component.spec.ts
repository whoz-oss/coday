import { ComponentFixture, TestBed } from '@angular/core/testing'
import { FactoryDeliverySnapshotDto } from '../../../services/factory-delivery.model'
import { DeliveryPanelComponent } from './delivery-panel.component'

const snapshot: FactoryDeliverySnapshotDto = {
  deliveryId: 'wf-delivery',
  namespaceId: 'namespace',
  workflowId: 'wf',
  environmentId: 'environment',
  environmentHash: `sha256:${'a'.repeat(64)}`,
  parentCaseId: 'case',
  runtimeId: 'runtime',
  branch: 'feature/unit',
  baseCommit: 'a'.repeat(40),
  headCommit: 'b'.repeat(40),
  stage: 'artifact-ready',
  revision: 2,
  git: { checkpoint: null, push: null, pullRequest: null },
  artifact: { state: 'succeeded' },
  release: { state: 'pending' },
  deployment: { state: 'pending' },
  verification: { state: 'pending' },
  blockers: [],
  deliveryOperations: [
    {
      operationId: 'dop_1',
      kind: 'deployment',
      state: 'running',
      targetRef: { targetId: 'prod' },
      attempt: 1,
      requestedAt: '2026-01-01T10:00:00.000Z',
      startedAt: '2026-01-01T10:01:00.000Z',
    },
  ],
  unresolvedIndeterminate: [],
  rollbackRequests: [
    {
      rollbackRequestId: 'rrq_1',
      status: 'requested',
      targetId: 'prod',
      reasonCode: 'bad-release',
      reason: 'Observed regression',
      requestedAt: '2026-01-01T11:00:00.000Z',
      requestedBy: { actorId: 'alice' },
    },
  ],
}

describe('DeliveryPanelComponent', () => {
  let fixture: ComponentFixture<DeliveryPanelComponent>
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DeliveryPanelComponent] }).compileComponents()
    fixture = TestBed.createComponent(DeliveryPanelComponent)
    fixture.componentRef.setInput('delivery', snapshot)
    fixture.detectChanges()
  })
  it('keeps the five-stage lifecycle visible', () => {
    expect(fixture.nativeElement.textContent).toContain('implementation-ready')
    expect(fixture.nativeElement.textContent).toContain('production-verified')
    expect(fixture.nativeElement.textContent).toContain('does not rewind')
  })
  it('renders operation kind, state, trusted target reference, and attempt', () => {
    expect(fixture.nativeElement.textContent).toContain('deployment')
    expect(fixture.nativeElement.textContent).toContain('Status: running')
    expect(fixture.nativeElement.textContent).toContain('prod')
    expect(fixture.nativeElement.textContent).toContain('Attempt')
  })
  it('shows an alert explaining unresolved indeterminate operations are not replayed', () => {
    fixture.componentRef.setInput('delivery', {
      ...snapshot,
      unresolvedIndeterminate: [{ ...snapshot.deliveryOperations![0]!, state: 'indeterminate' }],
    })
    fixture.detectChanges()
    const alert = fixture.nativeElement.querySelector('[role="alert"].indeterminate-warning')
    expect(alert.textContent).toContain('Reconciliation required')
    expect(alert.textContent).toContain('no automatic replay')
  })
  it('renders requested and approved rollback states', () => {
    expect(fixture.nativeElement.textContent).toContain('Status: requested')
    fixture.componentRef.setInput('delivery', {
      ...snapshot,
      rollbackRequests: [
        {
          ...snapshot.rollbackRequests![0]!,
          status: 'approved',
          approvedAt: '2026-01-01T12:00:00.000Z',
          approvedBy: { actorId: 'bob' },
        },
      ],
    })
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('Status: approved')
    expect(fixture.nativeElement.textContent).toContain('bob')
  })
  it('exposes no delivery action buttons', () => {
    expect(fixture.nativeElement.querySelectorAll('button').length).toBe(0)
  })
  it('shows operation and rollback empty states', () => {
    fixture.componentRef.setInput('delivery', { ...snapshot, deliveryOperations: [], rollbackRequests: [] })
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('No delivery operations recorded.')
    expect(fixture.nativeElement.textContent).toContain('No rollback requests recorded.')
  })
  it('rejects untrusted PR links', () => {
    expect(fixture.componentInstance.trustedUrl('javascript:alert(1)')).toBeNull()
    expect(fixture.componentInstance.trustedUrl('https://github.com/whoz-oss/coday/pull/1')).toContain('github.com')
  })
  it('shows the unlinked delivery empty state', () => {
    fixture.componentRef.setInput('delivery', null)
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('not yet linked')
  })
})
