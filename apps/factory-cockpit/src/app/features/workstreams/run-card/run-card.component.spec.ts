import { provideRouter } from '@angular/router'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { FactoryStore } from '../../../core/factory.store'
import { FactoryRun, SessionDetail } from '../../../core/models'
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

interface StoreStub {
  session: jest.Mock<SessionDetail | undefined, [string]>
}

function createStore(activeAttemptId?: string): StoreStub {
  const session: SessionDetail | undefined = activeAttemptId
    ? ({ id: 'wf-1', activeAttemptId } as SessionDetail)
    : undefined
  return { session: jest.fn().mockReturnValue(session) }
}

describe('RunCardComponent', () => {
  let fixture: ComponentFixture<RunCardComponent>
  let store: StoreStub

  async function setup(activeAttemptId?: string): Promise<void> {
    store = createStore(activeAttemptId)
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

  describe('failure reason', () => {
    it('shows the failed chip and the pre-check message of a refused run', async () => {
      await setup()
      const reason = "Required agent(s) reviewer are not available in namespace 'ns-1'. Remedy: Publish the agents."
      const host = render({
        ...run,
        status: 'failed',
        run: { ...(run.run as NonNullable<FactoryRun['run']>), status: 'failed', cardState: 'failed', failureReason: reason },
      })

      expect(host.textContent).toContain('failed')
      const line = host.querySelector('.failure-reason')
      expect(line?.textContent?.trim()).toBe(reason)
      expect(line?.getAttribute('title')).toBe(reason)
    })

    it('renders no failure reason line when none is set', async () => {
      await setup()
      const host = render()

      expect(host.querySelector('.failure-reason')).toBeNull()
    })
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
