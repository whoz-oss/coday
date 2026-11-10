import { ComponentFixture, TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { Configuration } from '@whoz-oss/agentos-api-client'
import { DelegationPresentation, DelegationStatus } from '../models/delegation.models'
import { DelegationCardComponent } from './delegation-card.component'

class MockEventSource {
  static instances: MockEventSource[] = []
  readonly close = jest.fn()
  private listener?: (message: { data: string }) => void

  constructor(readonly url: string) {
    MockEventSource.instances.push(this)
  }
  addEventListener(_type: string, listener: (message: { data: string }) => void): void {
    this.listener = listener
  }
  emit(event: Record<string, unknown>): void {
    this.raw(JSON.stringify(event))
  }
  raw(data: string): void {
    this.listener?.({ data })
  }
}

const delegation: DelegationPresentation = {
  delegationId: 'd-1',
  toolRequestId: 't-1',
  subCaseId: 'c-1',
  agentName: 'Research',
  task: 'Find facts',
  resumed: false,
  status: 'WORKING',
}

describe('DelegationCardComponent', () => {
  const originalEventSource = globalThis.EventSource
  let fixture: ComponentFixture<DelegationCardComponent>

  beforeEach(() => {
    MockEventSource.instances = []
    globalThis.EventSource = MockEventSource as unknown as typeof EventSource
    TestBed.configureTestingModule({
      imports: [DelegationCardComponent],
      providers: [
        { provide: Router, useValue: { navigate: jest.fn() } },
        { provide: Configuration, useValue: { basePath: '/backend' } },
      ],
    })
    fixture = TestBed.createComponent(DelegationCardComponent)
    fixture.componentRef.setInput('namespaceId', 'ns-1')
  })

  afterEach(() => {
    fixture.destroy()
    globalThis.EventSource = originalEventSource
  })

  function update(changes: Partial<DelegationPresentation> = {}): void {
    fixture.componentRef.setInput('delegation', { ...delegation, ...changes })
    fixture.detectChanges()
  }
  function line(): Element | null {
    return (fixture.nativeElement as HTMLElement).querySelector('.delegation-card__last-event')
  }
  function activity(): NodeListOf<Element> {
    return (fixture.nativeElement as HTMLElement).querySelectorAll('.delegation-card__activity li')
  }
  function toggle(): void {
    ;(fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.delegation-card__toggle')!.click()
    fixture.detectChanges()
  }

  it('streams only while the card is expanded', () => {
    update()

    // Collapsed: no connection at all. The connection budget is shared by every tab (#1616),
    // and the card still says what the delegation is doing from the parent's own events.
    expect(MockEventSource.instances).toHaveLength(0)
    expect(line()?.textContent?.trim()).toBe('Thinking')

    toggle()
    const source = MockEventSource.instances[0]!
    expect(source.url).toBe('/backend/api/cases/c-1/events')
    expect(activity()).toHaveLength(1)

    // Changes unrelated to the stream identity must not reconnect it.
    update({ task: 'Updated task' })
    expect(MockEventSource.instances).toHaveLength(1)
    expect(source.close).not.toHaveBeenCalled()

    toggle()
    expect(source.close).toHaveBeenCalledTimes(1)

    toggle()
    expect(MockEventSource.instances).toHaveLength(2)
  })

  it.each(['WAITING_USER', 'SUCCESS', 'ERROR', 'TIMEOUT', 'KILLED'] as const)(
    'disconnects automatically on %s',
    (status) => {
      update()
      toggle()
      const source = MockEventSource.instances[0]!

      update({ status })

      expect(source.close).toHaveBeenCalledTimes(1)
      if (status !== 'WAITING_USER') expect(line()).toBeNull()
      update()
      expect(MockEventSource.instances).toHaveLength(2)
    }
  )

  it('does not connect without a sub-case ID, nor for a delegation that is not working', () => {
    update({ subCaseId: '' })
    toggle()
    expect(MockEventSource.instances).toHaveLength(0)

    for (const status of ['WAITING_USER', 'SUCCESS', 'ERROR', 'TIMEOUT', 'KILLED'] as DelegationStatus[]) {
      update({ status })
    }
    expect(MockEventSource.instances).toHaveLength(0)
  })

  it('switches streams and clears activity when the sub-case changes, ignoring late events', () => {
    update()
    toggle()
    const old = MockEventSource.instances[0]!
    old.emit({ id: 'q', type: 'QuestionEvent', question: 'Old question?' })
    fixture.detectChanges()
    expect(line()?.textContent).toContain('Old question?')

    update({ subCaseId: 'c-2' })

    expect(old.close).toHaveBeenCalledTimes(1)
    expect(MockEventSource.instances[1]!.url).toBe('/backend/api/cases/c-2/events')
    old.emit({ id: 'late', type: 'QuestionEvent', question: 'Late question?' })
    fixture.detectChanges()
    expect(line()?.textContent?.trim()).toBe('Thinking')
    expect(activity()).toHaveLength(1)
  })

  it('shows rich activity, deduplicates replay and consecutive labels, and keeps the last six steps', () => {
    update()
    toggle()
    const source = MockEventSource.instances[0]!
    source.raw('{bad json')
    const read = { id: 'read', type: 'ToolRequestEvent', toolName: 'FILES__readFile', args: '{"filePath":"README.md"}' }
    source.emit(read)
    source.emit({ ...read, id: 'same-label' })
    fixture.detectChanges()
    expect(line()?.textContent?.trim()).toBe('Reading README.md')
    source.emit({ id: 'text', type: 'TextChunkEvent', chunk: 'Hello' })
    source.emit(read)
    fixture.detectChanges()
    expect(line()?.textContent?.trim()).toBe('Writing the answer')
    expect(activity()).toHaveLength(2)
    for (let i = 0; i < 8; i++) {
      source.emit({ ...read, id: `step-${i}`, args: JSON.stringify({ filePath: `${i % 2}.ts` }) })
    }
    fixture.detectChanges()
    const entries = activity()
    expect(entries).toHaveLength(6)
    expect(entries[0]?.textContent?.trim()).toBe('Reading 0.ts')
    expect(entries[5]?.textContent?.trim()).toBe('Reading 1.ts')
  })

  it('shows the streamed question even before WAITING_USER, and preserves it after disconnect', () => {
    update()
    toggle()
    MockEventSource.instances[0]!.emit({ id: 'q', type: 'QuestionEvent', question: 'Which branch?' })
    fixture.detectChanges()
    expect(line()?.textContent?.trim()).toBe('Question: Which branch?')
    expect(line()?.classList.contains('delegation-card__last-event--waiting')).toBe(true)
    update({ status: 'WAITING_USER' })
    expect(line()?.textContent?.trim()).toBe('Question: Which branch?')
    expect((fixture.nativeElement as HTMLElement).querySelector('.delegation-card--waiting')).not.toBeNull()
  })

  it('shows the parent result question without needing a stream', () => {
    update({
      status: 'WAITING_USER',
      result: {
        delegationId: 'd-1',
        toolRequestId: 't-1',
        pendingQuestion: 'Which project?',
      },
    })
    expect(line()?.textContent?.trim()).toBe('Question: Which project?')
    expect(MockEventSource.instances).toHaveLength(0)
  })

  it('surfaces pending confirmations and clears the highlight when resolved', () => {
    update()
    toggle()
    const source = MockEventSource.instances[0]!
    source.emit({ id: 'p', type: 'PendingConfirmationEvent', toolName: 'GIT__git_push', inputJson: '{}' })
    fixture.detectChanges()
    expect(line()?.textContent?.trim()).toBe('Confirmation needed: Pushing the branch')
    expect(line()?.classList.contains('delegation-card__last-event--waiting')).toBe(true)
    source.emit({ id: 'resolved', type: 'ConfirmationResolvedEvent', pendingEventId: 'p' })
    fixture.detectChanges()
    expect(line()?.textContent?.trim()).toBe('Thinking')
    expect(line()?.classList.contains('delegation-card__last-event--waiting')).toBe(false)
  })
})
