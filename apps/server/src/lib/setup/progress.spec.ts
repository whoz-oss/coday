import { JavaStatus } from '../runtime/java'
import { initialState, RuntimeState } from '../runtime/state'
import { ApplyTracker, buildSetupStatus, decideIdentityChange, idleApply, StatusFacts } from './progress'

const laptopState = (patch: Partial<RuntimeState> = {}): RuntimeState => ({
  ...initialState('nx', 'laptop'),
  provisioned: true,
  java: { state: 'ok', version: 'openjdk 25', major: 25 },
  ...patch,
})

const facts = (patch: Partial<StatusFacts> = {}): StatusFacts => ({
  setupEnabled: true,
  setup: {},
  state: laptopState(),
  agentosHealthy: false,
  factoryHealthy: false,
  apply: idleApply(),
  ...patch,
})

describe('buildSetupStatus', () => {
  it('reports laptop mode, not done, no identity on a first run', () => {
    const status = buildSetupStatus(facts())
    expect(status.mode).toBe('laptop')
    expect(status.setupDone).toBe(false)
    expect(status.identity).toBeUndefined()
    expect(status.steps.agentos).toEqual({ state: 'pending', message: 'Waiting for your email' })
  })

  it('reports managed mode when no state exists (nothing to redirect to)', () => {
    expect(buildSetupStatus(facts({ state: undefined, setupEnabled: false })).mode).toBe('managed')
  })

  it('exposes the identity once known', () => {
    const status = buildSetupStatus(facts({ setup: { identity: { email: 'a@b.co' } } }))
    expect(status.identity).toEqual({ email: 'a@b.co' })
    expect(status.steps.agentos.state).not.toBe('pending')
  })

  it('surfaces a missing Java as a blocked step with the install hint', () => {
    const java: JavaStatus = { state: 'missing', hint: 'brew install --cask temurin@25' }
    const status = buildSetupStatus(facts({ state: laptopState({ java }) }))
    expect(status.steps.java).toEqual({ state: 'blocked', hint: 'brew install --cask temurin@25' })
    expect(status.steps.agentos.state).toBe('blocked')
  })

  it('reports an old Java with its version', () => {
    const java: JavaStatus = { state: 'too-old', version: 'openjdk 21', major: 21, hint: 'upgrade' }
    expect(buildSetupStatus(facts({ state: laptopState({ java }) })).steps.java).toMatchObject({
      state: 'blocked',
      version: 'openjdk 21',
    })
  })

  it('merges the AgentOS and Factory downloads into one weighted percentage', () => {
    const state = laptopState({
      downloads: { agentos: { state: 'running', percent: 50 }, factory: { state: 'done', percent: 100 } },
    })
    const { download } = buildSetupStatus(facts({ state })).steps
    expect(download.state).toBe('running')
    expect(download.percent).toBe(Math.round(0.85 * 50 + 0.15 * 100))
  })

  it('turns a failed download into an error that also fails AgentOS', () => {
    const state = laptopState({ downloads: { agentos: { state: 'error', message: 'Manifest HTTP 404' } } })
    const { steps } = buildSetupStatus(facts({ state }))
    expect(steps.download).toEqual({ state: 'error', message: 'Manifest HTTP 404' })
    expect(steps.agentos).toEqual({ state: 'error', message: 'Manifest HTTP 404' })
  })

  it('does not let a Factory download failure block the wizard: it is reported on the Factory step only', () => {
    const state = laptopState({
      agentosStarted: true,
      downloads: { agentos: { state: 'done' }, factory: { state: 'error', message: "No 'factory-service' entry" } },
    })
    const { steps } = buildSetupStatus(facts({ state, setup: { identity: { email: 'a@b.co' } } }))
    expect(steps.download.state).toBe('done')
    expect(steps.agentos.state).toBe('running')
    expect(steps.factory).toEqual({ state: 'error', message: "No 'factory-service' entry" })
  })

  it('goes pending -> running -> done for AgentOS and chains Factory behind it', () => {
    const started = laptopState({
      agentosStarted: true,
      downloads: { agentos: { state: 'done' }, factory: { state: 'done' } },
    })
    const setup = { identity: { email: 'a@b.co' } }
    expect(buildSetupStatus(facts({ state: started, setup })).steps.agentos.state).toBe('running')
    expect(buildSetupStatus(facts({ state: started, setup })).steps.factory?.state).toBe('pending')
    const up = buildSetupStatus(facts({ state: started, setup, agentosHealthy: true }))
    expect(up.steps.agentos.state).toBe('done')
    expect(up.steps.factory?.state).toBe('running')
    expect(
      buildSetupStatus(facts({ state: started, setup, agentosHealthy: true, factoryHealthy: true })).steps.factory
        ?.state
    ).toBe('done')
  })

  it('has nothing to download, check or start for an external AgentOS and Factory, only to wait for them', () => {
    const state = laptopState({ profile: 'managed', java: { state: 'missing', hint: 'install java' } })
    const waiting = buildSetupStatus(facts({ state, setupEnabled: false, external: { agentos: true, factory: true } }))
    expect(waiting.steps.download).toEqual({ state: 'done', percent: 100 })
    expect(waiting.steps.java).toEqual({ state: 'done' })
    expect(waiting.steps.agentos).toEqual({ state: 'running', message: 'Waiting for AgentOS' })
    expect(waiting.steps.factory?.state).toBe('pending')

    const up = buildSetupStatus(
      facts({ state, setupEnabled: false, external: { agentos: true, factory: true }, agentosHealthy: true })
    )
    expect(up.steps.agentos.state).toBe('done')
    expect(up.steps.factory?.state).toBe('running')
  })

  it('leaves the Factory out of the status when it is disabled (CODAY_NO_FACTORY): no step, no download share', () => {
    const state = laptopState({
      downloads: { agentos: { state: 'running', percent: 50 }, factory: { state: 'error', message: 'stale' } },
    })
    const { steps } = buildSetupStatus(facts({ state, factoryDisabled: true }))
    expect(steps.download).toEqual({ state: 'running', percent: 50 })
    expect(steps.factory).toBeUndefined()
    expect('factory' in steps).toBe(false)
  })

  it('does not wait for a Factory once it is disabled: AgentOS up is all there is', () => {
    const started = laptopState({ agentosStarted: true, downloads: { agentos: { state: 'done' } } })
    const setup = { identity: { email: 'a@b.co' } }
    const up = buildSetupStatus(facts({ state: started, setup, agentosHealthy: true, factoryDisabled: true }))
    expect(up.steps.agentos.state).toBe('done')
    expect(up.steps.factory).toBeUndefined()
  })

  it('has nothing to download or check for an external AgentOS when the Factory is disabled', () => {
    const state = laptopState({ profile: 'managed', java: { state: 'missing', hint: 'install java' } })
    const { steps } = buildSetupStatus(
      facts({ state, setupEnabled: false, external: { agentos: true }, factoryDisabled: true })
    )
    expect(steps.download).toEqual({ state: 'done', percent: 100 })
    expect(steps.java).toEqual({ state: 'done' })
  })

  it('still waits for an external Factory even when the managed one is disabled', () => {
    const state = laptopState({ profile: 'managed' })
    const { steps } = buildSetupStatus(
      facts({ state, setupEnabled: false, external: { factory: true }, factoryDisabled: true })
    )
    expect(steps.factory).toEqual({ state: 'pending' })
  })

  it('still reports a managed Factory download while only AgentOS is external', () => {
    const state = laptopState({
      profile: 'managed',
      downloads: { factory: { state: 'running', percent: 30 } },
    })
    const { steps } = buildSetupStatus(facts({ state, setupEnabled: false, external: { agentos: true } }))
    expect(steps.download).toEqual({ state: 'running', percent: 30 })
    expect(steps.java.state).toBe('done')
  })

  it('relays the per-step apply results', () => {
    const tracker = new ApplyTracker()
    tracker.start()
    tracker.step('providers', 'done')
    tracker.step('models', 'running')
    const status = buildSetupStatus(facts({ apply: tracker.get() }))
    expect(status.steps.providers.state).toBe('done')
    expect(status.steps.models.state).toBe('running')
    expect(status.apply.status).toBe('running')
  })

  it('shows every apply step done once the setup is done, whatever the in-memory record says', () => {
    const status = buildSetupStatus(facts({ setup: { setupDone: true, identity: { email: 'a@b.co' } } }))
    expect(status.setupDone).toBe(true)
    expect(status.apply.status).toBe('done')
    for (const step of ['providers', 'models', 'namespace', 'agent'] as const)
      expect(status.steps[step].state).toBe('done')
  })
})

