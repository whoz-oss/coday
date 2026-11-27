import { TestBed } from '@angular/core/testing'
import { NamespaceDefaultAgentControllerService } from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of, throwError } from 'rxjs'
import { DefaultAgentStateService } from './default-agent-state.service'

describe('DefaultAgentStateService', () => {
  let api: { getNamespaceDefaultAgent: jest.Mock }

  function create(): DefaultAgentStateService {
    TestBed.configureTestingModule({
      providers: [{ provide: NamespaceDefaultAgentControllerService, useValue: api }],
    })
    return TestBed.inject(DefaultAgentStateService)
  }

  beforeEach(() => {
    api = { getNamespaceDefaultAgent: jest.fn() }
  })

  afterEach(() => TestBed.resetTestingModule())

  it('returns the agent the server resolved for the namespace', async () => {
    api.getNamespaceDefaultAgent.mockReturnValue(of({ agentName: 'Archay' }))

    expect(await firstValueFrom(create().resolve('ns-1'))).toBe('Archay')
    expect(api.getNamespaceDefaultAgent).toHaveBeenCalledWith('ns-1')
  })

  it('returns null when no agent would answer', async () => {
    api.getNamespaceDefaultAgent.mockReturnValue(of({}))

    expect(await firstValueFrom(create().resolve('ns-1'))).toBeNull()
  })

  it('returns null when the answer cannot be read', async () => {
    api.getNamespaceDefaultAgent.mockReturnValue(throwError(() => ({ status: 500 })))

    expect(await firstValueFrom(create().resolve('ns-1'))).toBeNull()
  })
})
