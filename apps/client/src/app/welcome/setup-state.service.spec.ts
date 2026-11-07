import { TestBed } from '@angular/core/testing'
import {
  AiProviderControllerService,
  NamespaceControllerService,
  UserControllerService,
} from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { SetupApiService } from './setup-api.service'
import { parseWelcomePreview, SetupStateService } from './setup-state.service'
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
  let getMeUser: jest.Mock
  let updateUser: jest.Mock
  let listByExternalIdsNamespace: jest.Mock
  let listAiProvider: jest.Mock
  let createAiProvider: jest.Mock
  let updateAiProvider: jest.Mock
  let state: SetupStateService

  beforeEach(() => {
    api = {
      getStatus: jest.fn().mockReturnValue(of(baseStatus())),
      saveIdentity: jest.fn().mockReturnValue(of({ identity: { email: 'me@acme.com' } })),
      apply: jest.fn().mockReturnValue(of({ status: 'running' })),
      browse: jest.fn(),
      mkdir: jest.fn(),
    }
    getMeUser = jest.fn().mockReturnValue(of({ externalId: 'me@acme.com', isAdmin: true }))
    updateUser = jest.fn().mockReturnValue(of({}))
    listByExternalIdsNamespace = jest.fn().mockReturnValue(of([{ id: 'ns-personal', externalId: 'user:me@acme.com' }]))
    listAiProvider = jest.fn().mockReturnValue(of([]))
    createAiProvider = jest.fn().mockReturnValue(of({}))
    updateAiProvider = jest.fn().mockReturnValue(of({}))
    TestBed.configureTestingModule({
      providers: [
        { provide: SetupApiService, useValue: api },
        { provide: UserControllerService, useValue: { getMeUser, updateUser } },
        { provide: NamespaceControllerService, useValue: { listByExternalIdsNamespace } },
        { provide: AiProviderControllerService, useValue: { listAiProvider, createAiProvider, updateAiProvider } },
      ],
    })
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

    describe('after a failed apply', () => {
      beforeEach(async () => {
        api.getStatus.mockReturnValue(of(baseStatus({ apply: { status: 'error', error: 'invalid x-api-key' } })))
        await state.submitFolderAndApply()
        await state.refreshStatus()
      })

      it('goes back to the providers to fix a key, then runs the setup again with the new one', async () => {
        state.editAfterFailure('providers')
        expect(state.step()).toBe('providers')
        expect(state.error()).toBeNull()

        state.setApiKey('anthropic', 'sk-ant-the-right-key')
        state.submitProviders()
        expect(state.step()).toBe('folder')
        await state.submitFolderAndApply()

        expect(api.apply).toHaveBeenLastCalledWith(
          expect.objectContaining({
            providers: expect.arrayContaining([{ id: 'anthropic', apiKey: 'sk-ant-the-right-key', rank: 1 }]),
          })
        )
        expect(state.step()).toBe('progress')
      })

      it('goes back to the folder step', () => {
        state.editAfterFailure('folder')
        expect(state.step()).toBe('folder')
      })

      it('no longer goes back to the email: AgentOS already made it its administrator', () => {
        state.editAfterFailure('providers')
        expect(state.emailLocked()).toBe(true)
        state.back()
        expect(state.step()).toBe('providers')
      })

      it('asks for the keys again when they were lost with a page reload', () => {
        state.setApiKey('anthropic', '')
        state.editAfterFailure('providers')
        expect(state.step()).toBe('providers')
        expect(state.error()).toMatch(/API keys/)
      })
    })

    it('does not leave the progress while the setup runs or once it succeeded', async () => {
      for (const apply of [{ status: 'running' as const }, { status: 'done' as const }]) {
        api.getStatus.mockReturnValue(of(baseStatus({ apply })))
        await state.submitFolderAndApply()
        await state.refreshStatus()
        state.editAfterFailure('providers')
        expect(state.step()).toBe('progress')
      }
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

    it('lists the services only, none of the setup steps, and no Factory unless one is configured', async () => {
      api.getStatus.mockReturnValue(of(done()))
      await state.init()
      expect(state.loadingChecklist().map((c) => c.key)).toEqual(['download', 'java', 'agentos'])

      api.getStatus.mockReturnValue(of(done({ factory: { state: 'running' } })))
      await state.refreshStatus()
      expect(state.loadingChecklist().map((c) => c.key)).toEqual(['download', 'java', 'agentos', 'factory'])
    })

    it('is not ready until AgentOS is up', async () => {
      api.getStatus.mockReturnValue(of(done()))
      await state.init()
      expect(state.loadingReady()).toBe(false)
    })

    it('is ready once AgentOS is up when there is no Factory to wait for', async () => {
      api.getStatus.mockReturnValue(of(done({ agentos: { state: 'done' } })))
      await state.init()
      expect(state.loadingReady()).toBe(true)
    })

    it('is ready once AgentOS and a configured Factory are up', async () => {
      api.getStatus.mockReturnValue(of(done({ agentos: { state: 'done' }, factory: { state: 'done' } })))
      await state.init()
      expect(state.loadingReady()).toBe(true)
    })

    it('does not hold the user back for a Factory that failed', async () => {
      api.getStatus.mockReturnValue(
        of(done({ agentos: { state: 'done' }, factory: { state: 'error', message: 'unreachable' } }))
      )
      await state.init()
      expect(state.loadingReady()).toBe(true)
    })

    it('gives a slow configured Factory a grace period once AgentOS is up, then leaves without it', async () => {
      jest.useFakeTimers()
      api.getStatus.mockReturnValue(of(done({ agentos: { state: 'done' }, factory: { state: 'running' } })))
      await state.init()
      expect(state.loadingReady()).toBe(false)
      jest.advanceTimersByTime(20_000)
      expect(state.loadingReady()).toBe(true)
    })

    it('always hands over to the personal namespace: the one the apply made, else the one AgentOS knows', async () => {
      api.getStatus.mockReturnValue(of(done({}, { namespaceId: 'ns-1' })))
      await state.init()
      expect(await state.loadingTarget()).toEqual({ path: '/home', queryParams: { ns: 'ns-1' } })
      expect(getMeUser).not.toHaveBeenCalled()

      // After a restart the server forgot the apply: the namespace is looked up as user:<externalId>.
      api.getStatus.mockReturnValue(of(done()))
      await state.refreshStatus()
      expect(await state.loadingTarget()).toEqual({ path: '/home', queryParams: { ns: 'ns-personal' } })
      expect(listByExternalIdsNamespace).toHaveBeenCalledWith(['user:me@acme.com'])
    })

    it('lets the app pick a namespace by itself when the personal one cannot be found', async () => {
      jest.spyOn(console, 'warn').mockImplementation(() => undefined)
      api.getStatus.mockReturnValue(of(done()))
      await state.init()

      listByExternalIdsNamespace.mockReturnValue(of([]))
      expect(await state.loadingTarget()).toEqual({ path: '/home', queryParams: {} })

      getMeUser.mockReturnValue(throwError(() => new Error('boom')))
      expect(await state.loadingTarget()).toEqual({ path: '/home', queryParams: {} })
    })
  })

  describe('profile: email and user keys (managed mode)', () => {
    const managed = (agentos: 'running' | 'done' = 'done') =>
      baseStatus({
        mode: 'managed',
        setupEnabled: false,
        setupDone: false,
        steps: { ...baseStatus().steps, agentos: { state: agentos } },
      })
    const platformProviders = [
      { id: 'p-anthropic', name: 'anthropic', apiType: 'Anthropic' },
      { id: 'p-openai', name: 'openai', apiType: 'OpenAI' },
    ]
    const providers = (platform: unknown[], mine: unknown[]) =>
      listAiProvider.mockImplementation((_ns: string, user?: string) => of(user ? mine : platform))
    const me = { id: 'user-1', externalId: 'me@acme.com', email: 'me@acme.com', firstname: 'Me', isAdmin: false }
    const mineWithKey = [{ id: 'u-1', name: 'anthropic', apiKey: 'sk-a****cdef' }]

    it('waits for AgentOS before looking at the providers', async () => {
      api.getStatus.mockReturnValue(of(managed('running')))
      await state.init()
      expect(listAiProvider).not.toHaveBeenCalled()
      expect(state.userKeysChecked()).toBe(false)
    })

    it('asks for every platform provider when the user has no global key for any of them', async () => {
      providers(platformProviders, [{ id: 'u-1', name: 'anthropic' }])
      getMeUser.mockReturnValue(of(me))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()

      expect(listAiProvider).toHaveBeenCalledWith('none')
      expect(listAiProvider).toHaveBeenCalledWith('none', 'me')
      expect(state.userKeys().map((k) => k.name)).toEqual(['anthropic', 'openai'])
      expect(state.needsUserKeys()).toBe(true)
      expect(state.needsEmail()).toBe(false)
      expect(state.loadingReady()).toBe(false)
    })

    it('asks for no key when the user already has one for a platform provider, whatever the case of its name', async () => {
      providers(platformProviders, [{ id: 'u-1', name: 'Anthropic', apiKey: 'sk-a****cdef' }])
      getMeUser.mockReturnValue(of(me))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()
      expect(state.needsUserKeys()).toBe(false)
    })

    it('does not count the key of a provider the platform does not offer', async () => {
      providers(platformProviders, [{ id: 'u-9', name: 'mistral', apiKey: 'sk-m****cdef' }])
      getMeUser.mockReturnValue(of(me))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()
      expect(state.needsUserKeys()).toBe(true)
    })

    it('asks for the email when the profile has none, and not when it has one', async () => {
      providers(platformProviders, mineWithKey)
      api.getStatus.mockReturnValue(of(managed()))
      getMeUser.mockReturnValue(of({ ...me, email: undefined }))
      await state.init()
      await state.checkUserKeys()
      expect(state.needsEmail()).toBe(true)
      expect(state.needsProfile()).toBe(true)
      expect(state.loadingReady()).toBe(false)
    })

    it('treats a blank email as missing', async () => {
      providers(platformProviders, mineWithKey)
      api.getStatus.mockReturnValue(of(managed()))
      getMeUser.mockReturnValue(of({ ...me, email: '  ' }))
      await state.init()
      await state.checkUserKeys()
      expect(state.needsEmail()).toBe(true)
    })

    it('asks for nothing when the user has an email and a key', async () => {
      providers(platformProviders, mineWithKey)
      getMeUser.mockReturnValue(of(me))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()
      expect(state.needsEmail()).toBe(false)
      expect(state.needsUserKeys()).toBe(false)
      expect(state.needsProfile()).toBe(false)
      expect(state.loadingReady()).toBe(true)
    })

    it('updates a keyless global provider of the user instead of creating a duplicate', async () => {
      providers(platformProviders, [{ id: 'u-2', name: 'openai', apiType: 'OpenAI' }])
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()
      expect(state.userKeys().find((k) => k.name === 'openai')?.existingId).toBe('u-2')
    })

    it('is not ready while the check is not done, nor while something is still asked', async () => {
      providers(platformProviders, [])
      api.getStatus.mockReturnValue(of(managed()))
      expect(state.loadingReady()).toBe(false)
      await state.init()
      await state.checkUserKeys()
      expect(state.needsProfile()).toBe(true)
      expect(state.loadingReady()).toBe(false)
    })

    it('asks for nothing and hands over when there is no platform provider at all, even with no email', async () => {
      getMeUser.mockReturnValue(of({ ...me, email: undefined }))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()
      expect(state.needsEmail()).toBe(false)
      expect(state.needsUserKeys()).toBe(false)
      expect(state.loadingReady()).toBe(true)
    })

    it('does not hold the user back when the check fails', async () => {
      jest.spyOn(console, 'warn').mockImplementation(() => undefined)
      listAiProvider.mockReturnValue(throwError(() => new Error('boom')))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()
      expect(state.needsProfile()).toBe(false)
      expect(state.loadingReady()).toBe(true)
    })

    it('never asks outside the managed loading page (laptop install, wizard, preview)', async () => {
      providers(platformProviders, [])
      api.getStatus.mockReturnValue(
        of(
          baseStatus({
            setupDone: true,
            apply: { status: 'done' },
            steps: { ...baseStatus().steps, agentos: { state: 'done' } },
          })
        )
      )
      await state.init()
      expect(listAiProvider).not.toHaveBeenCalled()

      api.getStatus.mockReturnValue(of(managed()))
      await state.init('nofresh')
      expect(listAiProvider).not.toHaveBeenCalled()
    })

    it('saves the typed keys as user-global providers and lets the user through', async () => {
      providers(platformProviders, [{ id: 'u-2', name: 'openai', apiType: 'OpenAI' }])
      getMeUser.mockReturnValue(of(me))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()

      expect(state.profileValid()).toBe(false)
      state.setUserKey('anthropic', ' sk-ant-long-enough ')
      state.setUserKey('openai', 'sk-openai-long-enough')
      expect(state.profileValid()).toBe(true)
      await state.submitProfile()
      expect(updateUser).not.toHaveBeenCalled()

      expect(createAiProvider).toHaveBeenCalledWith({
        name: 'anthropic',
        apiType: 'Anthropic',
        apiKey: 'sk-ant-long-enough',
        userId: 'user-1',
      })
      expect(updateAiProvider).toHaveBeenCalledWith('u-2', {
        name: 'openai',
        apiType: 'OpenAI',
        apiKey: 'sk-openai-long-enough',
        userId: 'user-1',
      })
      expect(state.needsUserKeys()).toBe(false)
      expect(state.loadingReady()).toBe(true)
    })

    it('leaves empty keys unset and keeps the failed ones with a message', async () => {
      jest.spyOn(console, 'warn').mockImplementation(() => undefined)
      providers(platformProviders, [])
      getMeUser.mockReturnValue(of(me))
      createAiProvider.mockReturnValue(throwError(() => ({ status: 409 })))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()

      state.setUserKey('anthropic', 'sk-ant-long-enough')
      await state.submitProfile()

      expect(createAiProvider).toHaveBeenCalledTimes(1)
      expect(state.userKeys().map((k) => k.name)).toEqual(['anthropic'])
      expect(state.error()).toMatch(/anthropic/)
      expect(state.loadingReady()).toBe(false)
    })

    it('requires a plausible email when the profile has none, and at least one plausible key', async () => {
      providers(platformProviders, [])
      getMeUser.mockReturnValue(of({ ...me, email: undefined }))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()

      state.setUserKey('anthropic', 'sk-ant-long-enough')
      expect(state.profileValid()).toBe(false)
      state.setProfileEmail('nope')
      expect(state.profileValid()).toBe(false)
      state.setProfileEmail(' Me@Acme.com ')
      expect(state.profileValid()).toBe(true)
      state.setUserKey('openai', 'short')
      expect(state.profileValid()).toBe(false)
    })

    it('saves the email first, keeping the other fields of the profile, then the keys', async () => {
      providers(platformProviders, [])
      getMeUser.mockReturnValue(of({ ...me, email: undefined, bio: 'hi', preferredLanguage: 'fr', isAdmin: true }))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()

      state.setProfileEmail(' Me@Acme.com ')
      state.setUserKey('anthropic', 'sk-ant-long-enough')
      await state.submitProfile()

      expect(updateUser).toHaveBeenCalledWith('user-1', {
        id: 'user-1',
        externalId: 'me@acme.com',
        email: 'me@acme.com',
        firstname: 'Me',
        bio: 'hi',
        preferredLanguage: 'fr',
        isAdmin: true,
      })
      expect(createAiProvider).toHaveBeenCalledTimes(1)
      expect(getMeUser).toHaveBeenCalledTimes(1)
      expect(state.needsEmail()).toBe(false)
      expect(state.loadingReady()).toBe(true)
    })

    it('stops at the email when it cannot be saved, and still asks for it', async () => {
      jest.spyOn(console, 'warn').mockImplementation(() => undefined)
      providers(platformProviders, [])
      getMeUser.mockReturnValue(of({ ...me, email: undefined }))
      updateUser.mockReturnValue(throwError(() => ({ status: 500 })))
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()

      state.setProfileEmail('me@acme.com')
      state.setUserKey('anthropic', 'sk-ant-long-enough')
      await state.submitProfile()

      expect(state.error()).toBe('Could not save your email.')
      expect(createAiProvider).not.toHaveBeenCalled()
      expect(state.needsEmail()).toBe(true)
      expect(state.busy()).toBe(false)
      expect(state.loadingReady()).toBe(false)
    })

    it('does nothing while the profile is not valid', async () => {
      providers(platformProviders, [])
      api.getStatus.mockReturnValue(of(managed()))
      await state.init()
      await state.checkUserKeys()
      await state.submitProfile()
      expect(updateUser).not.toHaveBeenCalled()
      expect(createAiProvider).not.toHaveBeenCalled()
    })
  })

  describe('preview (?preview=fresh|nofresh)', () => {
    const installed = () =>
      baseStatus({
        setupDone: true,
        apply: { status: 'done' },
        namespaceId: 'ns-1',
        steps: { ...baseStatus().steps, agentos: { state: 'done' } },
      })

    it('only knows fresh and nofresh', () => {
      expect(parseWelcomePreview('fresh')).toBe('fresh')
      expect(parseWelcomePreview('nofresh')).toBe('nofresh')
      expect(parseWelcomePreview('whatever')).toBeNull()
      expect(parseWelcomePreview(null)).toBeNull()
    })

    it('shows the wizard from its first step on an install that is already set up', async () => {
      api.getStatus.mockReturnValue(of(installed()))
      await state.init('fresh')
      expect(state.mode()).toBe('wizard')
      expect(state.step()).toBe('email')
      expect(state.preview()).toBe('fresh')
    })

    it('shows the loading page on a first run, which would show the wizard', async () => {
      await state.init('nofresh')
      expect(state.mode()).toBe('loading')
      expect(state.step()).toBe('progress')
      expect(state.preview()).toBe('nofresh')
    })

    it('walks through every step of the wizard without sending anything', async () => {
      api.getStatus.mockReturnValue(of(installed()))
      await state.init('fresh')

      state.email.set('me@acme.com')
      await state.submitEmail()
      expect(state.step()).toBe('providers')

      state.toggleProvider('anthropic')
      state.setApiKey('anthropic', 'sk-ant-long-enough')
      state.submitProviders()
      expect(state.step()).toBe('folder')

      await state.submitFolderAndApply()
      expect(state.step()).toBe('progress')

      expect(api.saveIdentity).not.toHaveBeenCalled()
      expect(api.apply).not.toHaveBeenCalled()
    })

    it('can go back to the email step, although the install is set up', async () => {
      api.getStatus.mockReturnValue(of(installed()))
      await state.init('fresh')
      state.email.set('me@acme.com')
      await state.submitEmail()
      expect(state.emailLocked()).toBe(false)
      state.back()
      expect(state.step()).toBe('email')
    })

    it('changes nothing without a preview', async () => {
      api.getStatus.mockReturnValue(of(installed()))
      await state.init()
      expect(state.mode()).toBe('loading')
      expect(state.preview()).toBeNull()
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
