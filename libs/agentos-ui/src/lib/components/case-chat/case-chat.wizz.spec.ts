import { HttpClient } from '@angular/common/http'
import { JsonPipe } from '@angular/common'
import { ComponentFixture, NO_ERRORS_SCHEMA, TestBed } from '@angular/core/testing'
import { ActivatedRoute } from '@angular/router'
import { Configuration, User } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { UserStateService } from '../../services/user-state.service'
import { CaseChatComponent } from './case-chat.component'

/** Actual-template coverage for the Wizz button and its sole animation target. */
describe('CaseChatComponent Wizz template', () => {
  let fixture: ComponentFixture<CaseChatComponent>
  let sources: Array<EventTarget & { close: jest.Mock; onerror: ((event: Event) => void) | null }>
  let animate: jest.Mock
  let originalEventSource: typeof EventSource
  let originalAnimate: typeof Element.prototype.animate

  beforeEach(async () => {
    sources = []
    originalEventSource = globalThis.EventSource
    originalAnimate = Element.prototype.animate
    globalThis.EventSource = jest.fn(() => {
      const source = Object.assign(new EventTarget(), { close: jest.fn(), onerror: null })
      sources.push(source)
      return source
    }) as unknown as typeof EventSource
    animate = jest.fn(() => ({ cancel: jest.fn(), onfinish: null, oncancel: null }))
    Element.prototype.animate = animate as unknown as typeof Element.prototype.animate

    await TestBed.configureTestingModule({
      imports: [CaseChatComponent],
      providers: [
        { provide: HttpClient, useValue: { get: () => of({}), post: () => of({}) } },
        { provide: Configuration, useValue: { basePath: '' } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParams: { case: 'c-1', ns: 'ns-1' } }, queryParams: of({}) } },
        {
          provide: ExchangeStateService,
          useValue: {
            canWriteCase: () => true,
            canWriteNamespace: () => false,
            fileCount: () => 0,
            refreshManifest: jest.fn(),
            refreshCase: jest.fn(),
            refreshNamespace: jest.fn(),
          },
        },
        {
          provide: CaseStateService,
          useValue: {
            cases: () => [], updateCaseTitle: jest.fn(), updateCaseStatus: jest.fn(), notifyAgentMessageEvent: jest.fn(),
            setReasoningEffort: () => of({}),
          },
        },
        { provide: PromptStateService, useValue: { listEffective: () => of([]) } },
        { provide: UserStateService, useValue: { currentUser: () => null as User | null } },
        { provide: USER_PREFERENCES_PORT, useValue: { shouldSend: () => false, composerHint: () => 'hint', showTechnical: () => true, enterKeyBehavior: () => 'send' } },
      ],
    })
      .overrideComponent(CaseChatComponent, { set: { imports: [JsonPipe], schemas: [NO_ERRORS_SCHEMA] } })
      .compileComponents()
    fixture = TestBed.createComponent(CaseChatComponent)
    fixture.detectChanges()
  })

  afterEach(() => {
    fixture.destroy()
    globalThis.EventSource = originalEventSource
    Element.prototype.animate = originalAnimate
    TestBed.resetTestingModule()
  })

  it('binds a native pressed Wizz button and animates only the conversation panel', () => {
    const button = fixture.nativeElement.querySelector<HTMLButtonElement>('.case-chat__wizz-toggle')!
    const panel = fixture.nativeElement.querySelector<HTMLElement>('.case-chat')!
    expect(button.type).toBe('button')
    expect(button.textContent?.trim()).toBe('Wizz')
    expect(button.getAttribute('aria-pressed')).toBe('false')
    expect(button.classList.contains('case-chat__wizz-toggle--active')).toBe(false)

    button.click()
    fixture.detectChanges()
    expect(button.getAttribute('aria-pressed')).toBe('true')
    expect(button.classList.contains('case-chat__wizz-toggle--active')).toBe(true)
    expect(sources).toHaveLength(2) // main replay stream + opt-in live-only Wizz stream

    // Delayed replay events stay in the main stream and never enter the live-only observer.
    sources[0]!.dispatchEvent(
      new MessageEvent('MessageEvent', {
        data: JSON.stringify({ id: 'history-message', type: 'MessageEvent', caseId: 'c-1', actor: { role: 'AGENT' }, content: [] }),
      })
    )
    sources[0]!.dispatchEvent(
      new MessageEvent('AgentFinishedEvent', {
        data: JSON.stringify({ id: 'history-finished', type: 'AgentFinishedEvent', caseId: 'c-1', agentId: 'agent-1' }),
      })
    )
    expect(animate).not.toHaveBeenCalled()

    fixture.componentInstance['playWizzEffect']()
    expect(animate).toHaveBeenCalledTimes(1)
    expect(animate.mock.contexts[0]).toBe(panel)

    button.click()
    fixture.detectChanges()
    expect(button.getAttribute('aria-pressed')).toBe('false')
    expect(button.classList.contains('case-chat__wizz-toggle--active')).toBe(false)
  })
})
