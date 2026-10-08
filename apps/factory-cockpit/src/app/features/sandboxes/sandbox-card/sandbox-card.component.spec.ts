import { provideRouter } from '@angular/router'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { of, throwError, Subject } from 'rxjs'
import { FactoryStore } from '../../../core/factory.store'
import { AgentOsApiError } from '../../../core/agentos-api.service'
import { SupervisorCaseResult, Sandbox, SessionDetail } from '../../../core/models'
import { SandboxCardComponent } from './sandbox-card.component'

const sandbox: Sandbox = {
  name: 'wf-1',
  project: 'ns-1',
  namespace: 'ns-uuid-1',
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

describe('SandboxCardComponent', () => {
  let fixture: ComponentFixture<SandboxCardComponent>
  let store: StoreStub

  async function setup(
    activeAttemptId?: string,
    supervisorResult$?: ReturnType<typeof of>,
    restoredCase?: SupervisorCaseResult
  ): Promise<void> {
    store = createStore(activeAttemptId, supervisorResult$, restoredCase)
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
    // UsdPipe with 4 digits -> $1.5000 (unknownCostCount = 0, so no >= prefix).
    expect(host.textContent).toContain('$1.5000')
  })

  it('displays the creation date when createdAt is present', async () => {
    await setup()
    const host = render({ ...sandbox, createdAt: '2026-09-30T16:00:00.000Z' })

    const el = host.querySelector('.created-at')
    expect(el).not.toBeNull()
    // DatePipe formats date and time parts; exact locale output is tested indirectly.
    expect(el?.textContent).toContain('2026')
    expect(el?.querySelector('time')?.getAttribute('dateTime')).toBe('2026-09-30T16:00:00.000Z')
  })

  it('omits the creation date element when createdAt is absent', async () => {
    await setup()
    const host = render({ ...sandbox, createdAt: undefined })

    expect(host.querySelector('.created-at')).toBeNull()
  })

  it('offers a restorable Remove action, never an irreversible destroy', async () => {
    await setup()
    const host = render()

    expect(host.textContent).not.toContain('Destroy')
    expect(buttonByText(host, 'Remove')).not.toBeUndefined()
  })

  it('does not render the fabricated roster/wave fields', async () => {
    await setup()
    const host = render()

    expect(host.textContent).not.toContain('roster')
    expect(host.textContent).not.toContain('vague')
  })

  it('shows the neutral idle status for an idle workflow', async () => {
    await setup()
    const host = render({ ...sandbox, status: 'idle' })
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
      const host = render({ ...sandbox, namespace: undefined })
      const btn = buttonByText(host, 'Ask supervisor')
      expect(btn).not.toBeUndefined()
      expect(btn?.disabled).toBe(true)
    })

    it('calls store.openSupervisorCase on click and transitions to done state', async () => {
      const openSpy = jest.spyOn(window, 'open').mockReturnValue(null)
      await setup()
      const host = render()

      buttonByText(host, 'Ask supervisor')?.click()

      expect(store.openSupervisorCase).toHaveBeenCalledWith(sandbox)
      // After success the button is replaced by a link.
      fixture.detectChanges()
      const link = host.querySelector<HTMLAnchorElement>('a[href*="heimdall-case-1"]')
      expect(link).not.toBeNull()
      expect(link?.textContent).toContain('View supervisor case')
      openSpy.mockRestore()
    })

    it('navigates the pre-opened window to the case URL on success', async () => {
      // opener must be writable so the component can null it out.
      const mockWindow = { closed: false, location: { href: '' }, opener: {} } as unknown as Window
      const openSpy = jest.spyOn(window, 'open').mockReturnValue(mockWindow)
      await setup()
      const host = render()

      buttonByText(host, 'Ask supervisor')?.click()

      // window.open must NOT carry 'noopener': that flag causes the browser to
      // return null, making navigation impossible. Isolation is achieved by
      // setting opener = null on the handle immediately after opening.
      expect(openSpy).toHaveBeenCalledWith('', '_blank')
      expect(mockWindow.opener).toBeNull()
      expect(mockWindow.location.href).toBe('/agentos/home?ns=ns-uuid-1&case=heimdall-case-1')
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

      // Blank window must be closed.
      expect((mockWindow as unknown as { close: jest.Mock }).close).toHaveBeenCalled()
      // Inline error message must be visible (not just a title attribute).
      const errorEl = host.querySelector('.supervisor-error')
      expect(errorEl).not.toBeNull()
      expect(errorEl?.textContent).toContain('Internal error')
      // Button is back to clickable state for retry.
      expect(buttonByText(host, 'Ask supervisor')?.disabled).toBe(false)
      openSpy.mockRestore()
    })

    it('does not call openSupervisorCase a second time while loading (double-click guard)', async () => {
      const subject = new Subject<SupervisorCaseResult>()
      await setup(undefined, subject.asObservable() as unknown as ReturnType<typeof of>)
      jest.spyOn(window, 'open').mockReturnValue(null)
      const host = render()

      buttonByText(host, 'Ask supervisor')?.click()
      fixture.detectChanges()
      // Button is now disabled in loading state.
      buttonByText(host, 'Ask supervisor')?.click()

      expect(store.openSupervisorCase).toHaveBeenCalledTimes(1)
    })

    it('does not create a duplicate case once in done state', async () => {
      const openSpy = jest.spyOn(window, 'open').mockReturnValue(null)
      await setup()
      const host = render()

      buttonByText(host, 'Ask supervisor')?.click()
      fixture.detectChanges()
      // After success the button is gone; only the link remains.
      expect(buttonByText(host, 'Ask supervisor')).toBeUndefined()
      expect(store.openSupervisorCase).toHaveBeenCalledTimes(1)
      openSpy.mockRestore()
    })

    it('restores a previously created supervisor case on init without calling openSupervisorCase', async () => {
      const restored: SupervisorCaseResult = {
        caseId: 'restored-case-1',
        namespaceId: 'ns-uuid-1',
        agentOsUrl: '/agentos/home?ns=ns-uuid-1&case=restored-case-1',
      }
      await setup(undefined, undefined, restored)
      const host = render()

      // The link must be visible immediately -- no click needed.
      const link = host.querySelector<HTMLAnchorElement>('a[href*="restored-case-1"]')
      expect(link).not.toBeNull()
      expect(link?.textContent).toContain('View supervisor case')
      // openSupervisorCase must never be called on restore.
      expect(store.openSupervisorCase).not.toHaveBeenCalled()
    })

    it('does not call openSupervisorCase when a restored case is already in done state (double-click guard after refresh)', async () => {
      const restored: SupervisorCaseResult = {
        caseId: 'restored-case-2',
        namespaceId: 'ns-uuid-1',
        agentOsUrl: '/agentos/home?ns=ns-uuid-1&case=restored-case-2',
      }
      await setup(undefined, undefined, restored)
      const host = render()

      // The supervisor button must be replaced by the link -- clicking is impossible.
      expect(buttonByText(host, 'Ask supervisor')).toBeUndefined()
      expect(store.openSupervisorCase).not.toHaveBeenCalled()
    })
  })

  describe('Conversation link', () => {
    it('renders Conversation as an external link when controllerCaseId is present', async () => {
      await setup()
      const host = render({ ...sandbox, namespace: 'ns-1', controllerCaseId: 'case-42' })
      const link = host.querySelector<HTMLAnchorElement>('a[href*="agentos"]')
      expect(link).not.toBeNull()
      expect(link?.getAttribute('href')).toBe('/agentos/home?ns=ns-1&case=case-42')
      expect(link?.textContent?.trim()).toBe('Coday')
    })

    it('renders Conversation as a disabled button when controllerCaseId is absent', async () => {
      await setup()
      const host = render({ ...sandbox, controllerCaseId: undefined })
      const disabledBtn = Array.from(host.querySelectorAll<HTMLButtonElement>('.actions button[disabled]')).find(
        (button) => button.textContent?.trim() === 'Conversation'
      )
      // No Conversation link when controllerCaseId is absent.
      expect(disabledBtn).not.toBeUndefined()
    })

    it('omits the ns param when no namespace is known', async () => {
      await setup()
      const host = render({ ...sandbox, namespace: undefined, controllerCaseId: 'case-7' })
      const link = host.querySelector<HTMLAnchorElement>('a[href*="agentos"][href*="case-7"]')
      expect(link?.getAttribute('href')).toBe('/agentos/home?case=case-7')
    })
  })

  describe('lifecycle actions', () => {
    it('renders "Stop" and emits stop when a running workflow has a resolvable active attempt', async () => {
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

    it('hides "Stop" for a non-working workflow even with a resolvable attempt', async () => {
      await setup('attempt-1')
      const host = render({ ...sandbox, status: 'idle' })

      expect(buttonByText(host, 'Stop')).toBeUndefined()
    })

    it('renders "Remove" and emits remove on a non-destroyed workflow', async () => {
      await setup()
      const actions: string[] = []
      fixture.componentInstance.action.subscribe((action) => actions.push(action))
      const host = render()

      const remove = buttonByText(host, 'Remove')
      expect(remove).not.toBeUndefined()
      remove?.click()
      expect(actions).toEqual(['remove'])
    })

    it('renders "Restore" and emits restore on a destroyed workflow only', async () => {
      await setup()
      const actions: string[] = []
      fixture.componentInstance.action.subscribe((action) => actions.push(action))
      const host = render({ ...sandbox, status: 'destroyed' })

      expect(buttonByText(host, 'Remove')).toBeUndefined()
      expect(buttonByText(host, 'Stop')).toBeUndefined()
      const restore = buttonByText(host, 'Restore')
      expect(restore).not.toBeUndefined()
      restore?.click()
      expect(actions).toEqual(['restore'])
    })
  })
})
