import { TestBed } from '@angular/core/testing'
import { PhaseSegment } from '../../core/models'
import { PhaseBarComponent } from './phase-bar.component'

describe('PhaseBarComponent', () => {
  it('renders skipped phases neutrally and never announces them as completed', async () => {
    await TestBed.configureTestingModule({ imports: [PhaseBarComponent] }).compileComponents()
    const fixture = TestBed.createComponent(PhaseBarComponent)
    const phases: PhaseSegment[] = [{ key: 'backend', ratio: 1, tone: 'green', status: 'skipped' }]
    fixture.componentRef.setInput('phases', phases)
    fixture.detectChanges()

    const host = fixture.nativeElement as HTMLElement
    expect(host.querySelector('.seg')?.classList.contains('skipped')).toBe(true)
    expect(host.querySelector('.seg')?.classList.contains('running')).toBe(false)
    expect(host.querySelector('.bar')?.getAttribute('aria-label')).toBe('Phases: backend skipped, not executed')
    expect(fixture.componentInstance.ariaLabel()).not.toContain('completed')
  })
})
