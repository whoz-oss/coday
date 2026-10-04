import { provideRouter } from '@angular/router'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { FactoryStore } from '../../../core/factory.store'
import { Sandbox, SessionDetail } from '../../../core/models'
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

interface StoreStub {
  session: jest.Mock<SessionDetail | undefined, [string]>
}

function createStore(activeAttemptId?: string): StoreStub {
  const session: SessionDetail | undefined = activeAttemptId
    ? ({ id: 'wf-1', activeAttemptId } as SessionDetail)
    : undefined
  return { session: jest.fn().mockReturnValue(session) }
}

describe('SandboxCardComponent', () => {
  let fixture: ComponentFixture<SandboxCardComponent>
  let store: StoreStub

  async function setup(activeAttemptId?: string): Promise<void> {
    store = createStore(activeAttemptId)
    await TestBed.configureTestingModule({
      imports: [SandboxCardComponent],
      providers: [provideRouter([]), { provide: FactoryStore, useValue: store }],
    }).compileComponents()
    fixture = TestBed.createComponent(SandboxCardComponent)
  }

  function render(value: Sandbox = sandbox): HTMLElement {
    fixture.componentRef.setInput('sandbox', value)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function actionButtons(host: HTMLElement): HTMLButtonElement[] {
    return Array.from(host.querySelectorAll('.actions button')) as HTMLButtonElement[]
  }

  function buttonByText(host: HTMLElement, text: string): HTMLButtonElement | undefined {
    return actionButtons(host).find((button) => button.textContent?.trim() === text)
  }

  it('renders the real workflow identity and its real cost', async () => {
    await setup()
    const host = render()

    expect(host.querySelector('.name')?.textContent).toContain('wf-1')
    expect(host.textContent).toContain('ns-1')
    expect(host.textContent).toContain('ABC-1')
    expect(host.textContent).toContain('adw_simple_sdlc')
    // UsdPipe with 4 digits → $1.5000 (unknownCostCount = 0, so no ≥ prefix).
    expect(host.textContent).toContain('$1.5000')
  })

  it('offers a restorable Supprimer action, never an irreversible destroy', async () => {
    await setup()
    const host = render()

    expect(host.textContent).not.toContain('Détruire')
    expect(buttonByText(host, 'Supprimer')).not.toBeUndefined()
  })

  it('does not render the fabricated roster/wave fields', async () => {
    await setup()
    const host = render()

    expect(host.textContent).not.toContain('roster')
    expect(host.textContent).not.toContain('vague')
  })

  it('emits the footer actions', async () => {
    await setup()
    const actions: SandboxAction[] = []
    fixture.componentInstance.action.subscribe((action) => actions.push(action))
    const host = render()

    const buttons = actionButtons(host)
    expect(buttons.length).toBeGreaterThan(0)
    buttons[0]?.click()

    expect(actions).toEqual(['ask'])
  })

  it('shows the neutral idle status for an idle workflow', async () => {
    await setup()
    const host = render({ ...sandbox, status: 'idle' })
    expect(host.textContent).toContain('en attente')
  })

  describe('lifecycle actions', () => {
    it('renders "Arrêter" and emits stop when a running workflow has a resolvable active attempt', async () => {
      await setup('attempt-1')
      const actions: SandboxAction[] = []
      fixture.componentInstance.action.subscribe((action) => actions.push(action))
      const host = render()

      expect(store.session).toHaveBeenCalledWith('wf-1')
      const stop = buttonByText(host, 'Arrêter')
      expect(stop).not.toBeUndefined()
      stop?.click()
      expect(actions).toEqual(['stop'])
    })

    it('hides "Arrêter" when no active attempt is resolvable', async () => {
      await setup()
      const host = render()

      expect(buttonByText(host, 'Arrêter')).toBeUndefined()
    })

    it('hides "Arrêter" for a non-working workflow even with a resolvable attempt', async () => {
      await setup('attempt-1')
      const host = render({ ...sandbox, status: 'idle' })

      expect(buttonByText(host, 'Arrêter')).toBeUndefined()
    })

    it('renders "Supprimer" and emits remove on a non-destroyed workflow', async () => {
      await setup()
      const actions: SandboxAction[] = []
      fixture.componentInstance.action.subscribe((action) => actions.push(action))
      const host = render()

      const remove = buttonByText(host, 'Supprimer')
      expect(remove).not.toBeUndefined()
      remove?.click()
      expect(actions).toEqual(['remove'])
    })

    it('renders "Restaurer" and emits restore on a destroyed workflow only', async () => {
      await setup()
      const actions: SandboxAction[] = []
      fixture.componentInstance.action.subscribe((action) => actions.push(action))
      const host = render({ ...sandbox, status: 'destroyed' })

      expect(buttonByText(host, 'Supprimer')).toBeUndefined()
      expect(buttonByText(host, 'Arrêter')).toBeUndefined()
      const restore = buttonByText(host, 'Restaurer')
      expect(restore).not.toBeUndefined()
      restore?.click()
      expect(actions).toEqual(['restore'])
    })
  })
})
