import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfigControllerService, CaseControllerService } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { CaseStateService } from '../../services/case-state.service'
import { AgentConfigLaunchComponent } from './agent-config-launch.component'

/**
 * Focused on the loopData of the launch page: a `steps` loopConfig reads `${loopData.key}` from the
 * session context of the start message, a legacy (`search` / `act`) one needs none.
 */
describe('AgentConfigLaunchComponent (loopData)', () => {
  let caseController: { createCase: jest.Mock; addMessageCase: jest.Mock }
  let agentConfigController: { getByIdAgentConfig: jest.Mock }
  let caseState: { addCase: jest.Mock }
  let router: { navigate: jest.Mock }
  let fixture: ComponentFixture<AgentConfigLaunchComponent>
  let component: AgentConfigLaunchComponent

  const stepsLoopConfig = {
    steps: [
      { id: 'search', type: 'SEARCH', tool: '${loopData.searchToolName}', args: '${loopData.searchToolArgs}' },
      {
        id: 'start-cases',
        type: 'START_CASES',
        tool: 'FreeCaseStarterForUser',
        args: { agentName: '${loopData.agentName}', prompt: '${loopData.prompt}' },
        scheduledPromptId: '${loopData.scheduledPromptId}',
      },
    ],
  }

  const legacyLoopConfig = {
    search: { tool: 'SearchTalents', params: { next: null } },
    act: { agentName: 'ProfileCaretaker', promptTemplate: 'Review your profile.' },
  }

  const internals = () =>
    component as unknown as {
      isStepsFormat: () => boolean
      loopDataJson: { (): string; set: (v: string) => void }
      loopDataInvalid: () => boolean
      launch: () => Promise<void>
    }

  function init(loopConfig: unknown): void {
    agentConfigController.getByIdAgentConfig.mockReturnValue(of({ id: 'a-1', name: 'TalentLoop', loopConfig }))
    component.ngOnInit()
  }

  beforeEach(() => {
    caseController = {
      createCase: jest.fn().mockReturnValue(of({ id: 'case-1' })),
      addMessageCase: jest.fn().mockReturnValue(of(undefined)),
    }
    agentConfigController = { getByIdAgentConfig: jest.fn() }
    caseState = { addCase: jest.fn() }
    router = { navigate: jest.fn() }

    TestBed.configureTestingModule({
      imports: [AgentConfigLaunchComponent],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { params: { namespaceId: 'ns-1', agentConfigId: 'a-1' } } } },
        { provide: Router, useValue: router },
        { provide: CaseControllerService, useValue: caseController },
        { provide: CaseStateService, useValue: caseState },
        { provide: AgentConfigControllerService, useValue: agentConfigController },
      ],
    })

    fixture = TestBed.createComponent(AgentConfigLaunchComponent)
    component = fixture.componentInstance
  })

  describe('steps format', () => {
    it('sends the edited loopData in the session context of the start message', async () => {
      init(stepsLoopConfig)
      expect(internals().isStepsFormat()).toBe(true)
      internals().loopDataJson.set(
        JSON.stringify({
          searchToolName: 'WHOZ__SearchTalents',
          searchToolArgs: { limit: 10 },
          agentName: 'ProfileCaretaker',
          prompt: 'Review your profile.',
          scheduledPromptId: '4f1c0f0e-6f43-4a39-9b6f-0a5a4a1b2c3d',
        })
      )

      await internals().launch()

      expect(caseController.addMessageCase).toHaveBeenCalledWith('case-1', {
        content: '@TalentLoop start',
        sessionContext: {
          loopData: {
            searchToolName: 'WHOZ__SearchTalents',
            searchToolArgs: { limit: 10 },
            agentName: 'ProfileCaretaker',
            prompt: 'Review your profile.',
            scheduledPromptId: '4f1c0f0e-6f43-4a39-9b6f-0a5a4a1b2c3d',
          },
        },
      })
      expect(router.navigate).toHaveBeenCalled()
    })

    it('prefills the loopData with the skeleton of the keys a scheduled prompt sends', () => {
      init(stepsLoopConfig)

      expect(JSON.parse(internals().loopDataJson())).toEqual({
        searchToolName: '',
        searchToolArgs: {},
        agentName: '',
        prompt: '',
        scheduledPromptId: '',
      })
    })

    it.each(['{ not json', '[1,2]', '"x"', 'null'])('blocks the launch while loopData is %s', async (text) => {
      init(stepsLoopConfig)
      internals().loopDataJson.set(text)

      expect(internals().loopDataInvalid()).toBe(true)
      await internals().launch()

      expect(caseController.createCase).not.toHaveBeenCalled()
      expect(caseController.addMessageCase).not.toHaveBeenCalled()
    })
  })

  describe('legacy format', () => {
    it('sends the start message without any session context', async () => {
      init(legacyLoopConfig)
      expect(internals().isStepsFormat()).toBe(false)

      await internals().launch()

      expect(caseController.addMessageCase).toHaveBeenCalledWith('case-1', { content: '@TalentLoop start' })
    })

    it('is not blocked by the loopData text, which is not used', async () => {
      init(legacyLoopConfig)
      internals().loopDataJson.set('{ not json')

      expect(internals().loopDataInvalid()).toBe(false)
      await internals().launch()

      expect(caseController.addMessageCase).toHaveBeenCalledTimes(1)
    })
  })

  it('treats an agent without loopConfig as the legacy format', async () => {
    init(null)

    await internals().launch()

    expect(internals().isStepsFormat()).toBe(false)
    expect(caseController.addMessageCase).toHaveBeenCalledWith('case-1', { content: '@TalentLoop start' })
  })
})
