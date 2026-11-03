import { TestBed } from '@angular/core/testing'
import { of, throwError } from 'rxjs'
import { SetupApiService } from './setup-api.service'
import { SetupStateService } from './setup-state.service'
import { SetupStatus } from './setup.models'

const baseStatus = (patch: Partial<SetupStatus> = {}): SetupStatus =>
  ({
    mode: 'laptop',
    setupEnabled: true,
    setupDone: false,
    steps: {
      download: { state: 'running', percent: 40 },
      java: { state: 'done', version: 'openjdk 25' },
      agentos: { state: 'pending' },
      factory: { state: 'pending' },
      providers: { state: 'pending' },
      models: { state: 'pending' },
      namespace: { state: 'pending' },
      agent: { state: 'pending' },
    },
    apply: { status: 'idle' },
    defaults: { sharedExchangePath: '/home/me/Coday' },
    ...patch,
  }) as SetupStatus

describe('SetupStateService', () => {
  let api: { getStatus: jest.Mock; saveIdentity: jest.Mock; apply: jest.Mock; browse: jest.Mock; mkdir: jest.Mock }
  let state: SetupStateService

  beforeEach(() => {
    api = {
      getStatus: jest.fn().mockReturnValue(of(baseStatus())),
      saveIdentity: jest.fn().mockReturnValue(of({ identity: { email: 'me@acme.com' } })),
      apply: jest.fn().mockReturnValue(of({ status: 'running' })),
      browse: jest.fn(),
      mkdir: jest.fn(),
    }
    TestBed.configureTestingModule({ providers: [{ provide: SetupApiService, useValue: api }] })
    state = TestBed.inject(SetupStateService)
  })

  afterEach(() => state.stopPolling())

  describe('email step', () => {
    it('validates the email', () => {
      state.email.set('nope')
      expect(state.emailValid()).toBe(false)
      state.email.set(' me@acme.com ')
      expect(state.emailValid()).toBe(true)
    })

    it('saves the trimmed identity and moves on to the providers', async () => {
      state.email.set(' me@acme.com ')
      await state.submitEmail()
      expect(api.saveIdentity).toHaveBeenCalledWith('me@acme.com')
      expect(state.step()).toBe('providers')
    })

    it('stays on the step and shows the server message when saving fails', async () => {
      api.saveIdentity.mockReturnValue(throwError(() => ({ error: { error: 'AgentOS already started' } })))
      state.email.set('me@acme.com')
      await state.submitEmail()
      expect(state.step()).toBe('email')
      expect(state.busy()).toBe(false)
    })

    it('does nothing for an invalid email', async () => {
      state.email.set('nope')
      await state.submitEmail()
      expect(api.saveIdentity).not.toHaveBeenCalled()
    })
  })

  describe('providers step', () => {
    it('requires at least one provider with a plausible key', () => {
      expect(state.providersValid()).toBe(false)
      state.toggleProvider('anthropic')
      expect(state.providersValid()).toBe(false)
      state.setApiKey('anthropic', 'short')
      expect(state.providersValid()).toBe(false)
      state.setApiKey('anthropic', 'sk-ant-long-enough')
      expect(state.providersValid()).toBe(true)
    })

    it('requires a key for EVERY enabled provider', () => {
      state.toggleProvider('anthropic')
      state.toggleProvider('openai')
      state.setApiKey('anthropic', 'sk-ant-long-enough')
      expect(state.providersValid()).toBe(false)
      state.setApiKey('openai', 'sk-openai-long-enough')
      expect(state.providersValid()).toBe(true)
    })

    it('ranks providers in the order they were enabled, and reorders with the arrows', () => {
      state.toggleProvider('openai')
      state.toggleProvider('anthropic')
      expect(state.rankOf('openai')).toBe(1)
      expect(state.rankOf('anthropic')).toBe(2)
      expect(state.rankOf('requesty')).toBeNull()

      state.moveProvider('anthropic', -1)
      expect(state.rankOf('anthropic')).toBe(1)
      state.moveProvider('anthropic', -1) // already first: no-op
      expect(state.rankOf('anthropic')).toBe(1)
      state.moveProvider('anthropic', 1)
      expect(state.rankOf('anthropic')).toBe(2)
    })

    it('reorders with drag and drop', () => {
      for (const id of ['anthropic', 'openai', 'requesty'] as const) state.toggleProvider(id)
      state.moveProviderTo('requesty', 'anthropic')
      expect(state.enabledProviders().map((p) => p.id)).toEqual(['requesty', 'anthropic', 'openai'])
    })

    it('drops a disabled provider from the ranking', () => {
      state.toggleProvider('anthropic')
      state.toggleProvider('openai')
      state.toggleProvider('anthropic')
      expect(state.rankOf('openai')).toBe(1)
      expect(state.rankOf('anthropic')).toBeNull()
    })
  })

  describe('apply', () => {
    beforeEach(() => {
      state.toggleProvider('anthropic')
      state.toggleProvider('openai')
      state.setApiKey('anthropic', '  sk-ant-long-enough ')
      state.setApiKey('openai', 'sk-openai-long-enough')
      state.selectFolder('/home/me/Coday')
    })

    it('sends the ranked providers with trimmed keys and the folder, then shows the progress', async () => {
      await state.submitFolderAndApply()
      expect(api.apply).toHaveBeenCalledWith({
        providers: [
          { id: 'anthropic', apiKey: 'sk-ant-long-enough', rank: 1 },
          { id: 'openai', apiKey: 'sk-openai-long-enough', rank: 2 },
        ],
        sharedExchangePath: '/home/me/Coday',
      })
      expect(state.step()).toBe('progress')
    })

    it('refuses a relative folder', async () => {
      state.selectFolder('relative/path')
      await state.submitFolderAndApply()
      expect(api.apply).not.toHaveBeenCalled()
    })

    it('stays on the folder step with a message when the server refuses', async () => {
      state.step.set('folder')
      api.apply.mockReturnValue(throwError(() => ({ error: { error: 'The API key of openai looks too short.' } })))
      await state.submitFolderAndApply()
      expect(state.step()).toBe('folder')
      expect(state.busy()).toBe(false)
    })

    it('never keeps the keys in the status or in storage', async () => {
      await state.submitFolderAndApply()
      expect(JSON.stringify(state.status())).not.toContain('sk-ant')
      expect(JSON.stringify(localStorage)).not.toContain('sk-ant')
    })
  })

  describe('status and checklist', () => {
    it('builds the checklist in display order from the polled status', async () => {
      await state.refreshStatus()
      expect(state.checklist().map((c) => c.key)).toEqual([
        'download',
        'java',
        'agentos',
        'providers',
        'models',
        'namespace',
        'agent',
      ])
      expect(state.checklist()[0]).toMatchObject({ state: 'running', percent: 40 })
    })

    it('exposes the blocked Java so the UI can show the install hint', async () => {
      api.getStatus.mockReturnValue(
        of(baseStatus({ steps: { ...baseStatus().steps, java: { state: 'blocked', hint: 'brew install' } } }))
      )
      await state.refreshStatus()
      expect(state.javaBlocked()).toBe(true)
    })

    it('is ready only when the apply is done and the namespace is known', async () => {
      await state.refreshStatus()
      expect(state.ready()).toBe(false)
      api.getStatus.mockReturnValue(of(baseStatus({ apply: { status: 'done' }, namespaceId: 'ns-1' })))
      await state.refreshStatus()
      expect(state.ready()).toBe(true)
      expect(state.landingCommands()).toEqual({ path: '/home', queryParams: { ns: 'ns-1' } })
    })

    it('reports a failed apply', async () => {
      api.getStatus.mockReturnValue(of(baseStatus({ apply: { status: 'error', error: 'boom' } })))
      await state.refreshStatus()
      expect(state.failed()).toBe(true)
    })

    it('keeps the last status and reports an error when the server cannot be reached', async () => {
      await state.refreshStatus()
      api.getStatus.mockReturnValue(throwError(() => ({ status: 0 })))
      expect(await state.refreshStatus()).toBeNull()
      expect(state.statusError()).toBeTruthy()
      expect(state.status()).not.toBeNull()
    })
  })

  describe('loading page (setup already done)', () => {
    const done = (steps: Partial<SetupStatus['steps']> = {}, patch: Partial<SetupStatus> = {}) =>
      baseStatus({
        setupDone: true,
        apply: { status: 'done' },
        steps: { ...baseStatus().steps, agentos: { state: 'running' }, ...steps },
        ...patch,
      })

    afterEach(() => jest.useRealTimers())

    it('enters the loading mode instead of the wizard, on the progress view', async () => {
      api.getStatus.mockReturnValue(of(done()))
      await state.init()
      expect(state.mode()).toBe('loading')
      expect(state.step()).toBe('progress')
    })

    it('stays in the wizard mode on a first run', async () => {
      await state.init()
      expect(state.mode()).toBe('wizard')
    })

    it('enters the loading mode where there is no wizard (existing install, --auth, external AgentOS)', async () => {
      api.getStatus.mockReturnValue(of(baseStatus({ mode: 'managed', setupEnabled: false })))
      await state.init()
      expect(state.mode()).toBe('loading')
      expect(state.step()).toBe('progress')
    })

    it('lists the services only, none of the setup steps', async () => {
      api.getStatus.mockReturnValue(of(done()))
      await state.init()
      expect(state.loadingChecklist().map((c) => c.key)).toEqual(['download', 'java', 'agentos', 'factory'])
    })

    it('is not ready until AgentOS is up', async () => {
      api.getStatus.mockReturnValue(of(done()))
      await state.init()
      expect(state.loadingReady()).toBe(false)
    })

    it('is ready once AgentOS and the Factory are up', async () => {
      api.getStatus.mockReturnValue(of(done({ agentos: { state: 'done' }, factory: { state: 'done' } })))
      await state.init()
      expect(state.loadingReady()).toBe(true)
    })

    it('does not hold the user back for a Factory that failed', async () => {
      api.getStatus.mockReturnValue(
        of(done({ agentos: { state: 'done' }, factory: { state: 'error', message: 'download failed' } }))
      )
      await state.init()
      expect(state.loadingReady()).toBe(true)
    })

    it('gives a slow Factory a grace period once AgentOS is up, then leaves without it', async () => {
      jest.useFakeTimers()
      api.getStatus.mockReturnValue(of(done({ agentos: { state: 'done' }, factory: { state: 'running' } })))
      await state.init()
      expect(state.loadingReady()).toBe(false)
      jest.advanceTimersByTime(20_000)
      expect(state.loadingReady()).toBe(true)
    })

    it('hands over to the app, on the personal namespace when it is known and by itself otherwise', async () => {
      api.getStatus.mockReturnValue(of(done()))
      await state.init()
      expect(state.loadingTarget()).toEqual({ path: '/home', queryParams: {} })
      api.getStatus.mockReturnValue(of(done({}, { namespaceId: 'ns-1' })))
      await state.refreshStatus()
      expect(state.loadingTarget()).toEqual({ path: '/home', queryParams: { ns: 'ns-1' } })
    })
  })

  describe('init (resume)', () => {
    it('prefills the default folder and the known identity', async () => {
      api.getStatus.mockReturnValue(of(baseStatus({ identity: { email: 'me@acme.com' } })))
      await state.init()
      expect(state.folder()).toBe('/home/me/Coday')
      expect(state.email()).toBe('me@acme.com')
      expect(state.step()).toBe('email')
    })

    it('jumps to the progress when an apply is already running (page reload)', async () => {
      api.getStatus.mockReturnValue(of(baseStatus({ apply: { status: 'running' } })))
      await state.init()
      expect(state.step()).toBe('progress')
    })

    it('asks for the keys again when the user retries a failed apply after a page reload', async () => {
      api.getStatus.mockReturnValue(of(baseStatus({ apply: { status: 'error', error: 'boom' } })))
      await state.init()
      expect(state.step()).toBe('progress')

      await state.retry()

      expect(api.apply).not.toHaveBeenCalled()
      expect(state.step()).toBe('providers')
      expect(state.error()).toMatch(/API keys/)
    })
  })
})
