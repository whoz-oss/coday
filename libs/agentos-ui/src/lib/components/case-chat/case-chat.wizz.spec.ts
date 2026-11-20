import { HttpClient } from '@angular/common/http'
import { JsonPipe } from '@angular/common'
import { NO_ERRORS_SCHEMA, signal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute } from '@angular/router'
import { Configuration, User } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { UserStateService } from '../../services/user-state.service'
import { VoiceCapabilityStateService } from '../../services/voice-capability-state.service'
import { CostPipe } from '../../pipes/cost.pipe'
import { TokenCountPipe } from '../../pipes/token-count.pipe'
import { CaseChatComponent } from './case-chat.component'

interface FakeSource extends EventTarget {
  readonly url: string
  readonly close: jest.Mock
  onerror: ((event: Event) => void) | null
}

interface FakeAnimation {
  readonly cancel: jest.Mock
  onfinish: (() => void) | null
  oncancel: (() => void) | null
}

/** Actual-template coverage for Wizz bindings and the live completion path. */
describe('CaseChatComponent Wizz template', () => {
  let fixture: ComponentFixture<CaseChatComponent>
  let sources: FakeSource[]
  let animate: jest.Mock
  let capability: { load: jest.Mock; available: ReturnType<typeof signal<boolean>> }
  let originalEventSource: typeof EventSource
  let originalAnimate: typeof Element.prototype.animate

  const message = (id: string) => ({
    id,
    type: 'MessageEvent',
    caseId: 'c-1',
    namespaceId: 'ns-1',
    timestamp: '',
    metadata: { id, created: '', modified: '', removed: false },
    actor: { id: 'agent-1', role: 'AGENT', displayName: 'Agent' },
    content: [{ type: 'Text', content: 'Completed answer' }],
  })
  const finished = (id: string) => ({
    id,
    type: 'AgentFinishedEvent',
    caseId: 'c-1',
    namespaceId: 'ns-1',
    timestamp: '',
    metadata: { id, created: '', modified: '', removed: false },
    agentId: 'agent-1',
    agentName: 'Agent',
  })
  const emit = (source: FakeSource, event: object): void =>
    source.dispatchEvent(new MessageEvent('case-event', { data: JSON.stringify(event) }))

  beforeEach(async () => {
    jest.useFakeTimers()
    sources = []
    capability = { load: jest.fn(), available: signal(false) }
    originalEventSource = globalThis.EventSource
    originalAnimate = Element.prototype.animate
    globalThis.EventSource = jest.fn((url: string) => {
      const source = Object.assign(new EventTarget(), { url, close: jest.fn(), onerror: null }) as FakeSource
      sources.push(source)
      return source
    }) as unknown as typeof EventSource
    animate = jest.fn((): FakeAnimation => ({ cancel: jest.fn(), onfinish: null, oncancel: null }))
    Element.prototype.animate = animate as unknown as typeof Element.prototype.animate

    await TestBed.configureTestingModule({
      imports: [CaseChatComponent],
      providers: [
        { provide: HttpClient, useValue: { get: () => of({}), post: () => of({}) } },
        { provide: Configuration, useValue: { basePath: '' } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParams: { case: 'c-1', ns: 'ns-1' } }, queryParams: of({}) } },
        { provide: VoiceCapabilityStateService, useValue: capability },
        {
          provide: ExchangeStateService,
          useValue: {
            canWriteCase: signal(true), canWriteNamespace: signal(false), fileCount: signal(0),
            refreshManifest: jest.fn(), refreshCase: jest.fn(), refreshNamespace: jest.fn(),
          },
        },
        {
          provide: CaseStateService,
          useValue: {
            cases: signal([]), updateCaseTitle: jest.fn(), updateCaseStatus: jest.fn(), notifyAgentMessageEvent: jest.fn(),
            setReasoningEffort: () => of({}),
          },
        },
        { provide: PromptStateService, useValue: { listEffective: () => of([]) } },
        { provide: UserStateService, useValue: { currentUser: signal<User | null>(null) } },
        {
          provide: USER_PREFERENCES_PORT,
          useValue: { shouldSend: () => false, composerHint: () => 'hint', showTechnical: signal(true), enterKeyBehavior: signal('send') },
        },
      ],
    })
      // Keep CaseChatComponent's real template and lifecycle while shallow-rendering children.
      // These three pipes are referenced statically; nested components are irrelevant to Wizz.
      .overrideComponent(CaseChatComponent, {
        set: { imports: [JsonPipe, CostPipe, TokenCountPipe], schemas: [NO_ERRORS_SCHEMA] },
      })
      .compileComponents()
    fixture = TestBed.createComponent(CaseChatComponent)
    fixture.detectChanges()
  })

  afterEach(() => {
    fixture?.destroy()
    globalThis.EventSource = originalEventSource
    Element.prototype.animate = originalAnimate
    jest.useRealTimers()
    TestBed.resetTestingModule()
  })

  it('uses the real template and only animates the panel for one completed live answer', () => {
    const host = fixture.nativeElement as HTMLElement
    const button = host.querySelector<HTMLButtonElement>('.case-chat__wizz-toggle')!
    const panel = host.querySelector<HTMLElement>('.case-chat')!
    expect(capability.load).toHaveBeenCalledTimes(1)
    expect(sources.map((source) => source.url)).toEqual(['/api/cases/c-1/events'])
    expect(button.type).toBe('button')
    expect(button.textContent?.trim()).toBe('Wizz')
    expect(button.getAttribute('aria-pressed')).toBe('false')
    expect(button.classList.contains('case-chat__wizz-toggle--active')).toBe(false)

    button.click()
    fixture.detectChanges()
    const main = sources.find((source) => source.url === '/api/cases/c-1/events')!
    const live = sources.find((source) => source.url === '/api/cases/c-1/events?includePreviousEvents=false')!
    expect(button.getAttribute('aria-pressed')).toBe('true')
    expect(button.classList.contains('case-chat__wizz-toggle--active')).toBe(true)

    // The replaying main stream still renders history, but it never drives Wizz.
    emit(main, message('history-message'))
    emit(main, finished('history-finished'))
    expect(fixture.componentInstance['events']().map((event) => event.id)).toContain('history-message')
    jest.runOnlyPendingTimers()
    expect(animate).not.toHaveBeenCalled()

    emit(live, message('live-message'))
    expect(animate).not.toHaveBeenCalled()
    emit(live, finished('live-finished'))
    jest.runOnlyPendingTimers()
    expect(animate).toHaveBeenCalledTimes(1)
    expect(animate.mock.contexts[0]).toBe(panel)

    // A replayed live pair has already been consumed by the isolated observer.
    emit(live, message('live-message'))
    emit(live, finished('live-finished'))
    jest.runOnlyPendingTimers()
    expect(animate).toHaveBeenCalledTimes(1)

    const animation = animate.mock.results[0]!.value as FakeAnimation
    button.click()
    fixture.detectChanges()
    expect(button.getAttribute('aria-pressed')).toBe('false')
    expect(button.classList.contains('case-chat__wizz-toggle--active')).toBe(false)
    expect(live.close).toHaveBeenCalledTimes(1)
    expect(animation.cancel).toHaveBeenCalledTimes(1)
  })
})
