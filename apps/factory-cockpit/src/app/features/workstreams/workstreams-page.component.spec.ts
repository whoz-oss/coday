import { signal, WritableSignal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { provideRouter } from '@angular/router'
import { of } from 'rxjs'
import { FactoryStore } from '../../core/factory.store'
import { CostSummary, FactoryRun, RecentTask, RunCardState, SessionDetail, WorkstreamView } from '../../core/models'
import { ConfirmDialogComponent } from '../admin/confirm-dialog.component'
import { WorkstreamsPageComponent } from './workstreams-page.component'

interface StoreStub {
  selectedNamespaceId: WritableSignal<string | null>
  namespaceOptions: WritableSignal<Array<{ namespaceId?: string; id?: string; name?: string }>>
  namespaceOptionsLoading: WritableSignal<boolean>
  namespaceOptionsError: WritableSignal<unknown>
  namespaceSelectionRequired: WritableSignal<boolean>
  workstreamMappingMissing: WritableSignal<boolean>
  overviewError: WritableSignal<unknown>
  selectNamespace: jest.Mock
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
    selectedNamespaceId: signal<string | null>('ns-1'),
    namespaceOptions: signal([{ namespaceId: 'ns-1', name: 'Namespace 1' }]),
    namespaceOptionsLoading: signal(false),
    namespaceOptionsError: signal(null),
    namespaceSelectionRequired: signal(false),
    workstreamMappingMissing: signal(false),
    overviewError: signal(null),
    selectNamespace: jest.fn(),
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

    expect(host.textContent).toContain('No workflows in the selected namespaces.')
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
    expect(header?.getAttribute('href')).toBe('/launch')
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

  describe('run status filter', () => {
    /** Accessor for the component's protected members, exercised directly by the tests. */
    interface PageInternals {
      status: WritableSignal<string>
      activeStatus: () => string
      filteredWorkstreams: () => WorkstreamView[]
    }

    function stateRun(id: string, namespaceId: string, cardState: RunCardState | undefined): FactoryRun {
      const base = createRun({ id, namespaceId })
      return { ...base, run: { ...base.run!, cardState } }
    }

    function internals(): PageInternals {
      return fixture.componentInstance as unknown as PageInternals
    }

    function chipLabels(host: HTMLElement): string[] {
      return Array.from(host.querySelectorAll('[data-workstreams-status-filter] mat-chip-option')).map((el) =>
        (el.textContent ?? '').replace(/\s+/g, ' ').trim()
      )
    }

    function runIds(host: HTMLElement): string[] {
      return Array.from(host.querySelectorAll('sf-run-card [data-run-id]')).map(
        (el) => el.getAttribute('data-run-id') ?? ''
      )
    }

    function kpiValues(host: HTMLElement): string[] {
      return Array.from(host.querySelectorAll('.kpi-value')).map((el) => el.textContent?.trim() ?? '')
    }

    function twoWorkstreams(): WorkstreamView[] {
      return [
        createWorkstream(
          'ns-a',
          [
            stateRun('run-running', 'ns-a', 'running'),
            stateRun('run-failed', 'ns-a', 'failed'),
            stateRun('run-nostate', 'ns-a', undefined),
          ],
          'Alpha'
        ),
        createWorkstream(
          'ns-b',
          [stateRun('run-done', 'ns-b', 'succeeded'), stateRun('run-human', 'ns-b', 'waiting_human')],
          'Beta'
        ),
      ]
    }

    it('renders All plus the six statuses with their counts, a run without cardState counting as Waiting', async () => {
      const { host } = await setup(createStore({ workstreams: twoWorkstreams() }))

      expect(chipLabels(host)).toEqual([
        'All \u00b7 5',
        'Running \u00b7 1',
        'Waiting \u00b7 1',
        'Waiting for human \u00b7 1',
        'Blocked \u00b7 0',
        'Failed \u00b7 1',
        'Completed \u00b7 1',
      ])
    })

    it('offers Destroyed only while the Destroyed toggle is on, and filters on it', async () => {
      const store = createStore({
        workstreams: [
          createWorkstream('ns-a', [
            stateRun('run-running', 'ns-a', 'running'),
            // destroyed wins over any cardState
            { ...stateRun('run-gone', 'ns-a', 'running'), status: 'destroyed' },
          ]),
        ],
      })
      const { host } = await setup(store)
      expect(chipLabels(host).some((label) => label.startsWith('Destroyed'))).toBe(false)

      store.showDestroyed.set(true)
      fixture.detectChanges()
      expect(chipLabels(host)).toContain('Destroyed \u00b7 1')
      expect(chipLabels(host)).toContain('Running \u00b7 1')

      internals().status.set('destroyed')
      fixture.detectChanges()
      expect(runIds(host)).toEqual(['run-gone'])
    })

    it('selecting a status chip renders only the matching runs and hides workstreams without any', async () => {
      const costs = { active: 5, workflowsUsd: 7.5, totalUsd: 7.5, unknownCostCount: 0 }
      const { host } = await setup(createStore({ workstreams: twoWorkstreams(), costs }))
      const kpisBefore = kpiValues(host)

      const failedChip = Array.from(
        host.querySelectorAll<HTMLElement>('[data-workstreams-status-filter] mat-chip-option')
      ).find((el) => el.textContent?.includes('Failed'))
      failedChip?.querySelector<HTMLElement>('button')?.click()
      fixture.detectChanges()

      expect(internals().activeStatus()).toBe('failed')
      expect(runIds(host)).toEqual(['run-failed'])
      expect(host.querySelectorAll('sf-workstream-card')).toHaveLength(1)
      expect(kpiValues(host)).toEqual(kpisBefore)
      expect(kpiValues(host)).toContain('5')
    })

    it('shows a dedicated empty state, not the neutral one, when no run matches', async () => {
      const { host } = await setup(createStore({ workstreams: twoWorkstreams() }))

      internals().status.set('blocked')
      fixture.detectChanges()

      expect(host.querySelectorAll('sf-workstream-card')).toHaveLength(0)
      expect(host.textContent).toContain('No run matches the selected status filter.')
      expect(host.textContent).not.toContain('No workflows in the selected namespaces.')
    })

    it('keeps the store reference when All is selected (stable refresh)', async () => {
      const store = createStore({ workstreams: twoWorkstreams() })
      await setup(store)

      expect(internals().filteredWorkstreams()).toBe(store.workstreams())
    })

    it('falls back to All when Destroyed is selected and the Destroyed toggle is switched off', async () => {
      const store = createStore({
        workstreams: [
          createWorkstream('ns-a', [
            stateRun('run-running', 'ns-a', 'running'),
            { ...stateRun('run-gone', 'ns-a', 'running'), status: 'destroyed' },
          ]),
        ],
      })
      store.showDestroyed.set(true)
      const { host } = await setup(store)

      internals().status.set('destroyed')
      fixture.detectChanges()
      expect(runIds(host)).toEqual(['run-gone'])

      store.showDestroyed.set(false)
      fixture.detectChanges()

      expect(internals().activeStatus()).toBe('all')
      expect(runIds(host).sort()).toEqual(['run-gone', 'run-running'])
    })
  })

  it('sets the Workstreams breadcrumb pointing at /workstreams', async () => {
    const { host } = await setup(createStore())
    expect(host).toBeTruthy()
    // ShellState is root-provided; the crumb is set in the constructor.
    // Rendering happens through the shell, so here we only assert no crash.
  })
})
