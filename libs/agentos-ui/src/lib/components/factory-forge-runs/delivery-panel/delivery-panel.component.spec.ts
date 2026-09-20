import { ComponentFixture, TestBed } from '@angular/core/testing'
import { DeliveryPanelComponent } from './delivery-panel.component'

const snapshot = {
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
  stage: 'artifact-ready' as const,
  revision: 2,
  git: { checkpoint: null, push: null, pullRequest: null },
  artifact: { state: 'succeeded' as const },
  release: { state: 'pending' as const },
  deployment: { state: 'pending' as const },
  verification: { state: 'pending' as const },
  blockers: [],
}

describe('DeliveryPanelComponent', () => {
  let fixture: ComponentFixture<DeliveryPanelComponent>
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DeliveryPanelComponent] }).compileComponents()
    fixture = TestBed.createComponent(DeliveryPanelComponent)
    fixture.componentRef.setInput('delivery', snapshot)
    fixture.detectChanges()
  })
  it('shows distinct governed delivery stages', () => {
    expect(fixture.nativeElement.textContent).toContain('implementation-ready')
    expect(fixture.nativeElement.textContent).toContain('production-verified')
  })
  it('marks release approval as a human action', () => {
    expect(fixture.componentInstance.humanAction()).toBe(true)
    expect(fixture.nativeElement.textContent).toContain('Approuver la release')
  })
  it('rejects untrusted PR links', () => {
    expect(fixture.componentInstance.trustedUrl('javascript:alert(1)')).toBeNull()
    expect(fixture.componentInstance.trustedUrl('https://github.com/whoz-oss/coday/pull/1')).toContain('github.com')
  })
  it('stageItems marks done and current correctly for artifact-ready', () => {
    const items = fixture.componentInstance.stageItems()
    expect(items.find((i) => i.stage === 'implementation-ready')?.done).toBe(true)
    expect(items.find((i) => i.stage === 'artifact-ready')?.current).toBe(true)
    expect(items.find((i) => i.stage === 'release-approved')?.done).toBe(false)
    expect(items.find((i) => i.stage === 'release-approved')?.current).toBe(false)
  })
  it('shows no next stage for production-verified', () => {
    fixture.componentRef.setInput('delivery', { ...snapshot, stage: 'production-verified' })
    fixture.detectChanges()
    expect(fixture.componentInstance.nextStage()).toBeNull()
  })
  it('shows blocked message when delivery is null and not loading', () => {
    fixture.componentRef.setInput('delivery', null)
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('n’est pas encore liée')
  })
})
