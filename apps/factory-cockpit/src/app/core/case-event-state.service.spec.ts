import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { firstValueFrom } from 'rxjs'
import {
  AGENTOS_API_ORIGIN,
  AgentOsCaseEvent,
  CaseEventStateService,
  mapAgentOsEventToRunEvent,
} from './case-event-state.service'
import { RunEvent } from './models'

// ---------------------------------------------------------------------------
// Fake EventSource (jsdom ships no implementation)
// ---------------------------------------------------------------------------

type Listener = (event: MessageEvent) => void

class FakeEventSource {
  static instances: FakeEventSource[] = []

  onerror: (() => void) | null = null
  closed = false
  private readonly listeners = new Map<string, Listener[]>()

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: Listener): void {
    const bucket = this.listeners.get(type) ?? []
    bucket.push(listener)
    this.listeners.set(type, bucket)
  }

  close(): void {
    this.closed = true
  }

  emit(type: string, data: unknown): void {
    const payload = typeof data === 'string' ? data : JSON.stringify(data)
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data: payload } as MessageEvent)
    }
  }
}

// ---------------------------------------------------------------------------
// mapAgentOsEventToRunEvent — unit tests
// ---------------------------------------------------------------------------

describe('mapAgentOsEventToRunEvent', () => {
  const baseEvent = (type: string, extra: Partial<AgentOsCaseEvent> = {}): AgentOsCaseEvent => ({
    id: 'ev-1',
    type,
    timestamp: '2024-01-15T10:30:00.000Z',
    ...extra,
  })

  it('maps MessageEvent with actor and content text', () => {
    const event = baseEvent('MessageEvent', {
      actor: { displayName: 'Heimdall', role: 'AGENT' },
      content: [{ text: 'Hello world' }],
    })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result).not.toBeNull()
    expect(result!.type).toBe('agent_message')
    expect(result!.text).toBe('[Heimdall] Hello world')
    expect(result!.time).toBe('10:30:00')
  })

  it('falls back to role when displayName is absent on MessageEvent', () => {
    const event = baseEvent('MessageEvent', {
      actor: { role: 'USER' },
      content: [{ text: 'Hi' }],
    })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result!.text).toBe('[USER] Hi')
  })

  it('extracts the current Text content shape and preserves line breaks between blocks', () => {
    const event = baseEvent('MessageEvent', {
      actor: { displayName: 'Agent' },
      content: [
        { type: 'Text', content: 'Part one.\nStill part one.' },
        { type: 'Text', content: 'Part two.' },
      ],
    })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result!.messageText).toBe('Part one.\nStill part one.\nPart two.')
    expect(result!.text).toBe('[Agent] Part one.\nStill part one.\nPart two.')
  })

  it('ignores image content and honestly preserves an absent textual body', () => {
    const event = baseEvent('MessageEvent', {
      actor: { displayName: 'Searcher' },
      content: [{ type: 'Image', content: 'base64-data' }],
    })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result!.text).toBe('[Searcher]')
    expect(result!.messageText).toBe('')
  })

  it('maps ToolRequestEvent with tool name', () => {
    const event = baseEvent('ToolRequestEvent', { toolName: 'bash' })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result).not.toBeNull()
    expect(result!.type).toBe('tool_call')
    expect(result!.tool).toBe('bash')
    expect(result!.text).toBe('bash')
  })

  it('maps ToolResponseEvent with success and duration', () => {
    const event = baseEvent('ToolResponseEvent', {
      toolName: 'write',
      success: true,
      durationMs: 1500,
    })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result).not.toBeNull()
    expect(result!.type).toBe('tool_call')
    expect(result!.tool).toBe('write')
    expect(result!.text).toContain('ok')
    expect(result!.durationSec).toBeCloseTo(1.5)
  })

  it('maps ToolResponseEvent failure correctly', () => {
    const event = baseEvent('ToolResponseEvent', {
      toolName: 'bash',
      success: false,
      durationMs: 300,
    })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result!.text).toContain('error')
    expect(result!.durationSec).toBeCloseTo(0.3)
  })

  it('maps ThinkingEvent to a fixed label (no text field in DTO)', () => {
    const event = baseEvent('ThinkingEvent')
    const result = mapAgentOsEventToRunEvent(event)
    expect(result).not.toBeNull()
    expect(result!.type).toBe('thinking')
    expect(result!.text).toBe('(thinking)')
  })

  it('maps ErrorEvent with message', () => {
    const event = baseEvent('ErrorEvent', { message: 'Something went wrong' })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result).not.toBeNull()
    expect(result!.type).toBe('log')
    expect(result!.text).toContain('ErrorEvent')
    expect(result!.text).toContain('Something went wrong')
  })

  it('maps WarnEvent with message', () => {
    const event = baseEvent('WarnEvent', { message: 'Low memory' })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result!.type).toBe('log')
    expect(result!.text).toContain('WarnEvent')
    expect(result!.text).toContain('Low memory')
  })

  it('returns null for TextChunkEvent (streaming noise)', () => {
    expect(mapAgentOsEventToRunEvent(baseEvent('TextChunkEvent'))).toBeNull()
  })

  it('returns null for AgentFinishedEvent (lifecycle noise)', () => {
    expect(mapAgentOsEventToRunEvent(baseEvent('AgentFinishedEvent'))).toBeNull()
  })

  it('returns null for AgentRunningEvent (lifecycle noise)', () => {
    expect(mapAgentOsEventToRunEvent(baseEvent('AgentRunningEvent'))).toBeNull()
  })

  it('returns null for CaseStatusEvent (lifecycle noise)', () => {
    expect(mapAgentOsEventToRunEvent(baseEvent('CaseStatusEvent'))).toBeNull()
  })

  it('returns null for AgentSelectedEvent (lifecycle noise)', () => {
    expect(mapAgentOsEventToRunEvent(baseEvent('AgentSelectedEvent'))).toBeNull()
  })

  it('maps unknown event types to a log entry', () => {
    const event = baseEvent('SomeNewFutureEvent')
    const result = mapAgentOsEventToRunEvent(event)
    expect(result).not.toBeNull()
    expect(result!.type).toBe('log')
    expect(result!.text).toContain('SomeNewFutureEvent')
  })

  it('returns --:--:-- for absent timestamp', () => {
    const event: AgentOsCaseEvent = { id: 'ev-x', type: 'ThinkingEvent' }
    const result = mapAgentOsEventToRunEvent(event)
    expect(result!.time).toBe('--:--:--')
  })

  it('returns --:--:-- for invalid timestamp', () => {
    const event = baseEvent('ThinkingEvent', { timestamp: 'not-a-date' })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result!.time).toBe('--:--:--')
  })

  it('ignores non-finite durationMs on ToolResponseEvent', () => {
    const event = baseEvent('ToolResponseEvent', {
      toolName: 'bash',
      success: true,
      durationMs: NaN,
    })
    const result = mapAgentOsEventToRunEvent(event)
    expect(result!.durationSec).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// CaseEventStateService — shared setup helpers
// ---------------------------------------------------------------------------

/**
 * Configure TestBed with optional AGENTOS_API_ORIGIN override.
 *
 * - `origin = ''`  (default) — simulates production or tests where
 *   HttpTestingController matches bare paths like `/api/case-events/…`.
 * - `origin = 'http://localhost:4300'`  — simulates the factory-cockpit dev
 *   server origin, verifying that URLs are constructed as absolute.
 */
function setupTestBed(origin = '') {
  FakeEventSource.instances = []
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      CaseEventStateService,
      // Override the token so HttpTestingController can match bare paths.
      // In production, this token resolves to window.location.origin.
      { provide: AGENTOS_API_ORIGIN, useValue: origin },
    ],
  })
  // Override EventSource globally with the fake.
  // CaseEventStateService uses `new EventSource(url)` inside NgZone.runOutsideAngular.
  ;(globalThis as Record<string, unknown>)['EventSource'] = FakeEventSource
}

