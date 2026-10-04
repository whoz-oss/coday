import { WritableSignal, signal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { MatTableDataSource } from '@angular/material/table'
import { provideNoopAnimations } from '@angular/platform-browser/animations'
import { provideRouter } from '@angular/router'
import { FactoryStore } from '../../core/factory.store'
import { Sandbox } from '../../core/models'
import { HistoryPageComponent } from './history-page.component'

type Row = Sandbox & { cost: number }
type StatusFilter = 'all' | 'working' | 'destroyed'

/** Accessor for the component's protected members, exercised directly by the tests. */
interface HistoryInternals {
  query: WritableSignal<string>
  status: WritableSignal<StatusFilter>
  rows: () => Row[]
  filtered: () => Row[]
  counts: () => { all: number; working: number; destroyed: number }
  totalCost: () => number
  costBars: () => Array<{ name: string; cost: number; active: boolean; pct: number }>
  dataSource: MatTableDataSource<Row>
  exportCsv: () => void
}

interface StoreStub {
  sandboxes: WritableSignal<Sandbox[]>
}

const activeSandbox: Sandbox = {
  name: 'wf-active',
  project: 'coday',
  branch: 'feature/alpha',
  status: 'working',
  workflowType: 'adw_simple_sdlc',
  run: {
    id: 'wf-active',
    workflow: 'adw_simple_sdlc',
    status: 'running',
    goal: 'Ship the active run',
    costUsd: 3,
    durationSec: 90,
    tokens: 1000,
    phases: [],
  },
}

const destroyedWithRun: Sandbox = {
  name: 'wf-done',
  project: 'coday',
  branch: 'feature/beta',
  status: 'destroyed',
  workflowType: 'adw_full',
  run: {
    id: 'wf-done',
    workflow: 'adw_full',
    status: 'succeeded',
    goal: 'Completed run',
    costUsd: 2,
    durationSec: 60,
    tokens: 800,
    phases: [],
  },
}

const destroyedWithoutRun: Sandbox = {
  name: 'wf-teardown',
  project: 'coday',
  status: 'destroyed',
  finalCostUsd: 5,
}

function createStore(sandboxes: Sandbox[] = [activeSandbox, destroyedWithRun, destroyedWithoutRun]): StoreStub {
  return { sandboxes: signal<Sandbox[]>([...sandboxes]) }
}

function internals(fixture: ComponentFixture<HistoryPageComponent>): HistoryInternals {
  return fixture.componentInstance as unknown as HistoryInternals
}

function tableNames(host: HTMLElement): string[] {
  return Array.from(host.querySelectorAll('tr[mat-row] .name')).map((el) => el.textContent?.trim() ?? '')
}

describe('HistoryPageComponent', () => {
  async function setup(
    sandboxes: Sandbox[] = [activeSandbox, destroyedWithRun, destroyedWithoutRun]
  ): Promise<{ host: HTMLElement; fixture: ComponentFixture<HistoryPageComponent>; c: HistoryInternals }> {
    const store = createStore(sandboxes)
    await TestBed.configureTestingModule({
      imports: [HistoryPageComponent],
      providers: [provideRouter([]), provideNoopAnimations(), { provide: FactoryStore, useValue: store }],
    }).compileComponents()
    const fixture = TestBed.createComponent(HistoryPageComponent)
    fixture.detectChanges()
    return { host: fixture.nativeElement as HTMLElement, fixture, c: internals(fixture) }
  }

  it('creates the component and renders the completed/stopped/destroyed header', async () => {
    const { host } = await setup()

    expect(host.querySelector('h1')?.textContent).toContain('Historique')
    expect(host.textContent).toContain('Runs terminés, arrêtés et détruits, avec leur coût et phases')
  })

  it('renders the columns and every sandbox row from store.sandboxes()', async () => {
    const { host, c } = await setup()

    const headers = Array.from(host.querySelectorAll('th')).map((el) => el.textContent?.trim())
    expect(headers).toEqual(expect.arrayContaining(['Sandbox', 'Statut', 'Coût']))

    expect(c.rows()).toHaveLength(3)
    // The table defaults to sorting by cost descending.
    expect(tableNames(host)).toEqual(['wf-teardown', 'wf-active', 'wf-done'])
  })

  it('labels the status filters and counts for all/working/destroyed runs', async () => {
    const { host, c } = await setup()

    const filters = host.querySelector('mat-chip-listbox')?.textContent ?? ''
    expect(filters).toContain('Tous · 3')
    expect(filters).toContain('En cours · 1')
    expect(filters).toContain('Terminés / Arrêtés · 2')
    expect(c.counts()).toEqual({ all: 3, working: 1, destroyed: 2 })
  })

  it('shows only active runs for the working status filter', async () => {
    const { host, fixture, c } = await setup()

    c.status.set('working')
    fixture.detectChanges()

    expect(tableNames(host)).toEqual(['wf-active'])
    expect(c.filtered().map((r) => r.name)).toEqual(['wf-active'])
  })

  it('shows completed/stopped/destroyed runs for the destroyed status filter', async () => {
    const { host, fixture, c } = await setup()

    c.status.set('destroyed')
    fixture.detectChanges()

    expect(tableNames(host)).toEqual(['wf-teardown', 'wf-done'])
    expect(c.filtered().every((r) => r.status === 'destroyed')).toBe(true)
  })

  it('keeps all runs when the status filter is all', async () => {
    const { host, fixture, c } = await setup()

    c.status.set('destroyed')
    fixture.detectChanges()
    c.status.set('all')
    fixture.detectChanges()

    expect(tableNames(host)).toEqual(['wf-teardown', 'wf-active', 'wf-done'])
  })

  it('renders the destroyed status chip as "terminé / arrêté" and active as "en cours"', async () => {
    const { host } = await setup()

    const chips = Array.from(host.querySelectorAll('tr[mat-row] sf-status-chip')).map((el) => {
      const clone = el.cloneNode(true) as HTMLElement
      clone.querySelectorAll('mat-icon').forEach((icon) => icon.remove())
      return clone.textContent?.trim()
    })
    // Rows are sorted by cost descending: teardown, active, done.
    expect(chips).toEqual(['terminé / arrêté', 'en cours', 'terminé / arrêté'])
  })

  it('filters by query across name, branch, run id and workflow', async () => {
    const { host, fixture, c } = await setup()

    c.query.set('feature/beta')
    fixture.detectChanges()
    expect(tableNames(host)).toEqual(['wf-done'])

    c.query.set('wf-teardown')
    fixture.detectChanges()
    expect(c.filtered().map((r) => r.name)).toEqual(['wf-teardown'])

    c.query.set('adw_simple_sdlc')
    fixture.detectChanges()
    expect(c.filtered().map((r) => r.name)).toEqual(['wf-active'])

    c.query.set('no-match')
    fixture.detectChanges()
    expect(c.filtered()).toHaveLength(0)
  })

  it('combines the search query with the status filter', async () => {
    const { c } = await setup()

    c.status.set('destroyed')
    c.query.set('beta')

    expect(c.filtered().map((r) => r.name)).toEqual(['wf-done'])
  })

  it('computes the total cost from run cost with a finalCostUsd fallback', async () => {
    const { c } = await setup()

    expect(c.rows().map((r) => r.cost)).toEqual([3, 2, 5])
    expect(c.totalCost()).toBe(10)
  })

  it('builds cost bars sorted from the most expensive, with relative percentages', async () => {
    const { c } = await setup()

    const bars = c.costBars()
    expect(bars.map((b) => b.name)).toEqual(['wf-teardown', 'wf-active', 'wf-done'])
    expect(bars.map((b) => b.pct)).toEqual([100, 60, 40])
    expect(bars.map((b) => b.active)).toEqual([false, true, false])
  })

  it('wires the table sort and paginator', async () => {
    const { fixture, c } = await setup()
    fixture.detectChanges()

    expect(c.dataSource.sort).not.toBeNull()
    expect(c.dataSource.sort?.active).toBe('cost')
    expect(c.dataSource.sort?.direction).toBe('desc')
    expect(c.dataSource.paginator).not.toBeNull()
  })

  it('triggers a CSV download for the filtered rows', async () => {
    let csvContent = ''
    const blobSpy = jest.spyOn(globalThis, 'Blob').mockImplementation((parts: BlobPart[]) => {
      csvContent = String(parts[0])
      return {} as Blob
    })
    const createObjectURL = jest.fn(() => 'blob:history')
    const revokeObjectURL = jest.fn()
    const originalCreate = URL.createObjectURL
    const originalRevoke = URL.revokeObjectURL
    URL.createObjectURL = createObjectURL as unknown as typeof URL.createObjectURL
    URL.revokeObjectURL = revokeObjectURL as unknown as typeof URL.revokeObjectURL
    const clickSpy = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    try {
      const { c } = await setup()
      c.exportCsv()

      expect(createObjectURL).toHaveBeenCalledTimes(1)
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:history')
      expect(csvContent).toContain('sandbox;projet;branche;statut;run;workflow;cout_usd')
      expect(csvContent).toContain('wf-active;coday;feature/alpha;working;wf-active;adw_simple_sdlc;3.0000')
      expect(csvContent).toContain('wf-teardown;coday;;destroyed;;;5.0000')
    } finally {
      URL.createObjectURL = originalCreate
      URL.revokeObjectURL = originalRevoke
      clickSpy.mockRestore()
      blobSpy.mockRestore()
    }
  })
})
