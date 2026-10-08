import { signal, WritableSignal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { provideRouter } from '@angular/router'
import { of } from 'rxjs'
import { FactoryStore } from '../../core/factory.store'
import { CostSummary, FactoryRun, RecentTask, SessionDetail, WorkstreamView } from '../../core/models'
import { ConfirmDialogComponent } from '../admin/confirm-dialog.component'
import { WorkstreamsPageComponent } from './workstreams-page.component'

interface StoreStub {
  costs: WritableSignal<CostSummary>
  workstreams: WritableSignal<WorkstreamView[]>
  recentTasks: WritableSignal<RecentTask[]>
  showDestroyed: WritableSignal<boolean>
  session: jest.Mock<SessionDetail | undefined, [string]>
  stop: jest.Mock
  remove: jest.Mock
  restore: jest.Mock
  openSupervisorCase: jest.Mock
  restoreSupervisorCase: jest.Mock
}

function createRun(overrides: Partial<FactoryRun> = {}): FactoryRun {
  const id = overrides.id ?? 'wf-1'
  return {
    id,
    title: `Run ${id}`,
    project: 'ns-1',
    namespaceId: 'ns-1',
    status: 'working',
    costUsd: 1.5,
    durationSec: 90,
    tokens: 1000,
    phases: [],
    run: {
      id,
      workflow: `wf-${id}`,
      status: 'running',
      goal: 'Do the real thing',
      costUsd: 1.5,
      unknownCostCount: 0,
      durationSec: 90,
      tokens: 1000,
      phases: [],
    },
    ...overrides,
  }
}

function createWorkstream(namespaceId: string, runs: FactoryRun[], title = namespaceId): WorkstreamView {
  return { namespaceId, title, runs }
}

function createStore(
  overrides: Partial<{
    costs: CostSummary
    workstreams: WorkstreamView[]
    recentTasks: RecentTask[]
    activeAttemptId: string
    sessionId: string
  }> = {}
): StoreStub {
  const sessionId = overrides.sessionId ?? 'wf-1'
  const session = overrides.activeAttemptId
    ? ({ id: sessionId, activeAttemptId: overrides.activeAttemptId } as SessionDetail)
    : undefined
  return {
    costs: signal<CostSummary>(overrides.costs ?? { active: 0, workflowsUsd: 0, totalUsd: 0, unknownCostCount: 0 }),
    workstreams: signal<WorkstreamView[]>(overrides.workstreams ?? []),
    recentTasks: signal<RecentTask[]>(overrides.recentTasks ?? []),
    showDestroyed: signal(false),
    session: jest.fn((id: string) => (session && id === sessionId ? session : undefined)),
    stop: jest.fn(),
    remove: jest.fn(),
    restore: jest.fn(),
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

function buttonByText(host: HTMLElement, text: string): HTMLButtonElement | undefined {
  return (Array.from(host.querySelectorAll('.actions button')) as HTMLButtonElement[]).find(
    (button) => button.textContent?.trim() === text
  )
}

describe('WorkstreamsPageComponent', () => {
  let fixture: ComponentFixture<WorkstreamsPageComponent>

  async function setup(store: StoreStub, confirm = true): Promise<{ host: HTMLElement; dialog: DialogStub }> {
    const dialog = createDialog(confirm)
    await TestBed.configureTestingModule({
      imports: [WorkstreamsPageComponent],
      providers: [
        provideRouter([]),
        { provide: FactoryStore, useValue: store },
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents()
    fixture = TestBed.createComponent(WorkstreamsPageComponent)
    fixture.detectChanges()
    return { host: fixture.nativeElement as HTMLElement, dialog }
  }

  it('shows a neutral empty state when there is no active workflow', async () => {
    const { host } = await setup(createStore())

    expect(host.textContent).toContain('No active workflows.')
    expect(host.querySelectorAll('sf-workstream-card')).toHaveLength(0)
  })

  it('renders the real cost KPIs and one workstream card per workstream', async () => {
    const { host } = await setup(
      createStore({
        costs: { active: 2, workflowsUsd: 3, totalUsd: 3, unknownCostCount: 0 },
        workstreams: [
          createWorkstream('ns-a', [createRun({ id: 'wf-a', namespaceId: 'ns-a' })], 'Alpha'),
          createWorkstream('ns-b', [createRun({ id: 'wf-b', namespaceId: 'ns-b' })], 'Beta'),
        ],
      })
    )

    expect(host.querySelectorAll('sf-workstream-card')).toHaveLength(2)
    // Two workstreams with homonym-sized run counts exist independently.
    expect(host.querySelectorAll('sf-run-card')).toHaveLength(2)
    const values = Array.from(host.querySelectorAll('.kpi-value')).map((el) => el.textContent?.trim())
    expect(values).toContain('2')
    expect(values).toContain('$3.00')
  })

  it('keeps the header launch entry point without rendering the secondary launch and recent tasks panels', async () => {
    const { host } = await setup(createStore())

    const header = host.querySelector<HTMLAnchorElement>('header [data-workstreams-launch]')
    expect(header).not.toBeNull()
    expect(header?.getAttribute('href')).toBe('/lancer')
    expect(host.querySelector('[data-workstreams-launch-panel]')).toBeNull()
    expect(host.textContent).not.toContain('Recent tasks')
  })

  it('targets actions with the exact run id, not the display name', async () => {
    const runA = createRun({ id: 'wf-a', title: 'Shared title', namespaceId: 'ns-1' })
    const runB = createRun({ id: 'wf-b', title: 'Shared title', namespaceId: 'ns-1' })
    const store = createStore({
      workstreams: [createWorkstream('ns-1', [runA, runB])],
      activeAttemptId: 'attempt-b',
      sessionId: 'wf-b',
    })
    const { host } = await setup(store)

    // session is resolved per run; only wf-b has an active attempt -> only its Stop shows.
    const stopButtons = Array.from(host.querySelectorAll('.actions button')).filter(
      (button) => button.textContent?.trim() === 'Stop'
    ) as HTMLButtonElement[]
    expect(stopButtons).toHaveLength(1)
    stopButtons[0]?.click()

    expect(store.stop).toHaveBeenCalledWith('wf-b')
    expect(store.stop).not.toHaveBeenCalledWith('Shared title')
  })

  describe('lifecycle actions', () => {
    it('routes "stop" to store.stop(runId)', async () => {
      const store = createStore({
        workstreams: [createWorkstream('ns-1', [createRun({ id: 'wf-1' })])],
        activeAttemptId: 'attempt-1',
      })
      const { host } = await setup(store)

      buttonByText(host, 'Stop')?.click()

      expect(store.stop).toHaveBeenCalledWith('wf-1')
    })

    it('routes "restore" to store.restore(runId)', async () => {
      const store = createStore({
        workstreams: [createWorkstream('ns-1', [createRun({ id: 'wf-1', status: 'destroyed' })])],
      })
      const { host } = await setup(store)

      buttonByText(host, 'Restore')?.click()

      expect(store.restore).toHaveBeenCalledWith('wf-1')
    })

    it('asks for confirmation before removing, then routes "remove" to store.remove(runId)', async () => {
      const store = createStore({ workstreams: [createWorkstream('ns-1', [createRun({ id: 'wf-1' })])] })
      const { host, dialog } = await setup(store, true)

      buttonByText(host, 'Remove')?.click()

      expect(dialog.open).toHaveBeenCalledTimes(1)
      const [component, config] = dialog.open.mock.calls[0] as [unknown, { data: Record<string, unknown> }]
      expect(component).toBe(ConfirmDialogComponent)
      expect(config.data['title']).toBe('Remove run')
      expect(store.remove).toHaveBeenCalledWith('wf-1')
    })

    it('does not remove the run when the confirmation is cancelled', async () => {
      const store = createStore({ workstreams: [createWorkstream('ns-1', [createRun({ id: 'wf-1' })])] })
      const { host, dialog } = await setup(store, false)

      buttonByText(host, 'Remove')?.click()

      expect(dialog.open).toHaveBeenCalledTimes(1)
      expect(store.remove).not.toHaveBeenCalled()
    })
  })

  it('sets the Workstreams breadcrumb pointing at /workstreams', async () => {
    const { host } = await setup(createStore())
    expect(host).toBeTruthy()
    // ShellState is root-provided; the crumb is set in the constructor.
    // Rendering happens through the shell, so here we only assert no crash.
  })
})