describe('ApplyTracker', () => {
  it('starts idle, records steps, and finishes', () => {
    const tracker = new ApplyTracker()
    expect(tracker.get().status).toBe('idle')
    tracker.start()
    expect(tracker.isRunning()).toBe(true)
    tracker.setNamespaceId('ns-1')
    tracker.finish()
    expect(tracker.get()).toMatchObject({ status: 'done', namespaceId: 'ns-1' })
  })

  it('marks the running step as failed on error', () => {
    const tracker = new ApplyTracker()
    tracker.start()
    tracker.step('providers', 'done')
    tracker.step('models', 'running')
    tracker.fail('boom')
    const snapshot = tracker.get()
    expect(snapshot.status).toBe('error')
    expect(snapshot.steps.providers.state).toBe('done')
    expect(snapshot.steps.models).toEqual({ state: 'error', message: 'boom' })
    expect(snapshot.steps.namespace.state).toBe('pending')
  })

  it('returns copies: callers cannot mutate the record', () => {
    const tracker = new ApplyTracker()
    tracker.get().steps.providers.state = 'done'
    expect(tracker.get().steps.providers.state).toBe('pending')
  })

  it('restarts clean after a failure (retry)', () => {
    const tracker = new ApplyTracker()
    tracker.start()
    tracker.fail('x')
    tracker.start()
    expect(tracker.get()).toMatchObject({ status: 'running', steps: { providers: { state: 'pending' } } })
    expect(tracker.get().error).toBeUndefined()
  })
})

