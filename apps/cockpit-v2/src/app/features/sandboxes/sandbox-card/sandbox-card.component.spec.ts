import { provideRouter } from '@angular/router'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { Sandbox } from '../../../core/models'
import { SandboxAction, SandboxCardComponent } from './sandbox-card.component'

const sandbox: Sandbox = {
  name: 'wf-1',
  project: 'ns-1',
  namespace: 'ns-1',
  ticket: 'ABC-1',
  branch: 'ABC-1',
  workflowType: 'adw_simple_sdlc',
  status: 'working',
  run: {
    id: 'wf-1',
    workflow: 'adw_simple_sdlc',
    status: 'running',
    goal: 'Do the real thing',
    costUsd: 1.5,
    unknownCostCount: 0,
    durationSec: 90,
    tokens: 1000,
    phases: [],
  },
}

describe('SandboxCardComponent', () => {
  let fixture: ComponentFixture<SandboxCardComponent>

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SandboxCardComponent],
      providers: [provideRouter([])],
    }).compileComponents()
    fixture = TestBed.createComponent(SandboxCardComponent)
  })

  function render(value: Sandbox = sandbox): HTMLElement {
    fixture.componentRef.setInput('sandbox', value)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  it('renders the real workflow identity and its real cost', () => {
    const host = render()

    expect(host.querySelector('.name')?.textContent).toContain('wf-1')
    expect(host.textContent).toContain('ns-1')
    expect(host.textContent).toContain('ABC-1')
    expect(host.textContent).toContain('adw_simple_sdlc')
    // UsdPipe with 4 digits → $1.5000 (unknownCostCount = 0, so no ≥ prefix).
    expect(host.textContent).toContain('$1.5000')
  })

  it('never renders a destroy button (no backend teardown action exists)', () => {
    const host = render()

    expect(host.querySelector('.sf-danger')).toBeNull()
    expect(host.textContent).not.toContain('Détruire')
  })

  it('does not render the fabricated roster/wave fields', () => {
    const host = render()

    expect(host.textContent).not.toContain('roster')
    expect(host.textContent).not.toContain('vague')
  })

  it('emits the footer actions', () => {
    const actions: SandboxAction[] = []
    fixture.componentInstance.action.subscribe((action) => actions.push(action))
    const host = render()

    const buttons = Array.from(host.querySelectorAll('.actions button')) as HTMLButtonElement[]
    expect(buttons.length).toBeGreaterThan(0)
    buttons[0]?.click()

    expect(actions).toEqual(['ask'])
  })

  it('shows the neutral idle status for an idle workflow', () => {
    const host = render({ ...sandbox, status: 'idle' })
    expect(host.textContent).toContain('en attente')
  })
})
