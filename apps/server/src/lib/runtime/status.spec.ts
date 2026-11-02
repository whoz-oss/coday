import { initialState, RuntimeState } from './state'
import { createStatusReporter, StatusFacts } from './status'

const state = (patch: Partial<RuntimeState> = {}): RuntimeState => ({
  ...initialState('nx', 'laptop'),
  provisioned: true,
  ports: { express: 3000, agentos: 8224, agentosBolt: 7788, factory: 8241 },
  java: { state: 'ok', version: 'openjdk 25', major: 25 },
  ...patch,
})

const facts = (patch: Partial<StatusFacts> = {}): StatusFacts => ({
  state: state(),
  setup: {},
  healthy: { agentos: false, factory: false },
  external: {},
  ...patch,
})

describe('status reporter', () => {
  it('waits quietly (once) until provision wrote state.json', () => {
    const report = createStatusReporter()
    expect(report(facts({ state: undefined }))).toEqual(['waiting for provisioning...'])
    expect(report(facts({ state: undefined }))).toEqual([])
    expect(report(facts({ state: state({ provisioned: false }) }))).toEqual([])
  })

  it('prints java and the URL to open once provisioned, then nothing while nothing changes', () => {
    const report = createStatusReporter()
    expect(report(facts())).toEqual(['java ok', 'Open -> http://localhost:3000/welcome'])
    expect(report(facts())).toEqual([])
    expect(report(facts())).toEqual([])
  })

  it('shows the install hint when Java is missing, and java ok once fixed', () => {
    const report = createStatusReporter()
    const missing = state({ java: { state: 'missing', hint: 'Coday needs Java 25 or newer.' } })
    expect(report(facts({ state: missing }))[0]).toBe('java missing: Coday needs Java 25 or newer.')
    expect(report(facts({ state: missing }))).toEqual([])
    expect(report(facts())).toEqual(['java ok'])
  })

  it('throttles the download to one line per decile, whatever the poll rate', () => {
    const report = createStatusReporter()
    const at = (percent: number) => facts({ state: state({ downloads: { agentos: { state: 'running', percent } } }) })
    const printed = [0, 3, 9, 10, 12, 19, 20, 42, 43, 49, 50, 99, 100].flatMap((p) =>
      report(at(p)).filter((l) => l.startsWith('agentos'))
    )
    expect(printed).toEqual([
      'agentos download 0%',
      'agentos download 10%',
      'agentos download 20%',
      'agentos download 42%',
      'agentos download 50%',
      'agentos download 99%',
      'agentos download 100%',
    ])
  })

  it('tracks agentos and factory independently', () => {
    const report = createStatusReporter()
    const lines = report(
      facts({
        state: state({
          downloads: { agentos: { state: 'running', percent: 42 }, factory: { state: 'running', percent: 5 } },
        }),
      })
    )
    expect(lines).toContain('agentos download 42%')
    expect(lines).toContain('factory download 5%')
  })

  it('reports a failed download with its reason, once', () => {
    const report = createStatusReporter()
    const failed = facts({ state: state({ downloads: { factory: { state: 'error', message: 'Manifest HTTP 404' } } }) })
    expect(report(failed)).toContain('factory download failed: Manifest HTTP 404')
    expect(report(failed)).toEqual([])
  })

  it('explains what a service waits for once its JARs are there', () => {
    const report = createStatusReporter()
    const done = { agentos: { state: 'done' as const }, factory: { state: 'done' as const } }
    const waiting = report(facts({ state: state({ downloads: done }) }))
    expect(waiting).toContain('agentos jars ready, waiting for first-run setup')
    expect(waiting).toContain('factory jars ready, waiting for agentos')

    const identified = report(facts({ state: state({ downloads: done }), setup: { identity: { email: 'a@b.co' } } }))
    expect(identified).toEqual(['agentos jars ready, starting'])
  })

  it('reports transitions to healthy with the port, then silence', () => {
    const report = createStatusReporter()
    report(facts())
    const up = facts({ healthy: { agentos: true, factory: false } })
    expect(report(up)).toEqual(['agentos healthy :8224'])
    expect(report(up)).toEqual([])
    expect(report(facts({ healthy: { agentos: true, factory: true } }))).toEqual(['factory healthy :8241'])
  })

  it('tolerates one or two failed probes of a healthy service, reports it down on the third, and back up', () => {
    const report = createStatusReporter()
    const up = facts({ healthy: { agentos: true, factory: true } })
    const flaky = facts({ healthy: { agentos: false, factory: true } })
    report(up)
    expect(report(flaky)).toEqual([])
    expect(report(flaky)).toEqual([])
    expect(report(flaky)).toEqual(['agentos not responding'])
    expect(report(flaky)).toEqual([])
    expect(report(up)).toEqual(['agentos healthy :8224'])
  })

  it('does not report a blip as a transition', () => {
    const report = createStatusReporter()
    const up = facts({ healthy: { agentos: true, factory: true } })
    report(up)
    report(facts({ healthy: { agentos: false, factory: true } }))
    expect(report(up)).toEqual([])
  })

  it('reports external services instead of probing managed ports (origin only, no credentials)', () => {
    const report = createStatusReporter()
    const lines = report(
      facts({
        external: { agentos: 'http://user:secret@agentos.acme.com:8124/x', factory: 'http://localhost:8141' },
        state: state({ profile: 'managed' }),
      })
    )
    expect(lines).toContain('agentos external http://agentos.acme.com:8124')
    expect(lines).toContain('factory external http://localhost:8141')
    expect(lines.join('\n')).not.toContain('secret')
    expect(report(facts({ external: { agentos: 'http://agentos.acme.com:8124/x' } }))).not.toContain(
      'agentos healthy :8224'
    )
  })

  it('sends the user to /welcome only while the first-run setup is pending, then to the root', () => {
    const report = createStatusReporter()
    expect(report(facts())).toContain('Open -> http://localhost:3000/welcome')
    const done = facts({ setup: { identity: { email: 'a@b.co' }, setupDone: true } })
    expect(report(done)).toEqual(['Open -> http://localhost:3000/  (once agentos is healthy)'])
  })

  it('never shows /welcome outside the laptop profile', () => {
    const report = createStatusReporter()
    const lines = report(facts({ state: state({ profile: 'managed' }) }))
    expect(lines.join('\n')).not.toContain('/welcome')
    expect(lines).toContain('Open -> http://localhost:3000/  (once agentos is healthy)')
  })

  it('shows the first-run wizard URL at once: it needs no AgentOS', () => {
    const report = createStatusReporter()
    expect(report(facts())).toContain('Open -> http://localhost:3000/welcome')
    expect(report(facts())).toEqual([])
  })

  it('says the app URL is for later until AgentOS is healthy, then repeats it plainly (same rule as the browser)', () => {
    const report = createStatusReporter()
    const resume = { setup: { identity: { email: 'a@b.co' }, setupDone: true } }
    expect(report(facts(resume))).toContain('Open -> http://localhost:3000/  (once agentos is healthy)')
    expect(report(facts(resume))).toEqual([])
    expect(report(facts({ ...resume, healthy: { agentos: true, factory: false } }))).toEqual([
      'agentos healthy :8224',
      'Open -> http://localhost:3000/',
    ])
  })

  it('makes the app URL wait for an external AgentOS too, probed at its own URL like the browser does', () => {
    const report = createStatusReporter()
    const external = { agentos: 'http://agentos.acme.com:8124' }
    const managed = state({ profile: 'managed' })
    expect(report(facts({ state: managed, external }))).toContain(
      'Open -> http://localhost:3000/  (once agentos is healthy)'
    )
    const up = report(facts({ state: managed, external, healthy: { agentos: true, factory: false } }))
    expect(up).toContain('Open -> http://localhost:3000/')
    expect(up).not.toContain('agentos healthy :8224') // external: no managed port to quote
  })
})
