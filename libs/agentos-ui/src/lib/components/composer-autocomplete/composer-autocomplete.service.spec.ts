import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { signal, WritableSignal } from '@angular/core'
import { AgentConfig, Prompt } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { AgentAutocompleteComponent } from '../agent-autocomplete/agent-autocomplete.component'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'
import { ComposerAutocompleteService } from './composer-autocomplete.service'

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const makePrompt = (name: string): Prompt => ({ id: name, name }) as Prompt
const makeAgent = (name: string, description?: string): AgentConfig => ({ name, description }) as AgentConfig

const NS = 'ns-1'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Minimal signal wrapping an optional component ref — mimics viewChild(). */
function refSignal<T>(value?: T): WritableSignal<T | undefined> {
  return signal<T | undefined>(value)
}

/** Build a jest mock for PromptAutocompleteComponent.navigate. */
function mockPromptRef(): WritableSignal<PromptAutocompleteComponent | undefined> {
  return refSignal({ navigate: jest.fn() } as unknown as PromptAutocompleteComponent)
}

/** Build a jest mock for AgentAutocompleteComponent.navigate. */
function mockAgentRef(): WritableSignal<AgentAutocompleteComponent | undefined> {
  return refSignal({ navigate: jest.fn() } as unknown as AgentAutocompleteComponent)
}

// ---------------------------------------------------------------------------
// Factory
// ---------------------------------------------------------------------------

interface TestContext {
  service: ComposerAutocompleteService
  promptState: { listEffective: jest.Mock }
  agentState: { listEffective: jest.Mock }
}

