import { initialState, RuntimeState } from './state'
import { renderStatus } from './status-view'

const provisioned = (patch: Partial<RuntimeState> = {}): RuntimeState => ({
  ...initialState('nx', 'laptop'),
  provisioned: true,
  ports: { express: 3000, agentos: 8124, agentosBolt: 8125, factory: 8141 },
  java: { state: 'ok', version: 'openjdk 25 2025-09-16', major: 25 },
  ...patch,
})

const lines = (text: string): string[] => text.split('\n')

describe('renderStatus', () => {
  it('waits for provisioning while state.json is absent or not provisioned', () => {
    expect(renderStatus(undefined, {})).toContain('waiting for provisioning')
    expect(renderStatus(initialState('nx', 'laptop'), {})).toContain('waiting for provisioning')
  })

  it('idle: the URL and the ports are shown, nothing started yet', () => {
    const out = lines(renderStatus(provisioned({ downloads: { agentos: { state: 'idle' } } }), {}))
    expect(out[0]).toBe('Coday   http://localhost:3000   (AgentOS 8124 · Factory 8141)')
    expect(out[1]).toBe('Java    ok (25)')
    expect(out[2]).toBe('AgentOS preparing')
    expect(out[3]).toBe('Factory waiting for AgentOS')
  })

  it('downloading: shows the percentage of each service', () => {
    const out = lines(
      renderStatus(
        provisioned({
          downloads: { agentos: { state: 'running', percent: 42 }, factory: { state: 'running', percent: 100 } },
        }),
        {}
      )
    )
    expect(out[2]).toBe('AgentOS downloading 42%')
    expect(out[3]).toBe('Factory downloading 100%')
  })

  it('waiting for the wizard: downloaded, not spawned yet, laptop profile', () => {
    const out = lines(
      renderStatus(
        provisioned({ downloads: { agentos: { state: 'done', percent: 100 }, factory: { state: 'done' } } }),
        {}
      )
    )
    expect(out[2]).toBe('AgentOS waiting for first-run setup -> http://localhost:3000/welcome')
    expect(out[3]).toBe('Factory waiting for AgentOS')
  })

  it('starting: AgentOS spawned but not healthy yet; Factory starting once AgentOS is healthy', () => {
    const state = provisioned({
      agentosStarted: true,
      downloads: { agentos: { state: 'done' }, factory: { state: 'done' } },
    })
    expect(lines(renderStatus(state, {}))[2]).toBe('AgentOS starting')
    const out = lines(renderStatus(state, { agentos: true }))
    expect(out[2]).toBe('AgentOS healthy')
    expect(out[3]).toBe('Factory starting')
  })

  it('the managed profile does not wait for the wizard', () => {
    const state = provisioned({ profile: 'managed', downloads: { agentos: { state: 'done' } } })
    expect(lines(renderStatus(state, {}))[2]).toBe('AgentOS starting')
  })

  it('java missing or too old: says so with the hint, and AgentOS waits for it', () => {
    const missing = renderStatus(
      provisioned({
        java: { state: 'missing', hint: 'Install it with: brew install --cask temurin@25' },
        downloads: { agentos: { state: 'done' } },
      }),
      {}
    )
    expect(lines(missing)[1]).toBe(
      'Java    MISSING, Java 25+ is required: Install it with: brew install --cask temurin@25'
    )
    expect(lines(missing)[2]).toBe('AgentOS waiting for Java 25+')
    const old = renderStatus(
      provisioned({ java: { state: 'too-old', version: 'openjdk 21', major: 21, hint: 'h' } }),
      {}
    )
    expect(lines(old)[1]).toContain('too old (21)')
  })

  it('download error: shown prominently, per service', () => {
    const out = lines(
      renderStatus(
        provisioned({
          downloads: {
            agentos: { state: 'error', message: 'checksum mismatch' },
            factory: { state: 'error', message: 'HTTP 404' },
          },
        }),
        {}
      )
    )
    expect(out[2]).toBe('AgentOS DOWNLOAD FAILED: checksum mismatch')
    expect(out[3]).toBe('Factory DOWNLOAD FAILED: HTTP 404')
  })

  it('all healthy', () => {
    const out = lines(
      renderStatus(
        provisioned({ agentosStarted: true, downloads: { agentos: { state: 'done' }, factory: { state: 'done' } } }),
        { agentos: true, factory: true }
      )
    )
    expect(out).toEqual([
      'Coday   http://localhost:3000   (AgentOS 8124 · Factory 8141)',
      'Java    ok (25)',
      'AgentOS healthy',
      'Factory healthy',
    ])
  })

  it('external services: reported as such, not waited for, and their ports are not advertised', () => {
    const out = lines(
      renderStatus(
        provisioned({ profile: 'managed' }),
        {},
        {
          agentosUrl: 'http://localhost:9000',
          factoryUrl: 'http://localhost:9100',
        }
      )
    )
    expect(out[0]).toBe('Coday   http://localhost:3000')
    expect(out[2]).toBe('AgentOS external (http://localhost:9000)')
    expect(out[3]).toBe('Factory external (http://localhost:9100)')
    // An external AgentOS counts as ready for a managed Factory.
    const factoryOnly = renderStatus(provisioned({ profile: 'managed' }), {}, { agentosUrl: 'http://x' })
    expect(lines(factoryOnly)[3]).toBe('Factory starting')
  })

  it('is deterministic: the same inputs give the same text (the caller diffs it)', () => {
    const state = provisioned({ downloads: { agentos: { state: 'running', percent: 5 } } })
    expect(renderStatus(state, {})).toBe(renderStatus(state, {}))
  })
})
