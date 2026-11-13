import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfig,
  AgentConfigControllerService,
  AgentConfigExecutionModeEnum,
  ScheduledPrompt,
} from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { ScheduledPromptStateService } from '../../services/scheduled-prompt-state.service'
import { ScheduledPromptFormComponent } from './scheduled-prompt-form.component'

/**
 * Focused on the loop mode of the scheduled prompt form: the three loop fields round-trip through
 * ScheduledPrompt.agentLoopName / loopConfiguration, set together or neither.
 *
 * The component class is driven directly (ngOnInit / submit) without rendering the template.
 */
describe('ScheduledPromptFormComponent (loop mode)', () => {
  let state: { getById: jest.Mock; create: jest.Mock; update: jest.Mock }
  let agentConfigController: { listByParentAgentConfig: jest.Mock; listPlatformAgentsAgentConfig: jest.Mock }
  let router: { navigate: jest.Mock }
  let routeScheduledPromptId: string | null
  let fixture: ComponentFixture<ScheduledPromptFormComponent>
  let component: ScheduledPromptFormComponent

  type Control<T> = { setValue: (v: T) => void; value: T; invalid: boolean }

  const internals = () =>
    component as unknown as {
      form: { invalid: boolean }
      nameControl: Control<string>
      agentConfigIdControl: Control<string>
      promptContentControl: Control<string>
      loopEnabledControl: Control<boolean>
      agentLoopNameControl: Control<string>
      searchToolNameControl: Control<string>
      searchToolArgsJsonControl: Control<string> & { hasError: (code: string) => boolean }
      loopAgentSuggestions: () => string[]
      isLoopMode: () => boolean
      submit: () => void
    }

  const existingPrompt = (overrides: Partial<ScheduledPrompt> = {}): ScheduledPrompt =>
    ({
      id: 'sp-1',
      namespaceId: 'ns-1',
      name: 'Weekly',
      agentConfigId: 'a-1',
      promptContent: 'Do the thing',
      enabled: true,
      removed: false,
      recurrence: { unit: 'WEEK', days: [], timeUtc: '09:00' },
      planning: { startDate: '2026-01-01', endType: 'NEVER' },
      ...overrides,
    }) as ScheduledPrompt

  /** What actually goes over the wire: undefined properties are dropped by JSON serialisation. */
  const wire = (payload: ScheduledPrompt) => JSON.parse(JSON.stringify(payload)) as Record<string, unknown>

  /** Fills the mandatory fields of a create form. */
  function fillBasics(): void {
    internals().nameControl.setValue('Weekly')
    internals().agentConfigIdControl.setValue('a-1')
    internals().promptContentControl.setValue('Do the thing')
  }

  beforeEach(() => {
    routeScheduledPromptId = null
    state = {
      getById: jest.fn(),
      create: jest.fn().mockReturnValue(of({})),
      update: jest.fn().mockReturnValue(of({})),
    }
    agentConfigController = {
      listByParentAgentConfig: jest.fn().mockReturnValue(of([])),
      listPlatformAgentsAgentConfig: jest.fn().mockReturnValue(of([])),
    }
    router = { navigate: jest.fn() }
    const activatedRoute = {
      snapshot: {
        params: { namespaceId: 'ns-1' },
        paramMap: { get: (key: string) => (key === 'scheduledPromptId' ? routeScheduledPromptId : null) },
      },
    }

    TestBed.configureTestingModule({
      imports: [ScheduledPromptFormComponent],
      providers: [
        { provide: ActivatedRoute, useValue: activatedRoute },
        { provide: Router, useValue: router },
        { provide: ScheduledPromptStateService, useValue: state },
        { provide: AgentConfigControllerService, useValue: agentConfigController },
      ],
    })

    fixture = TestBed.createComponent(ScheduledPromptFormComponent)
    component = fixture.componentInstance
  })

  describe('create payload', () => {
    it('maps the loop fields and parses the search arguments', () => {
      component.ngOnInit()
      fillBasics()
      internals().loopEnabledControl.setValue(true)
      internals().agentLoopNameControl.setValue('  TalentLoop  ')
      internals().searchToolNameControl.setValue(' WHOZ__SearchTalents ')
      internals().searchToolArgsJsonControl.setValue('{ "status": ["ACTIVE"], "limit": 10 }')

      internals().submit()

      const payload = state.create.mock.calls[0][0] as ScheduledPrompt
      expect(payload.agentLoopName).toBe('TalentLoop')
      expect(payload.loopConfiguration).toEqual({
        searchToolName: 'WHOZ__SearchTalents',
        searchToolArgs: { status: ['ACTIVE'], limit: 10 },
      })
      expect(router.navigate).toHaveBeenCalled()
    })

    it('sends null search arguments when left empty (= {} server-side)', () => {
      component.ngOnInit()
      fillBasics()
      internals().loopEnabledControl.setValue(true)
      internals().agentLoopNameControl.setValue('TalentLoop')
      internals().searchToolNameControl.setValue('WHOZ__SearchTalents')

      internals().submit()

      expect((state.create.mock.calls[0][0] as ScheduledPrompt).loopConfiguration?.searchToolArgs).toBeNull()
    })

    it('sends no loop field without loop mode, even if they were typed then switched off', () => {
      component.ngOnInit()
      fillBasics()
      internals().loopEnabledControl.setValue(true)
      internals().agentLoopNameControl.setValue('TalentLoop')
      internals().searchToolNameControl.setValue('WHOZ__SearchTalents')
      internals().loopEnabledControl.setValue(false)

      internals().submit()

      const payload = state.create.mock.calls[0][0] as ScheduledPrompt
      expect(payload.agentLoopName).toBeUndefined()
      expect(payload.loopConfiguration).toBeUndefined()
      expect(wire(payload)).not.toHaveProperty('agentLoopName')
      expect(wire(payload)).not.toHaveProperty('loopConfiguration')
    })
  })

  describe('edit', () => {
    it('hydrates the loop fields of a prompt in loop mode', () => {
      routeScheduledPromptId = 'sp-1'
      state.getById.mockReturnValue(
        of(
          existingPrompt({
            agentLoopName: 'TalentLoop',
            loopConfiguration: { searchToolName: 'WHOZ__SearchTalents', searchToolArgs: { limit: 10 } },
          })
        )
      )

      component.ngOnInit()

      expect(internals().loopEnabledControl.value).toBe(true)
      expect(internals().isLoopMode()).toBe(true)
      expect(internals().agentLoopNameControl.value).toBe('TalentLoop')
      expect(internals().searchToolNameControl.value).toBe('WHOZ__SearchTalents')
      expect(JSON.parse(internals().searchToolArgsJsonControl.value)).toEqual({ limit: 10 })
    })

    it('keeps loop mode off, with empty fields, for a prompt in historical mode', () => {
      routeScheduledPromptId = 'sp-1'
      state.getById.mockReturnValue(of(existingPrompt()))

      component.ngOnInit()

      expect(internals().loopEnabledControl.value).toBe(false)
      expect(internals().agentLoopNameControl.value).toBe('')
      expect(internals().searchToolNameControl.value).toBe('')
      expect(internals().searchToolArgsJsonControl.value).toBe('')
    })

    it('omits both loop fields of the update payload when loop mode is unchecked, so the PUT clears them', () => {
      routeScheduledPromptId = 'sp-1'
      state.getById.mockReturnValue(
        of(
          existingPrompt({
            agentLoopName: 'TalentLoop',
            loopConfiguration: { searchToolName: 'WHOZ__SearchTalents', searchToolArgs: { limit: 10 } },
          })
        )
      )
      component.ngOnInit()
      internals().loopEnabledControl.setValue(false)

      internals().submit()

      expect(state.update).toHaveBeenCalledTimes(1)
      const payload = state.update.mock.calls[0][1] as ScheduledPrompt
      expect(payload.agentLoopName).toBeUndefined()
      expect(payload.loopConfiguration).toBeUndefined()
      expect(wire(payload)).not.toHaveProperty('agentLoopName')
      expect(wire(payload)).not.toHaveProperty('loopConfiguration')
    })

    it('sends the edited loop fields in the update payload', () => {
      routeScheduledPromptId = 'sp-1'
      state.getById.mockReturnValue(
        of(
          existingPrompt({
            agentLoopName: 'TalentLoop',
            loopConfiguration: { searchToolName: 'WHOZ__SearchTalents', searchToolArgs: { limit: 10 } },
          })
        )
      )
      component.ngOnInit()
      internals().searchToolArgsJsonControl.setValue('{"limit": 20}')

      internals().submit()

      const payload = state.update.mock.calls[0][1] as ScheduledPrompt
      expect(payload.agentLoopName).toBe('TalentLoop')
      expect(payload.loopConfiguration).toEqual({
        searchToolName: 'WHOZ__SearchTalents',
        searchToolArgs: { limit: 20 },
      })
    })
  })

  describe('validation', () => {
    it('rejects loop mode without a loop agent', () => {
      component.ngOnInit()
      fillBasics()
      internals().loopEnabledControl.setValue(true)
      internals().searchToolNameControl.setValue('WHOZ__SearchTalents')

      internals().submit()

      expect(internals().form.invalid).toBe(true)
      expect(state.create).not.toHaveBeenCalled()
    })

    it('rejects loop mode without a search tool name', () => {
      component.ngOnInit()
      fillBasics()
      internals().loopEnabledControl.setValue(true)
      internals().agentLoopNameControl.setValue('TalentLoop')

      internals().submit()

      expect(internals().form.invalid).toBe(true)
      expect(state.create).not.toHaveBeenCalled()
    })

    it('rejects a blank loop agent or search tool name', () => {
      component.ngOnInit()
      fillBasics()
      internals().loopEnabledControl.setValue(true)
      internals().agentLoopNameControl.setValue('   ')
      internals().searchToolNameControl.setValue('   ')

      internals().submit()

      expect(internals().form.invalid).toBe(true)
      expect(state.create).not.toHaveBeenCalled()
    })

    it.each(['[1,2]', '"x"', '42', 'null', '{ not json'])('rejects search arguments %s', (args) => {
      component.ngOnInit()
      fillBasics()
      internals().loopEnabledControl.setValue(true)
      internals().agentLoopNameControl.setValue('TalentLoop')
      internals().searchToolNameControl.setValue('WHOZ__SearchTalents')
      internals().searchToolArgsJsonControl.setValue(args)

      internals().submit()

      expect(internals().searchToolArgsJsonControl.invalid).toBe(true)
      expect(internals().form.invalid).toBe(true)
      expect(state.create).not.toHaveBeenCalled()
    })

    it('flags an array as "not a JSON object" rather than as a syntax error', () => {
      component.ngOnInit()
      internals().loopEnabledControl.setValue(true)
      internals().searchToolArgsJsonControl.setValue('[1,2]')

      expect(internals().searchToolArgsJsonControl.hasError('notJsonObject')).toBe(true)
    })

    it('does not let loop fields block a prompt that is not in loop mode', () => {
      component.ngOnInit()
      fillBasics()
      internals().loopEnabledControl.setValue(true)
      internals().searchToolArgsJsonControl.setValue('[1,2]')
      internals().loopEnabledControl.setValue(false)

      internals().submit()

      expect(state.create).toHaveBeenCalledTimes(1)
    })
  })

  describe('loop agent suggestions', () => {
    const config = (name: string, executionMode: AgentConfigExecutionModeEnum, enabled = true): AgentConfig =>
      ({ id: name, name, executionMode, enabled }) as AgentConfig

    it('suggests the enabled LOOP agents of the namespace and of the platform, without duplicates', () => {
      agentConfigController.listByParentAgentConfig.mockReturnValue(
        of([
          config('NsLoop', AgentConfigExecutionModeEnum.LOOP),
          config('DisabledLoop', AgentConfigExecutionModeEnum.LOOP, false),
          config('Simple', AgentConfigExecutionModeEnum.SIMPLE),
        ])
      )
      agentConfigController.listPlatformAgentsAgentConfig.mockReturnValue(
        of([
          config('PlatformLoop', AgentConfigExecutionModeEnum.LOOP),
          config('NsLoop', AgentConfigExecutionModeEnum.LOOP),
        ])
      )

      component.ngOnInit()

      expect(internals().loopAgentSuggestions()).toEqual(['NsLoop', 'PlatformLoop'])
    })
  })
})