function setup(): TestContext {
  const promptState = { listEffective: jest.fn().mockReturnValue(of([])) }
  const agentState = { listEffective: jest.fn().mockReturnValue(of([])) }

  TestBed.configureTestingModule({
    providers: [
      ComposerAutocompleteService,
      { provide: PromptStateService, useValue: promptState },
      { provide: AgentStateService, useValue: agentState },
    ],
  })

  const service = TestBed.inject(ComposerAutocompleteService)
  service.init(NS)

  return { service, promptState, agentState }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('ComposerAutocompleteService', () => {
  afterEach(() => TestBed.resetTestingModule())

  // -------------------------------------------------------------------------
  // init / reset
  // -------------------------------------------------------------------------

  describe('init()', () => {
    it('stores the namespaceId used for HTTP calls', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))

      service.onInput('/', { set: jest.fn() })
      tick(60)

      expect(promptState.listEffective).toHaveBeenCalledWith(NS)
    }))
  })

  describe('reset()', () => {
    it('clears suggestions and forces a fresh HTTP call on the next prefix', fakeAsync(() => {
      const { service, promptState } = setup()

      // Prime the cache
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))
      service.onInput('/', { set: jest.fn() })
      tick(60)
      expect(service.slashSuggestions()).toHaveLength(1)

      // Reset clears suggestions and cache
      service.reset()
      expect(service.slashSuggestions()).toEqual([])
      expect(service.atSuggestions()).toEqual([])

      // Next input triggers a new HTTP call (cache was cleared)
      promptState.listEffective.mockReturnValue(of([makePrompt('migrate')]))
      service.onInput('/', { set: jest.fn() })
      tick(60)

      expect(promptState.listEffective).toHaveBeenCalledTimes(2)
      expect(service.slashSuggestions()).toHaveLength(1)
      expect(service.slashSuggestions()[0]!.name).toBe('migrate')
    }))
  })

  // -------------------------------------------------------------------------
  // onInput
  // -------------------------------------------------------------------------

  describe('onInput()', () => {
    it('updates the inputValue signal', () => {
      const { service } = setup()
      const inputValue = { set: jest.fn() }
      service.onInput('hello', inputValue)
      expect(inputValue.set).toHaveBeenCalledWith('hello')
    })

    it('closes both dropdowns when value has no trigger character', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))
      service.onInput('/dep', { set: jest.fn() })
      tick(60)
      expect(service.slashSuggestions().length).toBeGreaterThan(0)

      service.onInput('hello world', { set: jest.fn() })
      expect(service.slashSuggestions()).toEqual([])
      expect(service.atSuggestions()).toEqual([])
    }))

    it('closes the @ dropdown when switching to a slash command', fakeAsync(() => {
      const { service, promptState, agentState } = setup()
      agentState.listEffective.mockReturnValue(of([makeAgent('alice')]))
      service.onInput('@al', { set: jest.fn() })
      tick(60)
      expect(service.atSuggestions().length).toBeGreaterThan(0)

      promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))
      service.onInput('/dep', { set: jest.fn() })
      tick(60)
      expect(service.atSuggestions()).toEqual([])
      expect(service.slashSuggestions().length).toBeGreaterThan(0)
    }))
  })

  // -------------------------------------------------------------------------
  // Slash-command pipeline
  // -------------------------------------------------------------------------

  describe('slash-command autocomplete (/)', () => {
    it('triggers an HTTP call on the first slash and caches the result', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy'), makePrompt('migrate')]))

      service.onInput('/', { set: jest.fn() })
      tick(60)

      expect(promptState.listEffective).toHaveBeenCalledTimes(1)
      expect(service.slashSuggestions()).toHaveLength(2)
    }))

    it('does not call HTTP again after the first load', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))

      service.onInput('/', { set: jest.fn() })
      tick(60)
      service.onInput('/dep', { set: jest.fn() })
      tick(60)

      expect(promptState.listEffective).toHaveBeenCalledTimes(1)
    }))

    it('filters prompts by prefix (case-insensitive)', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy'), makePrompt('migrate'), makePrompt('DELETE')]))

      service.onInput('/de', { set: jest.fn() })
      tick(60)

      const names = service.slashSuggestions().map((p) => p.name)
      expect(names).toContain('deploy')
      expect(names).toContain('DELETE')
      expect(names).not.toContain('migrate')
    }))

    it('returns empty suggestions when no prompt matches the prefix', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))

      service.onInput('/zzz', { set: jest.fn() })
      tick(60)

      expect(service.slashSuggestions()).toEqual([])
    }))

    it('returns empty suggestions and does not throw on HTTP error', fakeAsync(() => {
      const { service, promptState } = setup()
      jest.spyOn(console, 'error').mockImplementation(() => undefined)
      promptState.listEffective.mockReturnValue(throwError(() => new Error('500')))

      expect(() => {
        service.onInput('/', { set: jest.fn() })
        tick(60)
      }).not.toThrow()

      expect(service.slashSuggestions()).toEqual([])
    }))

    it('does not open the dropdown when the slash is followed by a space', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))

      service.onInput('/deploy now', { set: jest.fn() })
      tick(60)

      expect(service.slashSuggestions()).toEqual([])
    }))
  })

  // -------------------------------------------------------------------------
  // Agent @-mention pipeline
  // -------------------------------------------------------------------------

  describe('agent @-mention autocomplete (@)', () => {
    it('triggers an HTTP call on the first @ and caches the result', fakeAsync(() => {
      const { service, agentState } = setup()
      agentState.listEffective.mockReturnValue(of([makeAgent('alice'), makeAgent('bob')]))

      service.onInput('@', { set: jest.fn() })
      tick(60)

      expect(agentState.listEffective).toHaveBeenCalledTimes(1)
      expect(agentState.listEffective).toHaveBeenCalledWith(NS)
      expect(service.atSuggestions()).toHaveLength(2)
    }))

    it('does not call HTTP again after the first load', fakeAsync(() => {
      const { service, agentState } = setup()
      agentState.listEffective.mockReturnValue(of([makeAgent('alice')]))

      service.onInput('@', { set: jest.fn() })
      tick(60)
      service.onInput('@al', { set: jest.fn() })
      tick(60)

      expect(agentState.listEffective).toHaveBeenCalledTimes(1)
    }))

    it('puts name-matching agents before description-matching agents', fakeAsync(() => {
      const { service, agentState } = setup()
      agentState.listEffective.mockReturnValue(of([makeAgent('bob', 'alice helper'), makeAgent('alice')]))

      service.onInput('@alice', { set: jest.fn() })
      tick(60)

      const names = service.atSuggestions().map((a) => a.name)
      expect(names[0]).toBe('alice')
      expect(names[1]).toBe('bob')
    }))

    it('only matches trailing @ not followed by a space', fakeAsync(() => {
      const { service, agentState } = setup()
      agentState.listEffective.mockReturnValue(of([makeAgent('alice')]))

      service.onInput('ping @alice now', { set: jest.fn() })
      tick(60)

      expect(service.atSuggestions()).toEqual([])
    }))

    it('returns empty suggestions and does not throw on HTTP error', fakeAsync(() => {
      const { service, agentState } = setup()
      jest.spyOn(console, 'error').mockImplementation(() => undefined)
      agentState.listEffective.mockReturnValue(throwError(() => new Error('500')))

      expect(() => {
        service.onInput('@al', { set: jest.fn() })
        tick(60)
      }).not.toThrow()

      expect(service.atSuggestions()).toEqual([])
    }))
  })

  // -------------------------------------------------------------------------
  // onKeydown
  // -------------------------------------------------------------------------

  describe('onKeydown()', () => {
    it('returns false when no dropdown is open', () => {
      const { service } = setup()
      const event = new KeyboardEvent('keydown', { key: 'Enter' })
      expect(service.onKeydown(event, mockPromptRef(), mockAgentRef())).toBe(false)
    })

    describe('when the slash dropdown is open', () => {
      it('ArrowDown delegates to promptRef.navigate and returns true', fakeAsync(() => {
        const { service, promptState } = setup()
        promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))
        service.onInput('/dep', { set: jest.fn() })
        tick(60)

        const promptRef = mockPromptRef()
        const result = service.onKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown' }), promptRef, mockAgentRef())
        expect(result).toBe(true)
        expect(promptRef()!.navigate).toHaveBeenCalledWith('ArrowDown')
      }))

      it('ArrowUp delegates to promptRef.navigate and returns true', fakeAsync(() => {
        const { service, promptState } = setup()
        promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))
        service.onInput('/dep', { set: jest.fn() })
        tick(60)

        const promptRef = mockPromptRef()
        service.onKeydown(new KeyboardEvent('keydown', { key: 'ArrowUp' }), promptRef, mockAgentRef())
        expect(promptRef()!.navigate).toHaveBeenCalledWith('ArrowUp')
      }))

      it('Enter delegates to promptRef.navigate(Enter) and returns true', fakeAsync(() => {
        const { service, promptState } = setup()
        promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))
        service.onInput('/dep', { set: jest.fn() })
        tick(60)

        const promptRef = mockPromptRef()
        const result = service.onKeydown(new KeyboardEvent('keydown', { key: 'Enter' }), promptRef, mockAgentRef())
        expect(result).toBe(true)
        expect(promptRef()!.navigate).toHaveBeenCalledWith('Enter')
      }))

      it('Tab delegates to promptRef.navigate(Enter) and returns true', fakeAsync(() => {
        const { service, promptState } = setup()
        promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))
        service.onInput('/dep', { set: jest.fn() })
        tick(60)

        const promptRef = mockPromptRef()
        service.onKeydown(new KeyboardEvent('keydown', { key: 'Tab' }), promptRef, mockAgentRef())
        expect(promptRef()!.navigate).toHaveBeenCalledWith('Enter')
      }))

      it('Escape closes the dropdown and returns true', fakeAsync(() => {
        const { service, promptState } = setup()
        promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))
        service.onInput('/dep', { set: jest.fn() })
        tick(60)

        const result = service.onKeydown(
          new KeyboardEvent('keydown', { key: 'Escape' }),
          mockPromptRef(),
          mockAgentRef()
        )
        expect(result).toBe(true)
        expect(service.slashSuggestions()).toEqual([])
      }))
    })

    describe('when the @ dropdown is open', () => {
      it('ArrowDown delegates to agentRef.navigate and returns true', fakeAsync(() => {
        const { service, agentState } = setup()
        agentState.listEffective.mockReturnValue(of([makeAgent('alice')]))
        service.onInput('@al', { set: jest.fn() })
        tick(60)

        const agentRef = mockAgentRef()
        const result = service.onKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown' }), mockPromptRef(), agentRef)
        expect(result).toBe(true)
        expect(agentRef()!.navigate).toHaveBeenCalledWith('ArrowDown')
      }))

      it('Escape closes the @ dropdown and returns true', fakeAsync(() => {
        const { service, agentState } = setup()
        agentState.listEffective.mockReturnValue(of([makeAgent('alice')]))
        service.onInput('@al', { set: jest.fn() })
        tick(60)

        const result = service.onKeydown(
          new KeyboardEvent('keydown', { key: 'Escape' }),
          mockPromptRef(),
          mockAgentRef()
        )
        expect(result).toBe(true)
        expect(service.atSuggestions()).toEqual([])
      }))
    })
  })

  // -------------------------------------------------------------------------
  // onPromptSelected
  // -------------------------------------------------------------------------

  describe('onPromptSelected()', () => {
    it('sets the inputValue to the completion and closes slashSuggestions', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))
      service.onInput('/dep', { set: jest.fn() })
      tick(60)

      const inputValue = { set: jest.fn() }
      const promptRef = refSignal({
        completionFor: () => '/deploy ',
        navigate: jest.fn(),
      } as unknown as PromptAutocompleteComponent)
      const composerEl = { value: '', setSelectionRange: jest.fn(), focus: jest.fn() }
      const composerRef = refSignal({ nativeElement: composerEl } as unknown as HTMLElement as any)

      service.onPromptSelected(makePrompt('deploy'), promptRef, composerRef, inputValue)

      expect(inputValue.set).toHaveBeenCalledWith('/deploy ')
      expect(service.slashSuggestions()).toEqual([])
    }))

    it('falls back to /{name} when promptRef is undefined', () => {
      const { service } = setup()
      const inputValue = { set: jest.fn() }

      service.onPromptSelected(makePrompt('deploy'), refSignal(undefined), refSignal(undefined), inputValue)

      expect(inputValue.set).toHaveBeenCalledWith('/deploy ')
    })
  })

  // -------------------------------------------------------------------------
  // onAgentSelected
  // -------------------------------------------------------------------------

  describe('onAgentSelected()', () => {
    it('replaces the trailing @prefix with the full completion and closes atSuggestions', fakeAsync(() => {
      const { service, agentState } = setup()
      agentState.listEffective.mockReturnValue(of([makeAgent('alice')]))
      service.onInput('@al', { set: jest.fn() })
      tick(60)

      const inputValue = Object.assign(jest.fn().mockReturnValue('ping @al'), { set: jest.fn() })
      const agentRef = refSignal({
        completionFor: () => '@alice ',
        navigate: jest.fn(),
      } as unknown as AgentAutocompleteComponent)
      const composerEl = { value: '', setSelectionRange: jest.fn(), focus: jest.fn() }
      const composerRef = refSignal({ nativeElement: composerEl } as unknown as HTMLElement as any)

      service.onAgentSelected(makeAgent('alice'), agentRef, composerRef, inputValue)

      expect(inputValue.set).toHaveBeenCalledWith('ping @alice ')
      expect(service.atSuggestions()).toEqual([])
    }))

    it('falls back to @{name} when agentRef is undefined', () => {
      const { service } = setup()
      const inputValue = Object.assign(jest.fn().mockReturnValue('@al'), { set: jest.fn() })

      service.onAgentSelected(makeAgent('alice'), refSignal(undefined), refSignal(undefined), inputValue)

      expect(inputValue.set).toHaveBeenCalledWith('@alice ')
    })
  })
})
