import { TestBed } from '@angular/core/testing'
import {
  AgentConfig,
  AgentConfigControllerService,
  IntegrationConfig,
  IntegrationConfigControllerService,
} from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { FactoryApiService, FactoryLaunchRequest } from './factory-api.service'
import { extractAgentRoot, FactoryLaunchStateService } from './factory-launch-state.service'
import { UserStateService } from './user-state.service'

function makeUser(externalId: string, email = 'user@example.com') {
  return { id: 'user-id', externalId, email }
}

function makeAgent(name: string, integrations: Record<string, string[]> = {}): AgentConfig {
  return { name, enabled: true, integrations }
}

function makeFileAccessIntegration(name: string, rootPath: string, namespaceId = 'ns-1'): IntegrationConfig {
  return { id: `int-${name}`, name, integrationType: 'FILE_ACCESS', namespaceId, parameters: { rootPath } }
}

describe('FactoryLaunchStateService', () => {
  let api: { launchRun: jest.Mock }
  let agentConfigController: { searchAgentConfig: jest.Mock }
  let integrationConfigController: { listIntegrationConfig: jest.Mock }
  let userState: UserStateService

  function createService(): FactoryLaunchStateService {
    api = { launchRun: jest.fn() }
    agentConfigController = { searchAgentConfig: jest.fn() }
    integrationConfigController = { listIntegrationConfig: jest.fn() }

    TestBed.configureTestingModule({
      providers: [
        FactoryLaunchStateService,
        UserStateService,
        { provide: FactoryApiService, useValue: api },
        { provide: AgentConfigControllerService, useValue: agentConfigController },
        { provide: IntegrationConfigControllerService, useValue: integrationConfigController },
      ],
    })

    userState = TestBed.inject(UserStateService)
    return TestBed.inject(FactoryLaunchStateService)
  }

  afterEach(() => TestBed.resetTestingModule())

  // ---------------------------------------------------------------------------
  // extractAgentRoot (pure helper)
  // ---------------------------------------------------------------------------

  describe('extractAgentRoot', () => {
    it('returns ok with rootPath when a FILE_ACCESS integration matches', () => {
      const agent = makeAgent('editor', { 'my-files': [] })
      const byName = new Map([['my-files', makeFileAccessIntegration('my-files', '/repo/sprint')]])
      const result = extractAgentRoot(agent, byName)
      expect(result).toEqual({ ok: true, rootPath: '/repo/sprint' })
    })

    it('returns failure when agent has no non-reserved integrations', () => {
      const agent = makeAgent('bare', { QUERY_USER: [], CASE_FILE_EXCHANGE: [] })
      const result = extractAgentRoot(agent, new Map())
      expect(result.ok).toBe(false)
      expect((result as any).reason).toContain('no non-reserved integrations')
    })

    it('returns failure when no FILE_ACCESS integration is in the map', () => {
      const agent = makeAgent('editor', { 'my-files': [] })
      const byName = new Map([['my-files', { id: 'x', name: 'my-files', integrationType: 'MEMORY', parameters: {} }]])
      const result = extractAgentRoot(agent, byName)
      expect(result.ok).toBe(false)
      expect((result as any).reason).toContain('no FILE_ACCESS')
    })

    it('returns failure when FILE_ACCESS integration has no rootPath', () => {
      const agent = makeAgent('editor', { 'my-files': [] })
      const byName = new Map([
        ['my-files', { id: 'x', name: 'my-files', integrationType: 'FILE_ACCESS', parameters: {} }],
      ])
      const result = extractAgentRoot(agent, byName)
      expect(result.ok).toBe(false)
      expect((result as any).reason).toContain('no rootPath')
    })
  })

  // ---------------------------------------------------------------------------
  // loadAgents — Defect 1 fixes
  // ---------------------------------------------------------------------------

  describe('loadAgents', () => {
    it('passes the current user external ID (not an empty string) to searchAgentConfig', () => {
      const service = createService()
      userState.currentUser.set(makeUser('bvaldes'))
      agentConfigController.searchAgentConfig.mockReturnValue(of([]))

      service.loadAgents('ns-1')

      const call = agentConfigController.searchAgentConfig.mock.calls[0][0]
      expect(call.userExternalId).toBe('bvaldes')
      expect(call.userExternalId).not.toBe('')
    })

    it('falls back to email when externalId is absent', () => {
      const service = createService()
      userState.currentUser.set({ id: 'u', externalId: undefined as any, email: 'me@example.com' })
      agentConfigController.searchAgentConfig.mockReturnValue(of([]))

      service.loadAgents('ns-1')

      expect(agentConfigController.searchAgentConfig.mock.calls[0][0].userExternalId).toBe('me@example.com')
    })

    it('calls loadMe() when user is not loaded, then searches with the real external ID', () => {
      const service = createService()
      userState.currentUser.set(null)
      const loadMeSpy = jest.spyOn(userState, 'loadMe').mockImplementation(() => {
        userState.currentUser.set(makeUser('loaded-user'))
        return of(makeUser('loaded-user') as any)
      })
      agentConfigController.searchAgentConfig.mockReturnValue(of([]))

      service.loadAgents('ns-1')

      expect(loadMeSpy).toHaveBeenCalled()
      const call = agentConfigController.searchAgentConfig.mock.calls[0][0]
      expect(call.userExternalId).toBe('loaded-user')
      expect(call.userExternalId).not.toBe('')
    })

    it('sets agentsError on API failure', () => {
      const service = createService()
      userState.currentUser.set(makeUser('bvaldes'))
      agentConfigController.searchAgentConfig.mockReturnValue(throwError(() => new Error('unreachable')))

      service.loadAgents('ns-1')

      expect(service.agentsError()).toContain('AgentOS unreachable')
    })

    it('discards stale response when namespace changes before call returns', () => {
      const service = createService()
      userState.currentUser.set(makeUser('bvaldes'))

      let resolveFirst!: (v: AgentConfig[]) => void
      agentConfigController.searchAgentConfig
        .mockReturnValueOnce(
          new (require('rxjs').Observable)((obs: any) => {
            resolveFirst = (v) => {
              obs.next(v)
              obs.complete()
            }
          })
        )
        .mockReturnValueOnce(of([{ name: 'ns2-agent', enabled: true }]))

      service.loadAgents('ns-1')
      service.loadAgents('ns-2')
      resolveFirst([{ name: 'ns1-stale-agent', enabled: true }])

      expect(service.agents().map((a) => a.name)).toEqual(['ns2-agent'])
    })

    it('sets agentsError and does NOT call searchAgentConfig when loadMe fails', () => {
      const service = createService()
      userState.currentUser.set(null)
      jest.spyOn(userState, 'loadMe').mockReturnValue(throwError(() => new Error('auth error')))
      agentConfigController.searchAgentConfig.mockReturnValue(of([]))

      service.loadAgents('ns-1')

      expect(service.agentsLoading()).toBe(false)
      expect(service.agentsError()).toContain('AgentOS unreachable')
      expect(agentConfigController.searchAgentConfig).not.toHaveBeenCalled()
    })
  })

  // ---------------------------------------------------------------------------
  // deriveRootForAgent — single agent (fix-loop / agentos-smoke)
  // ---------------------------------------------------------------------------

  describe('deriveRootForAgent', () => {
    const agent = makeAgent('factory-editor', { 'my-files': [] })

    it('populates derivedRoot from the FILE_ACCESS integration rootPath', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([makeFileAccessIntegration('my-files', '/path/to/sprint')])
      )

      service.deriveRootForAgent('ns-1', agent)

      expect(service.derivedRoot()).toBe('/path/to/sprint')
      expect(service.rootError()).toBeNull()
      expect(service.rootLoading()).toBe(false)
    })

    it('passes namespaceId to listIntegrationConfig', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([makeFileAccessIntegration('my-files', '/repo')])
      )

      service.deriveRootForAgent('ns-42', agent)

      expect(integrationConfigController.listIntegrationConfig).toHaveBeenCalledWith('ns-42')
    })

    it('sets rootError when no FILE_ACCESS integration is found', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([{ id: 'x', name: 'my-files', integrationType: 'MEMORY', namespaceId: 'ns-1', parameters: {} }])
      )

      service.deriveRootForAgent('ns-1', agent)

      expect(service.derivedRoot()).toBeNull()
      expect(service.rootError()).toBeTruthy()
    })

    it('sets rootError when FILE_ACCESS has no rootPath', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([{ id: 'x', name: 'my-files', integrationType: 'FILE_ACCESS', namespaceId: 'ns-1', parameters: {} }])
      )

      service.deriveRootForAgent('ns-1', agent)

      expect(service.derivedRoot()).toBeNull()
      expect(service.rootError()).toBeTruthy()
    })

    it('sets rootError when listIntegrationConfig fails', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(throwError(() => new Error('net')))

      service.deriveRootForAgent('ns-1', agent)

      expect(service.derivedRoot()).toBeNull()
      expect(service.rootError()).toContain('manually')
      expect(service.rootLoading()).toBe(false)
    })
  })

  // ---------------------------------------------------------------------------
  // deriveTwoAgentRoots — us-loop colocation enforcement
  // ---------------------------------------------------------------------------

  describe('deriveTwoAgentRoots', () => {
    const analyst = makeAgent('factory-analyst', { 'analyst-files': [] })
    const editor = makeAgent('factory-editor', { 'editor-files': [] })
    const sharedRoot = '/repo/sprint'

    it('sets derivedRoot to the editor root when both roots match', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([
          makeFileAccessIntegration('analyst-files', sharedRoot),
          makeFileAccessIntegration('editor-files', sharedRoot),
        ])
      )

      service.deriveTwoAgentRoots('ns-1', analyst, editor)

      expect(service.derivedRoot()).toBe(sharedRoot)
      expect(service.rootError()).toBeNull()
      expect(service.rootLoading()).toBe(false)
    })

    it('accepts roots that differ only by a trailing slash', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([
          makeFileAccessIntegration('analyst-files', '/repo/sprint/'),
          makeFileAccessIntegration('editor-files', '/repo/sprint'),
        ])
      )

      service.deriveTwoAgentRoots('ns-1', analyst, editor)

      expect(service.derivedRoot()).toBe('/repo/sprint')
      expect(service.rootError()).toBeNull()
    })

    it('sets rootError naming both roles when analyst root !== editor root', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([
          makeFileAccessIntegration('analyst-files', '/repo/sprint'),
          makeFileAccessIntegration('editor-files', '/repo/coday'),
        ])
      )

      service.deriveTwoAgentRoots('ns-1', analyst, editor)

      expect(service.derivedRoot()).toBeNull()
      const err = service.rootError()
      expect(err).toContain('factory-analyst')
      expect(err).toContain('factory-editor')
      expect(err).toContain('/repo/sprint')
      expect(err).toContain('/repo/coday')
    })

    it('sets rootError naming the analyst when analyst has no FILE_ACCESS integration', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([makeFileAccessIntegration('editor-files', sharedRoot)])
        // analyst-files not in the list
      )

      service.deriveTwoAgentRoots('ns-1', analyst, editor)

      expect(service.derivedRoot()).toBeNull()
      expect(service.rootError()).toContain('factory-analyst')
    })

    it('sets rootError naming the editor when editor has no FILE_ACCESS integration', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([makeFileAccessIntegration('analyst-files', sharedRoot)])
        // editor-files not in the list
      )

      service.deriveTwoAgentRoots('ns-1', analyst, editor)

      expect(service.derivedRoot()).toBeNull()
      expect(service.rootError()).toContain('factory-editor')
    })

    it('fetches integrations only once (shared call for both agents)', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([
          makeFileAccessIntegration('analyst-files', sharedRoot),
          makeFileAccessIntegration('editor-files', sharedRoot),
        ])
      )

      service.deriveTwoAgentRoots('ns-1', analyst, editor)

      expect(integrationConfigController.listIntegrationConfig).toHaveBeenCalledTimes(1)
    })

    it('sets rootError when listIntegrationConfig fails', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(throwError(() => new Error('net')))

      service.deriveTwoAgentRoots('ns-1', analyst, editor)

      expect(service.derivedRoot()).toBeNull()
      expect(service.rootError()).toContain('manually')
    })
  })

  // ---------------------------------------------------------------------------
  // clearDerivedRoot
  // ---------------------------------------------------------------------------

  describe('clearDerivedRoot', () => {
    it('resets all root signals', () => {
      const service = createService()
      integrationConfigController.listIntegrationConfig.mockReturnValue(
        of([makeFileAccessIntegration('my-files', '/repo')])
      )
      service.deriveRootForAgent('ns-1', makeAgent('editor', { 'my-files': [] }))
      expect(service.derivedRoot()).toBe('/repo')

      service.clearDerivedRoot()

      expect(service.derivedRoot()).toBeNull()
      expect(service.rootError()).toBeNull()
      expect(service.rootLoading()).toBe(false)
    })
  })

  // ---------------------------------------------------------------------------
  // usableAgents
  // ---------------------------------------------------------------------------

  describe('usableAgents', () => {
    it('excludes disabled agents and orchestrators with subAgents', () => {
      const service = createService()
      userState.currentUser.set(makeUser('bvaldes'))
      agentConfigController.searchAgentConfig.mockReturnValue(
        of([
          { name: 'editor', enabled: true },
          { name: 'disabled', enabled: false },
          { name: 'orchestrator', enabled: true, subAgents: ['editor'] },
        ])
      )
      service.loadAgents('ns-1')

      expect(service.usableAgents().map((a) => a.name)).toEqual(['editor'])
    })
  })

  // ---------------------------------------------------------------------------
  // launch
  // ---------------------------------------------------------------------------

  describe('launch', () => {
    const request: FactoryLaunchRequest = {
      workflow: 'fix-loop',
      FACTORY_NAMESPACE_ID: 'ns-1',
      FACTORY_TASK: 'Fix bug',
      FACTORY_AGENT: 'factory-editor',
    }

    it('transitions to success and stores result', () => {
      const service = createService()
      api.launchRun.mockReturnValue(of({ pid: 123, runId: 'run-abc' }))

      service.launch(request)

      expect(service.launchStatus()).toBe('success')
      expect(service.launchResult()).toEqual({ pid: 123, runId: 'run-abc' })
    })

    it('transitions to error on 400', () => {
      const service = createService()
      api.launchRun.mockReturnValue(throwError(() => ({ status: 400, error: { error: 'FACTORY_AGENT manquant' } })))

      service.launch(request)

      expect(service.launchStatus()).toBe('error')
      expect(service.launchError()).toBe('FACTORY_AGENT manquant')
    })

    it('shows connectivity message when factory server unreachable', () => {
      const service = createService()
      api.launchRun.mockReturnValue(throwError(() => ({ status: 0 })))

      service.launch(request)

      expect(service.launchError()).toContain('Factory server unreachable')
    })

    it('does not double-submit while launching', () => {
      const service = createService()
      api.launchRun.mockReturnValue(new (require('rxjs').Subject)())

      service.launch(request)
      service.launch(request)

      expect(api.launchRun).toHaveBeenCalledTimes(1)
    })
  })

  // ---------------------------------------------------------------------------
  // reset
  // ---------------------------------------------------------------------------

  describe('reset', () => {
    it('clears all state including derived root', () => {
      const service = createService()
      api.launchRun.mockReturnValue(of({ pid: 1, runId: 'r' }))
      service.launch({ workflow: 'fix-loop', FACTORY_NAMESPACE_ID: 'n', FACTORY_TASK: 't', FACTORY_AGENT: 'a' })

      service.reset()

      expect(service.launchStatus()).toBe('idle')
      expect(service.agents()).toEqual([])
      expect(service.derivedRoot()).toBeNull()
      expect(service.rootError()).toBeNull()
    })
  })

  // ---------------------------------------------------------------------------
  // Static helpers
  // ---------------------------------------------------------------------------

  describe('static helpers', () => {
    it('isSingleAgent: fix-loop and agentos-smoke', () => {
      expect(FactoryLaunchStateService.isSingleAgent('fix-loop')).toBe(true)
      expect(FactoryLaunchStateService.isSingleAgent('agentos-smoke')).toBe(true)
      expect(FactoryLaunchStateService.isSingleAgent('us-loop')).toBe(false)
      expect(FactoryLaunchStateService.isSingleAgent('backend-oracle-check')).toBe(false)
    })

    it('isTwoAgent: us-loop only', () => {
      expect(FactoryLaunchStateService.isTwoAgent('us-loop')).toBe(true)
      expect(FactoryLaunchStateService.isTwoAgent('fix-loop')).toBe(false)
    })

    it('isNoAgent: backend-oracle-check only', () => {
      expect(FactoryLaunchStateService.isNoAgent('backend-oracle-check')).toBe(true)
      expect(FactoryLaunchStateService.isNoAgent('fix-loop')).toBe(false)
    })
  })
})