describe('decideIdentityChange', () => {
  const email = 'a@b.co'
  it('refuses when setup is not enabled', () => {
    expect(decideIdentityChange(false, {}, undefined, email)).toMatchObject({ ok: false, status: 403 })
  })

  it('refuses once the setup is done', () => {
    expect(decideIdentityChange(true, { setupDone: true }, undefined, email)).toMatchObject({ ok: false, status: 403 })
  })

  it('accepts the first identity and a change before AgentOS started', () => {
    expect(decideIdentityChange(true, {}, laptopState(), email)).toEqual({ ok: true })
    expect(decideIdentityChange(true, { identity: { email: 'old@b.co' } }, laptopState(), email)).toEqual({ ok: true })
  })

  it('refuses to change the identity once AgentOS booted with another one (bootstrap admin is fixed)', () => {
    const started = laptopState({ agentosStarted: true })
    expect(decideIdentityChange(true, { identity: { email: 'old@b.co' } }, started, email)).toMatchObject({
      ok: false,
      status: 409,
    })
  })

  it('still refuses after a restart when an earlier run created the AgentOS database', () => {
    const restarted = laptopState({ agentosStarted: false })
    expect(decideIdentityChange(true, { identity: { email: 'old@b.co' } }, restarted, email, true)).toMatchObject({
      ok: false,
      status: 409,
    })
  })

  it('accepts re-sending the same identity after AgentOS started (page reload)', () => {
    const started = laptopState({ agentosStarted: true })
    expect(decideIdentityChange(true, { identity: { email } }, started, email)).toEqual({ ok: true })
  })
})
