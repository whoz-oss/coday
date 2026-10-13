import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideRouter } from '@angular/router'
import { of } from 'rxjs'
import { FactoryStore } from '../../../core/factory.store'
import { FactoryRun, WorkstreamView } from '../../../core/models'
import { WorkstreamActionEvent, WorkstreamCardComponent } from './workstream-card.component'

function createRun(overrides: Partial<FactoryRun> = {}): FactoryRun {
  const id = overrides.id ?? 'wf-1'
  const costUsd = overrides.costUsd ?? 1.5
  return {
    id,
    title: `Run ${id}`,
    project: 'ns-1',
    namespaceId: 'ns-1',
    status: 'working',
    costUsd,
    durationSec: 90,
    tokens: 1000,
    phases: [],
    run: {
      id,
      workflow: `wf-${id}`,
      status: 'running',
      goal: 'Do the real thing',
      costUsd,
      unknownCostCount: 0,
      durationSec: 90,
      tokens: 1000,
      phases: [],
    },
    ...overrides,
  }
}

const storeStub = {
  session: jest.fn().mockReturnValue(undefined),
  openSupervisorCase: jest.fn().mockReturnValue(of(null)),
  restoreSupervisorCase: jest.fn().mockReturnValue(undefined),
}

describe('WorkstreamCardComponent', () => {
  let fixture: ComponentFixture<WorkstreamCardComponent>

  async function setup(workstream: WorkstreamView): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [WorkstreamCardComponent],
      providers: [provideRouter([]), { provide: FactoryStore, useValue: storeStub }],
    }).compileComponents()
    fixture = TestBed.createComponent(WorkstreamCardComponent)
    fixture.componentRef.setInput('workstream', workstream)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  it('renders the workstream title, namespaceId, run count and total cost', async () => {
    const host = await setup({
      namespaceId: 'ns-a',
      title: 'Alpha',
      runs: [createRun({ id: 'wf-1', costUsd: 1.5 }), createRun({ id: 'wf-2', costUsd: 2.5 })],
    })

    expect(host.querySelector('.title')?.textContent?.trim()).toBe('Alpha')
    expect(host.querySelector('.namespace-id')?.textContent?.trim()).toBe('ns-a')
    expect(host.querySelector('.count')?.textContent).toContain('2 runs')
    expect(host.querySelector('.cost')?.textContent).toContain('$4.00')
  })

  it('does NOT render any overall state/working badge in the workstream header', async () => {
    const host = await setup({
      namespaceId: 'ns-a',
      title: 'Alpha',
      runs: [createRun({ id: 'wf-1', status: 'working' })],
    })

    const header = host.querySelector('.workstream-head')
    expect(header?.querySelector('sf-status-chip')).toBeNull()
    expect(header?.textContent).not.toContain('working')
  })

  it('keeps independent run statuses inside each run card', async () => {
    const host = await setup({
      namespaceId: 'ns-a',
      title: 'Alpha',
      runs: [
        createRun({ id: 'wf-1', status: 'working' }),
        createRun({ id: 'wf-2', status: 'idle' }),
        createRun({ id: 'wf-3', status: 'destroyed' }),
      ],
    })

    // Statuses live on the run cards only.
    expect(host.querySelectorAll('sf-run-card')).toHaveLength(3)
    expect(host.textContent).toContain('working')
    expect(host.textContent).toContain('waiting')
    expect(host.textContent).toContain('destroyed')
  })

  it('exposes accessible aria-labels for the workstream and its runs', async () => {
    const host = await setup({
      namespaceId: 'ns-a',
      title: 'Alpha',
      runs: [createRun({ id: 'wf-1', title: 'Nightly build' })],
    })

    const region = host.querySelector('.workstream')
    expect(region?.getAttribute('role')).toBe('region')
    expect(region?.getAttribute('aria-label')).toBe('Workstream Alpha')

    const card = host.querySelector('sf-run-card .card')
    expect(card?.getAttribute('aria-label')).toContain('Run Nightly build')
  })

  it('bubbles run actions with the exact run id', async () => {
    const host = await setup({
      namespaceId: 'ns-a',
      title: 'Alpha',
      runs: [createRun({ id: 'wf-42', title: 'Shared title' })],
    })
    const events: WorkstreamActionEvent[] = []
    fixture.componentInstance.action.subscribe((event) => events.push(event))

    const remove = Array.from(host.querySelectorAll('.actions button')).find(
      (button) => button.textContent?.trim() === 'Remove'
    ) as HTMLButtonElement | undefined
    remove?.click()

    expect(events).toEqual([{ runId: 'wf-42', action: 'remove' }])
  })
})
