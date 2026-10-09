import { provideRouter } from '@angular/router'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { of, throwError, Subject } from 'rxjs'
import { FactoryStore } from '../../../core/factory.store'
import { AgentOsApiError } from '../../../core/agentos-api.service'
import { FactoryRun, SupervisorCaseResult, SessionDetail } from '../../../core/models'
import { RunCardComponent } from './run-card.component'

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
}

function createStore(
  activeAttemptId?: string,
  supervisorResult$?: ReturnType<typeof of>,
  restoredCase?: SupervisorCaseResult
): StoreStub {
  const session: SessionDetail | undefined = activeAttemptId
    ? ({ id: 'wf-1', activeAttemptId } as SessionDetail)
    : undefined
  return {
    session: jest.fn().mockReturnValue(session),
    openSupervisorCase: jest.fn().mockReturnValue(supervisorResult$ ?? of(supervisorResult)),
    restoreSupervisorCase: jest.fn().mockReturnValue(restoredCase ?? undefined),
  }
}

describe('RunCardComponent', () => {
  let fixture: ComponentFixture<RunCardComponent>
  let store: StoreStub

  async function setup(
    activeAttemptId?: string,
    supervisorResult$?: ReturnType<typeof of>,
    restoredCase?: SupervisorCaseResult
  ): Promise<void> {
    store = createStore(activeAttemptId, supervisorResult$, restoredCase)
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

  describe('Ask supervisor', () => {
    it('renders the supervisor button when a namespace is known', async () => {
      await setup()
      const host = render()
      const btn = buttonByText(host, 'Ask supervisor')
      expect(btn).not.toBeUndefined()
      expect(btn?.disabled).toBe(false)
    })

    it('renders the supervisor button as disabled when namespace is unknown', async () => {
      await setup()
      const host = render({ ...run, namespaceId: undefined })
      const btn = buttonByText(host, 'Ask supervisor')
      expect(btn).not.toBeUndefined()
      expect(btn?.disabled).toBe(true)
    })

    it('calls store.openSupervisorCase(run) on click and transitions to done state', async () => {
      const openSpy = jest.spyOn(window, 'open').mockReturnValue(null)
      await setup()
      const host = render()

      buttonByText(host, 'Ask supervisor')?.click()

      expect(store.openSupervisorCase).toHaveBeenCalledWith(run)
      fixture.detectChanges()
      const link = host.querySelector<HTMLAnchorElement>('a[href*="heimdall-case-1"]')
      expect(link).not.toBeNull()
      expect(link?.textContent).toContain('View supervisor case')
      openSpy.mockRestore()
    })

    it('closes the blank window and shows an inline error when openSupervisorCase fails', async () => {
      const mockWindow = { closed: false, close: jest.fn() } as unknown as Window
      const openSpy = jest.spyOn(window, 'open').mockReturnValue(mockWindow)
      const err: AgentOsApiError = { code: 'HTTP_500', message: 'Internal error', status: 500, raw: null }
      await setup(undefined, throwError(() => err) as ReturnType<typeof of>)
      const host = render()

      buttonByText(host, 'Ask supervisor')?.click()
      fixture.detectChanges()

      expect((mockWindow as unknown as { close: jest.Mock }).close).toHaveBeenCalled()
      const errorEl = host.querySelector('.supervisor-error')
      expect(errorEl).not.toBeNull()
      expect(errorEl?.textContent).toContain('Internal error')
      openSpy.mockRestore()
    })

    it('does not call openSupervisorCase a second time while loading (double-click guard)', async () => {
      const subject = new Subject<SupervisorCaseResult>()
      await setup(undefined, subject.asObservable() as unknown as ReturnType<typeof of>)
      jest.spyOn(window, 'open').mockReturnValue(null)
      const host = render()

      buttonByText(host, 'Ask supervisor')?.click()
      fixture.detectChanges()
      buttonByText(host, 'Ask supervisor')?.click()

      expect(store.openSupervisorCase).toHaveBeenCalledTimes(1)
    })

    it('restores a previously created supervisor case on init without calling openSupervisorCase', async () => {
      const restored: SupervisorCaseResult = {
        caseId: 'restored-case-1',
        namespaceId: 'ns-uuid-1',
        agentOsUrl: '/agentos/home?ns=ns-uuid-1&case=restored-case-1',
      }
      await setup(undefined, undefined, restored)
      const host = render()

      const link = host.querySelector<HTMLAnchorElement>('a[href*="restored-case-1"]')
      expect(link).not.toBeNull()
      expect(link?.textContent).toContain('View supervisor case')
      expect(store.openSupervisorCase).not.toHaveBeenCalled()
    })
  })

  describe('Conversation link', () => {
    it('renders Conversation as an external link when controllerCaseId is present', async () => {
      await setup()
      const host = render({ ...run, namespaceId: 'ns-1', controllerCaseId: 'case-42' })
      const link = host.querySelector<HTMLAnchorElement>('a[href*="agentos"]')
      expect(link).not.toBeNull()
      expect(link?.getAttribute('href')).toBe('/agentos/home?ns=ns-1&case=case-42')
      expect(link?.textContent?.trim()).toBe('Conversation')
    })

    it('renders Conversation as a disabled button when controllerCaseId is absent', async () => {
      await setup()
      const host = render({ ...run, controllerCaseId: undefined })
      const disabledBtn = Array.from(host.querySelectorAll<HTMLButtonElement>('.actions button[disabled]')).find(
        (button) => button.textContent?.trim() === 'Conversation'
      )
      expect(disabledBtn).not.toBeUndefined()
    })

    it('omits the ns param when no namespace is known', async () => {
      await setup()
      const host = render({ ...run, namespaceId: undefined, controllerCaseId: 'case-7' })
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

    it('hides "Stop" for a non-working run even with a resolvable attempt', async () => {
      await setup('attempt-1')
      const host = render({ ...run, status: 'idle' })

      expect(buttonByText(host, 'Stop')).toBeUndefined()
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
