import { provideRouter } from '@angular/router'
import { signal, WritableSignal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { FactoryStore } from '../../../core/factory.store'
import { FactoryRun, SessionDetail, StopFeedback, SupervisorCaseResult } from '../../../core/models'
import { RunCardComponent } from './run-card.component'
import { of } from 'rxjs'

const run: FactoryRun = {
  id: 'wf-1',
  title: 'wf-1',
  project: 'ns-1',
  namespaceId: 'ns-uuid-1',
  ticket: 'ABC-1',
  branch: 'ABC-1',
  workflowType: 'adw_simple_sdlc',
  status: 'working',
  costUsd: 1.5,
  durationSec: 90,
  tokens: 1000,
  phases: [],
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

const supervisorResult: SupervisorCaseResult = {
  caseId: 'heimdall-case-1',
  namespaceId: 'ns-uuid-1',
  agentOsUrl: '/agentos/home?ns=ns-uuid-1&case=heimdall-case-1',
}

interface StoreStub {
  session: jest.Mock<SessionDetail | undefined, [string]>
  openSupervisorCase: jest.Mock
  restoreSupervisorCase: jest.Mock
  stopFeedback: WritableSignal<StopFeedback | null>
}

function createStore(
  activeAttemptId?: string,
  supervisorResult$?: ReturnType<typeof of>,
  restoredCase?: SupervisorCaseResult,
  blockers: SessionDetail['blockers'] = []
): StoreStub {
  const session: SessionDetail | undefined = activeAttemptId
    ? ({ id: 'wf-1', activeAttemptId, blockers } as SessionDetail)
    : undefined
  return {
    session: jest.fn().mockReturnValue(session),
    openSupervisorCase: jest.fn().mockReturnValue(supervisorResult$ ?? of(supervisorResult)),
    restoreSupervisorCase: jest.fn().mockReturnValue(restoredCase ?? undefined),
    stopFeedback: signal<StopFeedback | null>(null),
  }
}

describe('RunCardComponent', () => {
  let fixture: ComponentFixture<RunCardComponent>
  let store: StoreStub

  async function setup(
    activeAttemptId?: string,
    supervisorResult$?: ReturnType<typeof of>,
    restoredCase?: SupervisorCaseResult,
    blockers: SessionDetail['blockers'] = []
  ): Promise<void> {
    store = createStore(activeAttemptId, supervisorResult$, restoredCase, blockers)
    await TestBed.configureTestingModule({
      imports: [RunCardComponent],
      providers: [provideRouter([]), { provide: FactoryStore, useValue: store }],
    }).compileComponents()
    fixture = TestBed.createComponent(RunCardComponent)
  }

  function render(value: FactoryRun = run): HTMLElement {
    fixture.componentRef.setInput('run', value)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function buttonByText(host: HTMLElement, text: string): HTMLButtonElement | undefined {
    return (Array.from(host.querySelectorAll('.actions button')) as HTMLButtonElement[]).find(
      (button) => button.textContent?.trim() === text
    )
  }

  it('renders the real run identity and its real cost', async () => {
    await setup()
    const host = render()

    expect(host.querySelector('.name')?.textContent).toContain('wf-1')
    expect(host.textContent).toContain('ns-1')
    expect(host.textContent).toContain('ABC-1')
    expect(host.textContent).toContain('adw_simple_sdlc')
    expect(host.textContent).toContain('$1.5000')
  })

  it('renders the failure reason of a pre-check refused run only when one is set', async () => {
    await setup()
    const refused = render({
      ...run,
      run: { ...run.run!, cardState: 'failed', failureReason: 'Required agent(s) reviewer are not available.' },
    })
    expect(refused.querySelector('.failure-reason')?.textContent).toContain('Required agent(s) reviewer')

    const plain = render()
    expect(plain.querySelector('.failure-reason')).toBeNull()
  })

  it('preserves the namespace in session navigation links', async () => {
    await setup()
    const host = render()
    const links = Array.from(host.querySelectorAll<HTMLAnchorElement>('a[href^="/sessions/wf-1"]'))

    expect(links).toHaveLength(2)
    expect(links.every((link) => link.getAttribute('href') === '/sessions/wf-1?namespaceId=ns-uuid-1')).toBe(true)
  })

  it('exposes an aria-label on the run card', async () => {
    await setup()
    const host = render()
    const card = host.querySelector('.card')
    expect(card?.getAttribute('role')).toBe('region')
    expect(card?.getAttribute('aria-label')).toContain('Run wf-1')
  })

  it('shows the neutral idle status for an idle run', async () => {
    await setup()
    const host = render({ ...run, status: 'idle' })
    expect(host.textContent).toContain('waiting')
  })

  describe('Ask supervisor removal', () => {
    it.each([
      ['a known namespace', run],
      ['no namespace', { ...run, namespaceId: undefined }],
    ])('renders no supervisor action for a run with %s', async (_label, value) => {
      await setup()
      const host = render(value)
      const actions = host.querySelector('.actions')

      expect(actions).not.toBeNull()
      expect(actions?.textContent).not.toContain('Ask supervisor')
      expect(actions?.textContent).not.toContain('View supervisor case')
      expect(host.querySelector('.supervisor-error')).toBeNull()
    })
  })

  describe('Conversation link', () => {
    it('opens the main case in a new tab with the exact label "Conversation"', async () => {
      await setup()
      const host = render({ ...run, namespaceId: 'ns-1', mainCaseId: 'case-main' })
      const link = host.querySelector<HTMLAnchorElement>('.actions a[href*="agentos"]')
      expect(link).not.toBeNull()
      expect(link?.getAttribute('href')).toBe('/agentos/home?ns=ns-1&case=case-main')
      expect(link?.getAttribute('target')).toBe('_blank')
      expect(link?.textContent?.trim()).toBe('Conversation')
    })

    it('is enabled from the main case alone, without any controller case', async () => {
      await setup()
      const host = render({ ...run, namespaceId: 'ns-1', mainCaseId: 'case-main', controllerCaseId: undefined })
      expect(host.querySelector('.actions a[href*="case-main"]')).not.toBeNull()
      expect(buttonByText(host, 'Conversation')).toBeUndefined()
    })

    it('prefers the main case over the controller case', async () => {
      await setup()
      const host = render({ ...run, namespaceId: 'ns-1', mainCaseId: 'case-main', controllerCaseId: 'case-controller' })
      const link = host.querySelector<HTMLAnchorElement>('.actions a[href*="agentos"]')
      expect(link?.getAttribute('href')).toBe('/agentos/home?ns=ns-1&case=case-main')
    })

    it('falls back to the controller case when no main case is known', async () => {
      await setup()
      const host = render({ ...run, namespaceId: 'ns-1', controllerCaseId: 'case-42' })
      const link = host.querySelector<HTMLAnchorElement>('.actions a[href*="agentos"]')
      expect(link?.getAttribute('href')).toBe('/agentos/home?ns=ns-1&case=case-42')
    })

    it('renders a disabled button labelled "Conversation" while no main case exists', async () => {
      await setup()
      const host = render({ ...run, mainCaseId: undefined, controllerCaseId: undefined })
      const disabledBtn = Array.from(host.querySelectorAll<HTMLButtonElement>('.actions button[disabled]')).find(
        (button) => button.textContent?.trim() === 'Conversation'
      )
      expect(disabledBtn).not.toBeUndefined()
      expect(disabledBtn?.getAttribute('title')).toContain('Main AgentOS case not created yet')
      expect(host.querySelector('.actions a[href*="agentos"]')).toBeNull()
    })

    it('omits the ns param when no namespace is known', async () => {
      await setup()
      const host = render({ ...run, namespaceId: undefined, mainCaseId: 'case-7' })
      const link = host.querySelector<HTMLAnchorElement>('a[href*="agentos"][href*="case-7"]')
      expect(link?.getAttribute('href')).toBe('/agentos/home?case=case-7')
    })
  })

  describe('lifecycle actions', () => {
    it('renders "Stop" and emits stop when a running run has a resolvable active attempt', async () => {
      await setup('attempt-1')
      const actions: string[] = []
      fixture.componentInstance.action.subscribe((action) => actions.push(action))
      const host = render()

      expect(store.session).toHaveBeenCalledWith('wf-1')
      const stop = buttonByText(host, 'Stop')
      expect(stop).not.toBeUndefined()
      stop?.click()
      expect(actions).toEqual(['stop'])
    })

    it('hides "Stop" when no active attempt is resolvable', async () => {
      await setup()
      const host = render()

      expect(buttonByText(host, 'Stop')).toBeUndefined()
    })

    it('hides "Stop" for a removed run even with a resolvable attempt', async () => {
      await setup('attempt-1')
      const host = render({ ...run, status: 'destroyed' })

      expect(buttonByText(host, 'Stop')).toBeUndefined()
    })

    it('offers "Retry stop" on a cancelled run whose kill the backend reports as unconfirmed', async () => {
      await setup('attempt-1', undefined, undefined, [
        { code: 'AGENT_KILL_UNCONFIRMED', label: 'Stop not confirmed', stepId: 'build' },
      ])
      const actions: string[] = []
      fixture.componentInstance.action.subscribe((action) => actions.push(action))
      // The run left `working`: it is sealed cancelled, yet Stop must stay reachable to re-send the kill.
      const host = render({ ...run, status: 'idle' })

      expect(buttonByText(host, 'Stop')).toBeUndefined()
      const retry = buttonByText(host, 'Retry stop')
      expect(retry).not.toBeUndefined()
      retry?.click()
      expect(actions).toEqual(['stop'])
    })

    it('renders the feedback of a refused Stop for this run only', async () => {
      await setup('attempt-1')
      store.stopFeedback.set({
        workflowId: 'wf-1',
        kind: 'not_authorized',
        message: 'You are not allowed to stop this run.',
      })
      const host = render()

      expect(host.querySelector('[data-stop-feedback]')?.textContent).toContain('You are not allowed to stop this run.')
    })

    it('does not render the Stop feedback of another run', async () => {
      await setup('attempt-1')
      store.stopFeedback.set({ workflowId: 'wf-other', kind: 'error', message: 'AgentOS is unreachable.' })
      const host = render()

      expect(host.querySelector('[data-stop-feedback]')).toBeNull()
    })

    it('offers "Retry stop" and says so when the last Stop was not confirmed', async () => {
      await setup('attempt-1')
      store.stopFeedback.set({
        workflowId: 'wf-1',
        kind: 'unconfirmed',
        message: 'Stop requested but the agents are not confirmed stopped. Retry stop.',
      })
      const host = render()

      expect(buttonByText(host, 'Retry stop')).not.toBeUndefined()
      expect(host.querySelector('[data-stop-feedback]')?.textContent).toContain('not confirmed stopped')
    })

    it('renders "Remove" and emits remove on a non-destroyed run', async () => {
      await setup()
      const actions: string[] = []
      fixture.componentInstance.action.subscribe((action) => actions.push(action))
      const host = render()

      const remove = buttonByText(host, 'Remove')
      expect(remove).not.toBeUndefined()
      remove?.click()
      expect(actions).toEqual(['remove'])
    })

    it('renders "Restore" and emits restore on a destroyed run only', async () => {
      await setup()
      const actions: string[] = []
      fixture.componentInstance.action.subscribe((action) => actions.push(action))
      const host = render({ ...run, status: 'destroyed' })

      expect(buttonByText(host, 'Remove')).toBeUndefined()
      expect(buttonByText(host, 'Stop')).toBeUndefined()
      const restore = buttonByText(host, 'Restore')
      expect(restore).not.toBeUndefined()
      restore?.click()
      expect(actions).toEqual(['restore'])
    })
  })
})
