import { signal, WritableSignal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideRouter } from '@angular/router'
import { FactoryStore } from '../../core/factory.store'
import { CostSummary, RecentTask, Sandbox } from '../../core/models'
import { SandboxesPageComponent } from './sandboxes-page.component'

interface StoreStub {
  costs: WritableSignal<CostSummary>
  visibleSandboxes: WritableSignal<Sandbox[]>
  recentTasks: WritableSignal<RecentTask[]>
  showDestroyed: WritableSignal<boolean>
}

function createStore(
  overrides: Partial<{ costs: CostSummary; sandboxes: Sandbox[]; recentTasks: RecentTask[] }> = {}
): StoreStub {
  return {
    costs: signal<CostSummary>(overrides.costs ?? { active: 0, workflowsUsd: 0, totalUsd: 0, unknownCostCount: 0 }),
    visibleSandboxes: signal<Sandbox[]>(overrides.sandboxes ?? []),
    recentTasks: signal<RecentTask[]>(overrides.recentTasks ?? []),
    showDestroyed: signal(false),
  }
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

describe('SandboxesPageComponent', () => {
  let fixture: ComponentFixture<SandboxesPageComponent>

  async function setup(store: StoreStub): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [SandboxesPageComponent],
      providers: [provideRouter([]), { provide: FactoryStore, useValue: store }],
    }).compileComponents()
    fixture = TestBed.createComponent(SandboxesPageComponent)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  it('shows a neutral empty state when there is no active workflow', async () => {
    const host = await setup(createStore())

    expect(host.textContent).toContain('Aucun workflow actif.')
    expect(host.querySelectorAll('sf-sandbox-card')).toHaveLength(0)
  })

  it('renders the real cost KPIs and one card per derived sandbox', async () => {
    const host = await setup(
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
    expect(host.textContent).not.toContain('Sandboxes détruites')
  })

  it('exposes a launch entry point to the /lancer screen', async () => {
    const host = await setup(createStore())

    const header = host.querySelector<HTMLAnchorElement>('header [data-sandboxes-launch]')
    expect(header).not.toBeNull()
    expect(header?.getAttribute('href')).toBe('/lancer')
    expect(header?.textContent).toContain('Lancer un run')

    const panel = host.querySelector<HTMLAnchorElement>('[data-sandboxes-launch-panel]')
    expect(panel).not.toBeNull()
    expect(panel?.getAttribute('href')).toBe('/lancer')
  })
})
