import { signal, WritableSignal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { provideRouter } from '@angular/router'
import { of } from 'rxjs'
import { FactoryStore } from '../../core/factory.store'
import { CostSummary, RecentTask, Sandbox, SessionDetail } from '../../core/models'
import { ConfirmDialogComponent } from '../admin/confirm-dialog.component'
import { SandboxesPageComponent } from './sandboxes-page.component'

interface StoreStub {
  costs: WritableSignal<CostSummary>
  visibleSandboxes: WritableSignal<Sandbox[]>
  recentTasks: WritableSignal<RecentTask[]>
  showDestroyed: WritableSignal<boolean>
  session: jest.Mock<SessionDetail | undefined, [string]>
  stop: jest.Mock
  remove: jest.Mock
  restore: jest.Mock
  openSupervisorCase: jest.Mock
  restoreSupervisorCase: jest.Mock
}

function createStore(
  overrides: Partial<{
    costs: CostSummary
    sandboxes: Sandbox[]
    recentTasks: RecentTask[]
    activeAttemptId: string
  }> = {}
): StoreStub {
  const session = overrides.activeAttemptId
    ? ({ id: 'wf-1', activeAttemptId: overrides.activeAttemptId } as SessionDetail)
    : undefined
  return {
    costs: signal<CostSummary>(overrides.costs ?? { active: 0, workflowsUsd: 0, totalUsd: 0, unknownCostCount: 0 }),
    visibleSandboxes: signal<Sandbox[]>(overrides.sandboxes ?? []),
    recentTasks: signal<RecentTask[]>(overrides.recentTasks ?? []),
    showDestroyed: signal(false),
    session: jest.fn().mockReturnValue(session),
    stop: jest.fn(),
    remove: jest.fn(),
    restore: jest.fn(),
    // SandboxCardComponent delegates supervisor case creation to the store.
    // Provide a no-op stub so the page tests never perform real HTTP calls.
    openSupervisorCase: jest.fn().mockReturnValue(of(null)),
    restoreSupervisorCase: jest.fn().mockReturnValue(undefined),
  }
}

interface DialogStub {
  open: jest.Mock
}

function createDialog(confirm: boolean): DialogStub {
  return { open: jest.fn().mockReturnValue({ afterClosed: () => of(confirm) }) }
}

const sandbox: Sandbox = {
  name: 'wf-1',
  project: 'ns-1',
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

function buttonByText(host: HTMLElement, text: string): HTMLButtonElement | undefined {
  return (Array.from(host.querySelectorAll('.actions button')) as HTMLButtonElement[]).find(
    (button) => button.textContent?.trim() === text
  )
}

describe('SandboxesPageComponent', () => {
  let fixture: ComponentFixture<SandboxesPageComponent>

  async function setup(store: StoreStub, confirm = true): Promise<{ host: HTMLElement; dialog: DialogStub }> {
    const dialog = createDialog(confirm)
    await TestBed.configureTestingModule({
      imports: [SandboxesPageComponent],
      providers: [
        provideRouter([]),
        { provide: FactoryStore, useValue: store },
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents()
    fixture = TestBed.createComponent(SandboxesPageComponent)
    fixture.detectChanges()
    return { host: fixture.nativeElement as HTMLElement, dialog }
  }

  it('shows a neutral empty state when there is no active workflow', async () => {
    const { host } = await setup(createStore())

    expect(host.textContent).toContain('No active workflows.')
    expect(host.querySelectorAll('sf-sandbox-card')).toHaveLength(0)
  })

  it('renders the real cost KPIs and one card per derived sandbox', async () => {
    const { host } = await setup(
      createStore({
        costs: { active: 1, workflowsUsd: 1.5, totalUsd: 1.5, unknownCostCount: 0 },
        sandboxes: [sandbox],
      })
    )

    expect(host.querySelectorAll('sf-sandbox-card')).toHaveLength(1)
    const values = Array.from(host.querySelectorAll('.kpi-value')).map((el) => el.textContent?.trim())
    expect(values).toContain('1')
    expect(values).toContain('$1.50')
    // The fabricated destroyed-sandbox KPI is gone.
    expect(host.textContent).not.toContain('Destroyed sandboxes')
  })

  it('exposes a launch entry point to the /lancer screen', async () => {
    const { host } = await setup(createStore())

    const header = host.querySelector<HTMLAnchorElement>('header [data-sandboxes-launch]')
    expect(header).not.toBeNull()
    expect(header?.getAttribute('href')).toBe('/lancer')
    expect(header?.textContent).toContain('Launch a run')

    const panel = host.querySelector<HTMLAnchorElement>('[data-sandboxes-launch-panel]')
    expect(panel).not.toBeNull()
    expect(panel?.getAttribute('href')).toBe('/lancer')
  })

  describe('lifecycle actions', () => {
    it('routes "stop" to store.stop(workflowId)', async () => {
      const store = createStore({ sandboxes: [sandbox], activeAttemptId: 'attempt-1' })
      const { host } = await setup(store)

      buttonByText(host, 'Stop')?.click()

      expect(store.stop).toHaveBeenCalledWith('wf-1')
    })

    it('routes "restore" to store.restore(workflowId)', async () => {
      const store = createStore({ sandboxes: [{ ...sandbox, status: 'destroyed' }] })
      const { host } = await setup(store)

      buttonByText(host, 'Restore')?.click()

      expect(store.restore).toHaveBeenCalledWith('wf-1')
    })

    it('asks for confirmation before removing, then routes "remove" to store.remove(workflowId)', async () => {
      const store = createStore({ sandboxes: [sandbox] })
      const { host, dialog } = await setup(store, true)

      buttonByText(host, 'Remove')?.click()

      expect(dialog.open).toHaveBeenCalledTimes(1)
      const [component, config] = dialog.open.mock.calls[0] as [unknown, { data: Record<string, unknown> }]
      expect(component).toBe(ConfirmDialogComponent)
      expect(config.data['title']).toBe('Remove sandbox')
      expect(config.data['message']).toContain('recoverable via the destroyed sandboxes toggle')
      expect(config.data['confirmLabel']).toBe('Remove')
      expect(config.data['destructive']).toBe(true)
      expect(store.remove).toHaveBeenCalledWith('wf-1')
    })

    it('does not remove the sandbox when the confirmation is cancelled', async () => {
      const store = createStore({ sandboxes: [sandbox] })
      const { host, dialog } = await setup(store, false)

      buttonByText(host, 'Remove')?.click()

      expect(dialog.open).toHaveBeenCalledTimes(1)
      expect(store.remove).not.toHaveBeenCalled()
    })
  })
})
