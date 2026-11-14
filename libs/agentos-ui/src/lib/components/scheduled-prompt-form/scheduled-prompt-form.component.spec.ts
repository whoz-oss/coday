import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfigControllerService, ScheduledPrompt } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { ScheduledPromptStateService } from '../../services/scheduled-prompt-state.service'
import { ScheduledPromptFormComponent } from './scheduled-prompt-form.component'

/**
 * Focused on the flowConfiguration of a scheduled prompt: an existing one is shown and survives an edit, because
 * a PUT with null clears it.
 */
describe('ScheduledPromptFormComponent (flowConfiguration)', () => {
  const flowConfiguration = {
    search: { tool: 'searchAgentUsers', params: { agentConfigId: 'abc' } },
    act: { agentName: 'Caretaker' },
  }

  const existing = {
    id: 'sp-1',
    namespaceId: 'ns-1',
    agentConfigId: 'loop-agent',
    name: 'Weekly loop',
    promptContent: 'Review the profile',
    recurrence: { unit: 'WEEK', days: [], timeUtc: '09:00' },
    planning: { startDate: '2026-01-01', endType: 'NEVER' },
    enabled: true,
    removed: false,
    flowConfiguration,
  } as unknown as ScheduledPrompt

  let state: { getById: jest.Mock; update: jest.Mock; create: jest.Mock }

  function createComponent(stored: ScheduledPrompt) {
    state = {
      getById: jest.fn().mockReturnValue(of(stored)),
      update: jest.fn().mockReturnValue(of(stored)),
      create: jest.fn().mockReturnValue(of(stored)),
    }
    TestBed.configureTestingModule({
      imports: [ScheduledPromptFormComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              params: { namespaceId: 'ns-1' },
              paramMap: { get: (key: string) => (key === 'scheduledPromptId' ? 'sp-1' : null) },
            },
          },
        },
        { provide: Router, useValue: { navigate: jest.fn() } },
        { provide: ScheduledPromptStateService, useValue: state },
        {
          provide: AgentConfigControllerService,
          useValue: { listByParentAgentConfig: jest.fn().mockReturnValue(of([])) },
        },
      ],
    })
    const fixture = TestBed.createComponent(ScheduledPromptFormComponent)
    fixture.detectChanges()
    return fixture
  }

  const field = (fixture: ReturnType<typeof createComponent>) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLTextAreaElement>('#sp-flow-configuration')

  it('displays an existing flowConfiguration and sends it back on save', () => {
    const fixture = createComponent(existing)

    expect(field(fixture)).not.toBeNull()
    expect(JSON.parse(field(fixture)!.value)).toEqual(flowConfiguration)
    ;(fixture.componentInstance as unknown as { submit: () => void }).submit()

    expect(state.update).toHaveBeenCalledTimes(1)
    expect(state.update.mock.calls[0][1].flowConfiguration).toEqual(flowConfiguration)
  })

  it('sends null when the field has been emptied', () => {
    const fixture = createComponent(existing)
    const textarea = field(fixture)!
    textarea.value = ''
    textarea.dispatchEvent(new Event('input'))
    fixture.detectChanges()
    ;(fixture.componentInstance as unknown as { submit: () => void }).submit()

    expect(state.update.mock.calls[0][1].flowConfiguration).toBeNull()
  })
})