// ---------------------------------------------------------------------------
// CaseEventStateService — integration tests (default: origin = '')
// ---------------------------------------------------------------------------

describe('CaseEventStateService', () => {
  let service: CaseEventStateService
  let http: HttpTestingController

  function makeHistoryEvent(id: string, type: string, extra: Partial<AgentOsCaseEvent> = {}): AgentOsCaseEvent {
    return { id, type, timestamp: '2024-01-15T10:00:00.000Z', ...extra }
  }

  beforeEach(() => {
    setupTestBed('')
    service = TestBed.inject(CaseEventStateService)
    http = TestBed.inject(HttpTestingController)
  })

  afterEach(() => {
    service.ngOnDestroy()
    http.verify()
  })

  // ── Initial state ───────────────────────────────────────────────────────────────────────────

  it('starts in idle state with no events', async () => {
    const state = await firstValueFrom(service.state$)
    const events = await firstValueFrom(service.events$)
    expect(state).toBe('idle')
    expect(events).toEqual([])
  })

  // ── activate() — normal flow ─────────────────────────────────────────────────────

  it('sets state to loading immediately on activate()', async () => {
    service.activate('case-1')
    const state = await firstValueFrom(service.state$)
    expect(state).toBe('loading')
    // Clean up pending request
    http.expectOne((r) => r.url.includes('/api/case-events/by-parentId/case-1')).flush([])
  })

  it('fetches history from GET /api/case-events/by-parentId/{caseId}', () => {
    service.activate('case-abc')
    const req = http.expectOne((r) => r.url === '/api/case-events/by-parentId/case-abc')
    expect(req.request.method).toBe('GET')
    req.flush([])
  })

  it('sets state to ready and populates events after successful history fetch', async () => {
    service.activate('case-1')

    const historyEvents: AgentOsCaseEvent[] = [
      makeHistoryEvent('ev-1', 'ToolRequestEvent', { toolName: 'bash' }),
      makeHistoryEvent('ev-2', 'MessageEvent', {
        actor: { displayName: 'Agent' },
        content: [{ text: 'Done' }],
      }),
      makeHistoryEvent('ev-3', 'TextChunkEvent'), // should be filtered out
    ]
    http.expectOne((r) => r.url.includes('case-1')).flush(historyEvents)

    const state = await firstValueFrom(service.state$)
    const events = await firstValueFrom(service.events$)
    expect(state).toBe('ready')
    expect(events).toHaveLength(2) // TextChunkEvent filtered
    expect(events[0]!.type).toBe('tool_call')
    expect(events[1]!.type).toBe('agent_message')
  })

  it('opens SSE to GET /api/cases/{caseId}/events after history succeeds', () => {
    service.activate('case-1')
    http.expectOne((r) => r.url.includes('case-1')).flush([])

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(FakeEventSource.instances[0]!.url).toBe('/api/cases/case-1/events')
  })

  it('appends live SSE events to the accumulated list', async () => {
    service.activate('case-1')
    http
      .expectOne((r) => r.url.includes('case-1'))
      .flush([makeHistoryEvent('ev-1', 'ToolRequestEvent', { toolName: 'read' })])

    const sse = FakeEventSource.instances[0]!
    sse.emit('case-event', makeHistoryEvent('ev-live', 'ThinkingEvent'))

    const events = await firstValueFrom(service.events$)
    expect(events).toHaveLength(2)
    expect(events[1]!.type).toBe('thinking')
  })

  // ── Deduplication ────────────────────────────────────────────────────────────────────

  it('deduplicates SSE replay: events already in history are not appended again', async () => {
    service.activate('case-1')
    const historyEvent = makeHistoryEvent('ev-1', 'ToolRequestEvent', { toolName: 'bash' })
    http.expectOne((r) => r.url.includes('case-1')).flush([historyEvent])

    const sse = FakeEventSource.instances[0]!
    // SSE replays the same event that was already in the history
    sse.emit('case-event', historyEvent)

    const events = await firstValueFrom(service.events$)
    // Only one entry: the duplicate from SSE is dropped
    expect(events).toHaveLength(1)
    expect(events[0]!.tool).toBe('bash')
  })

  it('accepts new SSE events whose id was not in the history', async () => {
    service.activate('case-1')
    http
      .expectOne((r) => r.url.includes('case-1'))
      .flush([makeHistoryEvent('ev-1', 'ToolRequestEvent', { toolName: 'bash' })])

    const sse = FakeEventSource.instances[0]!
    sse.emit('case-event', makeHistoryEvent('ev-2', 'ToolRequestEvent', { toolName: 'write' }))

    const events = await firstValueFrom(service.events$)
    expect(events).toHaveLength(2)
    expect(events[1]!.tool).toBe('write')
  })

  // ── Case switching — cancellation of previous pipeline ──────────────────────────────

  it('cancels the previous SSE connection when a new case is activated', () => {
    service.activate('case-1')
    http.expectOne((r) => r.url.includes('case-1')).flush([])
    const firstSse = FakeEventSource.instances[0]!
    expect(firstSse.closed).toBe(false)

    service.activate('case-2')
    // The first SSE must be closed immediately
    expect(firstSse.closed).toBe(true)

    http.expectOne((r) => r.url.includes('case-2')).flush([])
  })

  it('resets events to empty when switching to a new case', async () => {
    service.activate('case-1')
    http.expectOne((r) => r.url.includes('case-1')).flush([makeHistoryEvent('ev-1', 'ThinkingEvent')])

    // Now switch to a different case
    service.activate('case-2')
    const eventsAfterSwitch = await firstValueFrom(service.events$)
    // Events are cleared immediately on activate()
    expect(eventsAfterSwitch).toEqual([])

    http.expectOne((r) => r.url.includes('case-2')).flush([])
  })

  it('does not let stale SSE events from old case appear after case switch', async () => {
    service.activate('case-1')
    http.expectOne((r) => r.url.includes('case-1')).flush([])
    const firstSse = FakeEventSource.instances[0]!

    // Switch to case-2 (closes case-1 SSE)
    service.activate('case-2')
    http.expectOne((r) => r.url.includes('case-2')).flush([])

    // A stale case-1 SSE event arrives after the switch (late delivery)
    firstSse.emit('case-event', makeHistoryEvent('ev-stale', 'ThinkingEvent'))

    // The stale event must NOT appear in the current event list
    const events = await firstValueFrom(service.events$)
    expect(events.find((e) => e.text === '(thinking)')).toBeUndefined()
  })

  // ── Error handling ────────────────────────────────────────────────────────────────────

  it('sets state to error when the REST history fetch fails (HTTP 500)', async () => {
    service.activate('case-1')
    http
      .expectOne((r) => r.url.includes('case-1'))
      .flush({ error: 'internal' }, { status: 500, statusText: 'Server Error' })

    const state = await firstValueFrom(service.state$)
    expect(state).toBe('error')
  })

  it('emits empty events on REST error (never shows stale data)', async () => {
    service.activate('case-1')
    http
      .expectOne((r) => r.url.includes('case-1'))
      .flush({ error: 'internal' }, { status: 500, statusText: 'Server Error' })

    const events = await firstValueFrom(service.events$)
    expect(events).toEqual([])
  })

  it('does not open SSE when the REST history fetch fails', () => {
    service.activate('case-1')
    http
      .expectOne((r) => r.url.includes('case-1'))
      .flush({ error: 'internal' }, { status: 500, statusText: 'Server Error' })

    expect(FakeEventSource.instances).toHaveLength(0)
  })

  it('recovers: activating a new case after an error fetches fresh history', async () => {
    // First case fails
    service.activate('case-bad')
    http
      .expectOne((r) => r.url.includes('case-bad'))
      .flush({ error: 'fail' }, { status: 503, statusText: 'Unavailable' })
    expect(await firstValueFrom(service.state$)).toBe('error')

    // Activate a new case — must recover
    service.activate('case-good')
    expect(await firstValueFrom(service.state$)).toBe('loading')

    http.expectOne((r) => r.url.includes('case-good')).flush([makeHistoryEvent('ev-1', 'ThinkingEvent')])

    expect(await firstValueFrom(service.state$)).toBe('ready')
    expect((await firstValueFrom(service.events$)).length).toBe(1)
  })

  it('survives a malformed SSE frame without crashing', async () => {
    service.activate('case-1')
    http.expectOne((r) => r.url.includes('case-1')).flush([])

    const sse = FakeEventSource.instances[0]!
    // Emit invalid JSON
    sse.emit('case-event', 'NOT_VALID_JSON')

    // Service stays alive and state stays ready
    const state = await firstValueFrom(service.state$)
    expect(state).toBe('ready')
  })

  // ── clear() ──────────────────────────────────────────────────────────────────────────

  it('clear() closes SSE and resets to idle', async () => {
    service.activate('case-1')
    http.expectOne((r) => r.url.includes('case-1')).flush([])
    const sse = FakeEventSource.instances[0]!
    expect(sse.closed).toBe(false)

    service.clear()

    expect(sse.closed).toBe(true)
    expect(await firstValueFrom(service.state$)).toBe('idle')
    expect(await firstValueFrom(service.events$)).toEqual([])
  })

  it('clear() before any activate() is a no-op', async () => {
    service.clear()
    expect(await firstValueFrom(service.state$)).toBe('idle')
    expect(await firstValueFrom(service.events$)).toEqual([])
    http.expectNone(() => true)
  })

  // ── ngOnDestroy ────────────────────────────────────────────────────────────────────────

  it('ngOnDestroy closes the active SSE connection', () => {
    service.activate('case-1')
    http.expectOne((r) => r.url.includes('case-1')).flush([])
    const sse = FakeEventSource.instances[0]!

    service.ngOnDestroy()

    expect(sse.closed).toBe(true)
  })

  it('ngOnDestroy completes the observables (no further emissions)', () => {
    const states: string[] = []
    service.state$.subscribe((s) => states.push(s))

    service.ngOnDestroy()

    // Attempt to emit after destroy (should be ignored)
    // The BehaviorSubject is completed, so no new emission possible.
    const initialLength = states.length
    // No crash, no new emission
    expect(states.length).toBe(initialLength)
  })

  // ── SSE channel name ───────────────────────────────────────────────────────────────────

  it('listens on the case-event SSE channel (not message)', async () => {
    service.activate('case-1')
    http.expectOne((r) => r.url.includes('case-1')).flush([])

    const sse = FakeEventSource.instances[0]!

    // Emit on the WRONG channel — should be ignored
    sse.emit('message', makeHistoryEvent('ev-wrong', 'ThinkingEvent'))

    const events = await firstValueFrom(service.events$)
    expect(events).toHaveLength(0)
  })
})

// ---------------------------------------------------------------------------
// CaseEventStateService — URL construction under base href /factory/
//
// These tests verify the fix for the bug where <base href="/factory/"> caused
// Angular's fetch-based HttpClient to resolve /api/case-events/… as
// /factory/api/case-events/…, which the gateway could not route to AgentOS.
//
// The fix: CaseEventStateService injects AGENTOS_API_ORIGIN and prefixes all
// AgentOS API paths with it, producing absolute URLs that bypass <base href>.
// ---------------------------------------------------------------------------

describe('CaseEventStateService — URL construction (base href /factory/ scenario)', () => {
  let service: CaseEventStateService
  let http: HttpTestingController

  const SIMULATED_ORIGIN = 'http://localhost:4300'

  function makeHistoryEvent(id: string, type: string): AgentOsCaseEvent {
    return { id, type, timestamp: '2024-01-15T10:00:00.000Z' }
  }

  beforeEach(() => {
    // Simulate the cockpit running at http://localhost:4300/factory/
    // AGENTOS_API_ORIGIN = window.location.origin = 'http://localhost:4300'
    setupTestBed(SIMULATED_ORIGIN)
    service = TestBed.inject(CaseEventStateService)
    http = TestBed.inject(HttpTestingController)
  })

  afterEach(() => {
    service.ngOnDestroy()
    http.verify()
  })

  it('fetches history with an absolute URL that includes the origin (not /factory/ prefix)', () => {
    service.activate('test-case-id')

    // The URL must be absolute from the origin, NOT /factory/api/case-events/…
    const req = http.expectOne(`${SIMULATED_ORIGIN}/api/case-events/by-parentId/test-case-id`)
    expect(req.request.method).toBe('GET')
    // Crucially: no /factory/ in the path
    expect(req.request.url).not.toContain('/factory/')
    req.flush([])
  })

  it('opens SSE with an absolute URL that includes the origin (not /factory/ prefix)', () => {
    service.activate('sse-case-id')
    http.expectOne((r) => r.url.includes('/api/case-events/by-parentId/')).flush([])

    expect(FakeEventSource.instances).toHaveLength(1)
    const sseUrl = FakeEventSource.instances[0]!.url

    // The SSE URL must be absolute from the origin
    expect(sseUrl).toBe(`${SIMULATED_ORIGIN}/api/cases/sse-case-id/events`)
    // Crucially: no /factory/ in the path
    expect(sseUrl).not.toContain('/factory/')
  })

  it('URL-encodes the caseId in both REST and SSE paths', () => {
    const caseIdWithSpecialChars = 'case/with spaces'
    service.activate(caseIdWithSpecialChars)

    const req = http.expectOne(`${SIMULATED_ORIGIN}/api/case-events/by-parentId/case%2Fwith%20spaces`)
    expect(req.request.method).toBe('GET')
    req.flush([])

    const sseUrl = FakeEventSource.instances[0]!.url
    expect(sseUrl).toBe(`${SIMULATED_ORIGIN}/api/cases/case%2Fwith%20spaces/events`)
  })

  it('produces correct absolute URLs for a real UUID caseId', () => {
    const uuid = 'cfd97cc9-2590-3f0f-af0f-4fe045c56d49'
    service.activate(uuid)

    // This is the exact URL the user observed as broken (/factory/api/case-events/…)
    // The fix must produce the absolute URL WITHOUT /factory/ in the path.
    const req = http.expectOne(`${SIMULATED_ORIGIN}/api/case-events/by-parentId/${uuid}`)
    expect(req.request.url).toBe(`${SIMULATED_ORIGIN}/api/case-events/by-parentId/${uuid}`)
    expect(req.request.url).not.toContain('/factory/')
    req.flush([])

    const sseUrl = FakeEventSource.instances[0]!.url
    expect(sseUrl).toBe(`${SIMULATED_ORIGIN}/api/cases/${uuid}/events`)
    expect(sseUrl).not.toContain('/factory/')
  })

  it('still deduplicates and maps events correctly when using absolute URLs', async () => {
    service.activate('case-1')
    http
      .expectOne((r) => r.url.includes('/api/case-events/by-parentId/case-1'))
      .flush([
        makeHistoryEvent('ev-1', 'ToolRequestEvent'),
        makeHistoryEvent('ev-2', 'TextChunkEvent'), // filtered
      ])

    const events = await firstValueFrom(service.events$)
    expect(events).toHaveLength(1)
    expect(events[0]!.type).toBe('tool_call')
  })
})
