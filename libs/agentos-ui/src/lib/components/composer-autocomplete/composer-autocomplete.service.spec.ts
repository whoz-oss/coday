import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { signal, WritableSignal } from '@angular/core'
import { AgentConfig, ExchangeControllerService, ExchangeManifest, Prompt } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { AgentAutocompleteComponent } from '../agent-autocomplete/agent-autocomplete.component'
import { EffortAutocompleteComponent } from '../effort-autocomplete/effort-autocomplete.component'
import { FileAutocompleteComponent } from '../file-autocomplete/file-autocomplete.component'
import { ModelAutocompleteComponent } from '../model-autocomplete/model-autocomplete.component'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'
import { ComposerAutocompleteService, FILE_CACHE_TTL_MS } from './composer-autocomplete.service'
import { FileSuggestion } from './file-mention'
import { modelChoices } from './model-command'

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
  exchange: { getNamespaceFilesManifestExchange: jest.Mock; getCaseFilesManifestExchange: jest.Mock }
}

function setup(): TestContext {
  const promptState = { listEffective: jest.fn().mockReturnValue(of([])) }
  const agentState = { listEffective: jest.fn().mockReturnValue(of([])) }
  const exchange = {
    getNamespaceFilesManifestExchange: jest.fn().mockReturnValue(of(manifest([]))),
    getCaseFilesManifestExchange: jest.fn().mockReturnValue(of(manifest([]))),
  }

  TestBed.configureTestingModule({
    providers: [
      ComposerAutocompleteService,
      { provide: PromptStateService, useValue: promptState },
      { provide: AgentStateService, useValue: agentState },
      { provide: ExchangeControllerService, useValue: exchange },
    ],
  })

  const service = TestBed.inject(ComposerAutocompleteService)
  service.init(NS)

  return { service, promptState, agentState, exchange }
}

/** A recursive exchange manifest listing [paths], readable by the caller. */
function manifest(paths: string[], capability = 'READ'): ExchangeManifest {
  return {
    capability,
    files: paths.map((path) => ({
      path,
      filename: path.split('/').pop()!,
      scope: 'NAMESPACE',
      size: 1,
      lastModified: '2026-01-01T00:00:00Z',
    })),
  } as ExchangeManifest
}

/** Build a jest mock for FileAutocompleteComponent.navigate. */
function mockFileRef(): WritableSignal<FileAutocompleteComponent | undefined> {
  return refSignal({ navigate: jest.fn() } as unknown as FileAutocompleteComponent)
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

  describe('/effort command', () => {
    it('is not offered until the host enables it', fakeAsync(() => {
      const { service } = setup()

      service.onInput('/effort', { set: jest.fn() })
      tick(60)

      expect(service.effortSuggestions()).toEqual([])
    }))

    it('lists the levels instead of the prompts whose name starts the same way', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('efficiency')]))
      service.enableEffortCommand(() => true)

      service.onInput('/e', { set: jest.fn() })
      service.onInput('/ef', { set: jest.fn() })
      tick(60)

      expect(service.effortSuggestions().map((o) => o.label)).toContain('high')
      expect(service.slashSuggestions()).toEqual([])
    }))

    it('stays closed while the host says the effort cannot be saved', fakeAsync(() => {
      const { service } = setup()
      service.enableEffortCommand(() => false)

      service.onInput('/effort', { set: jest.fn() })
      tick(60)

      expect(service.effortSuggestions()).toEqual([])
    }))

    it('closes when the value is no longer an /effort command', fakeAsync(() => {
      const { service } = setup()
      service.enableEffortCommand(() => true)

      service.onInput('/effort', { set: jest.fn() })
      service.onInput('hello', { set: jest.fn() })
      tick(60)

      expect(service.effortSuggestions()).toEqual([])
    }))

    it('takes the keyboard: Enter delegates to the effort picker, Escape closes it', fakeAsync(() => {
      const { service } = setup()
      service.enableEffortCommand(() => true)
      const effortRef = refSignal({ navigate: jest.fn() } as unknown as EffortAutocompleteComponent)
      service.onInput('/effort h', { set: jest.fn() })

      const enter = new KeyboardEvent('keydown', { key: 'Enter' })
      expect(service.onKeydown(enter, mockPromptRef(), mockAgentRef(), effortRef)).toBe(true)
      expect(effortRef()?.navigate).toHaveBeenCalledWith('Enter')

      const escape = new KeyboardEvent('keydown', { key: 'Escape' })
      expect(service.onKeydown(escape, mockPromptRef(), mockAgentRef(), effortRef)).toBe(true)
      expect(service.effortSuggestions()).toEqual([])
    }))
  })

  describe('/model command', () => {
    const choices = () =>
      modelChoices([
        { value: 'BIG', label: 'BIG (claude-opus)' },
        { value: 'SMALL', label: 'SMALL (claude-haiku)' },
      ])
    const labels = (service: ComposerAutocompleteService): string[] => service.modelSuggestions().map((c) => c.label)

    it('is not offered until the host enables it', fakeAsync(() => {
      const { service } = setup()

      service.onInput('/model', { set: jest.fn() })
      tick(60)

      expect(service.modelSuggestions()).toEqual([])
    }))

    it('lists the way back and every model from two letters of the command on', fakeAsync(() => {
      const { service, promptState } = setup()
      promptState.listEffective.mockReturnValue(of([makePrompt('modernize')]))
      service.enableModelCommand(() => true, choices)

      service.onInput('/mo', { set: jest.fn() })
      tick(60)

      expect(labels(service)).toEqual(['agent', 'BIG', 'SMALL'])
      // The prompt whose name starts the same way does not open over the picker.
      expect(service.slashSuggestions()).toEqual([])
    }))

    it('filters the models by the name typed after the command', fakeAsync(() => {
      const { service } = setup()
      service.enableModelCommand(() => true, choices)

      service.onInput('/model s', { set: jest.fn() })

      expect(labels(service)).toEqual(['SMALL'])
    }))

    it('stays closed while the host says the model cannot be saved', fakeAsync(() => {
      const { service } = setup()
      service.enableModelCommand(() => false, choices)

      service.onInput('/model', { set: jest.fn() })
      tick(60)

      expect(service.modelSuggestions()).toEqual([])
    }))

    it('closes when the value is no longer a /model command, or no model matches', fakeAsync(() => {
      const { service } = setup()
      service.enableModelCommand(() => true, choices)

      service.onInput('/model', { set: jest.fn() })
      service.onInput('hello', { set: jest.fn() })
      expect(service.modelSuggestions()).toEqual([])

      service.onInput('/model', { set: jest.fn() })
      service.onInput('/model zz', { set: jest.fn() })
      expect(service.modelSuggestions()).toEqual([])
    }))

    it('reads the models when the picker opens, so models loaded late are listed', fakeAsync(() => {
      const { service } = setup()
      let loaded = modelChoices([])
      service.enableModelCommand(
        () => true,
        () => loaded
      )

      service.onInput('/model', { set: jest.fn() })
      expect(labels(service)).toEqual(['agent'])

      loaded = choices()
      service.onInput('/model ', { set: jest.fn() })
      expect(labels(service)).toEqual(['agent', 'BIG', 'SMALL'])
    }))

    it('takes the keyboard: Enter delegates to the model picker, Escape closes it', fakeAsync(() => {
      const { service } = setup()
      service.enableModelCommand(() => true, choices)
      const modelRef = refSignal({ navigate: jest.fn() } as unknown as ModelAutocompleteComponent)
      service.onInput('/model b', { set: jest.fn() })

      const enter = new KeyboardEvent('keydown', { key: 'Enter' })
      expect(service.onKeydown(enter, mockPromptRef(), mockAgentRef(), undefined, undefined, modelRef)).toBe(true)
      expect(modelRef()?.navigate).toHaveBeenCalledWith('Enter')

      const escape = new KeyboardEvent('keydown', { key: 'Escape' })
      expect(service.onKeydown(escape, mockPromptRef(), mockAgentRef(), undefined, undefined, modelRef)).toBe(true)
      expect(service.modelSuggestions()).toEqual([])
    }))

    it('and /effort never open together', fakeAsync(() => {
      const { service } = setup()
      service.enableEffortCommand(() => true)
      service.enableModelCommand(() => true, choices)

      service.onInput('/effort', { set: jest.fn() })
      expect(service.effortSuggestions().length).toBeGreaterThan(0)
      expect(service.modelSuggestions()).toEqual([])

      service.onInput('/model', { set: jest.fn() })
      expect(service.modelSuggestions().length).toBeGreaterThan(0)
      expect(service.effortSuggestions()).toEqual([])
    }))

    it('reset() closes the picker', fakeAsync(() => {
      const { service } = setup()
      service.enableModelCommand(() => true, choices)
      service.onInput('/model', { set: jest.fn() })

      service.reset()

      expect(service.modelSuggestions()).toEqual([])
    }))
  })

  describe('showModelChoices()', () => {
    it('lists every model when the command is available, and nothing otherwise', () => {
      const { service } = setup()
      service.showModelChoices()
      expect(service.modelSuggestions()).toEqual([])

      service.enableModelCommand(
        () => true,
        () => modelChoices([{ value: 'BIG', label: 'BIG' }])
      )
      service.showModelChoices()
      expect(service.modelSuggestions().map((c) => c.label)).toEqual(['agent', 'BIG'])
    })
  })

  describe('showEffortLevels()', () => {
    it('lists every level when the command is available, and nothing otherwise', () => {
      const { service } = setup()
      service.showEffortLevels()
      expect(service.effortSuggestions()).toEqual([])

      service.enableEffortCommand(() => true)
      service.showEffortLevels()
      expect(service.effortSuggestions().map((o) => o.label)).toEqual([
        'default',
        'none',
        'low',
        'medium',
        'high',
        'xhigh',
        'max',
      ])
    })
  })

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
  // File # mention
  // -------------------------------------------------------------------------

  describe('file # mention', () => {
    const scopes = { namespaceId: NS, caseId: null as string | null }
    const names = (service: ComposerAutocompleteService): string[] =>
      service.fileSuggestions().map((f) => `${f.scope}:${f.path}`)

    function setupFiles(
      namespacePaths: string[] = ['docs/readme.md', 'src/main.ts'],
      casePaths: string[] = ['notes/todo.md']
    ): TestContext {
      const ctx = setup()
      ctx.exchange.getNamespaceFilesManifestExchange.mockReturnValue(of(manifest(namespacePaths)))
      ctx.exchange.getCaseFilesManifestExchange.mockReturnValue(of(manifest(casePaths)))
      ctx.service.enableFileMentions(() => scopes)
      return ctx
    }

    afterEach(() => {
      scopes.caseId = null
      jest.useRealTimers()
    })

    it('is not offered until the host enables it', fakeAsync(() => {
      const { service, exchange } = setup()

      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      expect(service.fileSuggestions()).toEqual([])
      expect(exchange.getNamespaceFilesManifestExchange).not.toHaveBeenCalled()
    }))

    it('opens on #rea and lists the matching namespace file', fakeAsync(() => {
      const { service, exchange } = setupFiles()

      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      expect(exchange.getNamespaceFilesManifestExchange).toHaveBeenCalledWith(NS)
      expect(names(service)).toEqual(['namespace:docs/readme.md'])
    }))

    it('opens after a whitespace, never inside a word (C#, a#b)', fakeAsync(() => {
      const { service } = setupFiles()

      service.onInput('see #rea', { set: jest.fn() })
      tick(60)
      expect(service.fileSuggestions()).toHaveLength(1)

      service.onInput('read C#', { set: jest.fn() })
      tick(60)
      expect(service.fileSuggestions()).toEqual([])

      service.onInput('a#rea', { set: jest.fn() })
      tick(60)
      expect(service.fileSuggestions()).toEqual([])
    }))

    it('inserts the scope-qualified reference in place of the #prefix', fakeAsync(() => {
      const { service } = setupFiles()
      service.onInput('see #rea', { set: jest.fn() })
      tick(60)
      const [file] = service.fileSuggestions()

      const inputValue = Object.assign(jest.fn().mockReturnValue('see #rea'), { set: jest.fn() })
      service.onFileSelected(file!, refSignal(undefined), refSignal(undefined), inputValue)

      expect(inputValue.set).toHaveBeenCalledWith('see namespace:docs/readme.md ')
      expect(service.fileSuggestions()).toEqual([])
    }))

    it('uses the completion of the dropdown component when there is one', () => {
      const { service } = setupFiles()
      const fileRef = refSignal({
        completionFor: () => 'namespace:docs/readme.md ',
      } as unknown as FileAutocompleteComponent)
      const inputValue = Object.assign(jest.fn().mockReturnValue('#rea'), { set: jest.fn() })
      const file: FileSuggestion = { scope: 'namespace', path: 'docs/readme.md', filename: 'readme.md' }

      service.onFileSelected(file, fileRef, refSignal(undefined), inputValue)

      expect(inputValue.set).toHaveBeenCalledWith('namespace:docs/readme.md ')
    })

    it('inside a case, also lists the case exchange, qualified with case:', fakeAsync(() => {
      scopes.caseId = 'case-1'
      const { service, exchange } = setupFiles(['docs/readme.md'], ['notes/readme-draft.md'])

      service.onInput('#readme', { set: jest.fn() })
      tick(60)

      expect(exchange.getCaseFilesManifestExchange).toHaveBeenCalledWith('case-1')
      expect(names(service)).toEqual(['namespace:docs/readme.md', 'case:notes/readme-draft.md'])
      const [file] = service.fileSuggestions().filter((f) => f.scope === 'case')
      const inputValue = Object.assign(jest.fn().mockReturnValue('#readme'), { set: jest.fn() })
      service.onFileSelected(file!, refSignal(undefined), refSignal(undefined), inputValue)
      expect(inputValue.set).toHaveBeenCalledWith('case:notes/readme-draft.md ')
    }))

    it('outside a case, never asks for a case exchange', fakeAsync(() => {
      const { service, exchange } = setupFiles()

      service.onInput('#', { set: jest.fn() })
      tick(60)

      expect(exchange.getCaseFilesManifestExchange).not.toHaveBeenCalled()
    }))

    it('stays silent when no file matches (an issue reference like #123)', fakeAsync(() => {
      const { service } = setupFiles()

      service.onInput('fixes #123', { set: jest.fn() })
      tick(60)

      expect(service.fileSuggestions()).toEqual([])
    }))

    it('a scope that answers an error contributes nothing, silently (fail-closed)', fakeAsync(() => {
      scopes.caseId = 'case-1'
      const { service, exchange } = setupFiles()
      exchange.getCaseFilesManifestExchange.mockReturnValue(throwError(() => ({ status: 409 })))

      service.onInput('#readme', { set: jest.fn() })
      tick(60)

      expect(names(service)).toEqual(['namespace:docs/readme.md'])
    }))

    it('a scope without capability contributes nothing', fakeAsync(() => {
      const { service, exchange } = setupFiles()
      exchange.getNamespaceFilesManifestExchange.mockReturnValue(of(manifest(['docs/readme.md'], 'NONE')))

      service.onInput('#readme', { set: jest.fn() })
      tick(60)

      expect(service.fileSuggestions()).toEqual([])
    }))

    it('does not conflict with @: an @ mention opens agents only, a # mention files only', fakeAsync(() => {
      const { service, agentState } = setupFiles()
      agentState.listEffective.mockReturnValue(of([makeAgent('alice')]))

      service.onInput('@al', { set: jest.fn() })
      tick(60)
      expect(service.atSuggestions()).toHaveLength(1)
      expect(service.fileSuggestions()).toEqual([])

      service.onInput('@al #rea', { set: jest.fn() })
      tick(60)
      expect(service.atSuggestions()).toEqual([])
      expect(names(service)).toEqual(['namespace:docs/readme.md'])

      service.onInput('@al #rea @al', { set: jest.fn() })
      tick(60)
      expect(service.fileSuggestions()).toEqual([])
      expect(service.atSuggestions()).toHaveLength(1)
    }))

    it('closes the slash dropdown, and a slash command closes the file one', fakeAsync(() => {
      const { service, promptState } = setupFiles()
      promptState.listEffective.mockReturnValue(of([makePrompt('deploy')]))

      service.onInput('#rea', { set: jest.fn() })
      tick(60)
      expect(service.fileSuggestions()).toHaveLength(1)

      service.onInput('/dep', { set: jest.fn() })
      tick(60)
      expect(service.fileSuggestions()).toEqual([])
      expect(service.slashSuggestions()).toHaveLength(1)
    }))

    it('closes when the word stops being a mention', fakeAsync(() => {
      const { service } = setupFiles()
      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      service.onInput('#rea ', { set: jest.fn() })

      expect(service.fileSuggestions()).toEqual([])
    }))

    it('ignores an answer that arrives after the mention was closed', fakeAsync(() => {
      const { service } = setupFiles()

      service.onInput('#rea', { set: jest.fn() })
      service.onInput('hello', { set: jest.fn() })
      tick(60)

      expect(service.fileSuggestions()).toEqual([])
    }))

    it('fetches once per scope and filters locally afterwards', fakeAsync(() => {
      const { service, exchange } = setupFiles()

      service.onInput('#', { set: jest.fn() })
      tick(60)
      service.onInput('#rea', { set: jest.fn() })
      tick(60)
      service.onInput('#main', { set: jest.fn() })
      tick(60)

      expect(exchange.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
      expect(names(service)).toEqual(['namespace:src/main.ts'])
    }))

    it('answers the keystrokes of a burst with a single request', fakeAsync(() => {
      const { service, exchange } = setupFiles()

      service.onInput('#r', { set: jest.fn() })
      service.onInput('#re', { set: jest.fn() })
      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      expect(exchange.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(1)
    }))

    it('fetches again once the cache is older than the TTL, so new files appear', fakeAsync(() => {
      const { service, exchange } = setupFiles(['docs/readme.md'])
      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      tick(FILE_CACHE_TTL_MS + 1)
      exchange.getNamespaceFilesManifestExchange.mockReturnValue(
        of(manifest(['docs/readme.md', 'docs/read-me-too.md']))
      )
      service.onInput('#read', { set: jest.fn() })
      tick(60)

      expect(exchange.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(2)
      expect(names(service)).toEqual(['namespace:docs/read-me-too.md', 'namespace:docs/readme.md'])
    }))

    it('reset() drops the cache and the suggestions', fakeAsync(() => {
      const { service, exchange } = setupFiles()
      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      service.reset()
      expect(service.fileSuggestions()).toEqual([])
      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      expect(exchange.getNamespaceFilesManifestExchange).toHaveBeenCalledTimes(2)
    }))

    it('fetches again when the case changes under the same composer', fakeAsync(() => {
      scopes.caseId = 'case-1'
      const { service, exchange } = setupFiles()
      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      scopes.caseId = 'case-2'
      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      expect(exchange.getCaseFilesManifestExchange).toHaveBeenCalledWith('case-2')
      expect(exchange.getCaseFilesManifestExchange).toHaveBeenCalledTimes(2)
    }))

    it('caps the list at 50 files', fakeAsync(() => {
      const paths = Array.from({ length: 80 }, (_, i) => `dir/file-${i}.txt`)
      const { service } = setupFiles(paths, [])

      service.onInput('#file', { set: jest.fn() })
      tick(60)

      expect(service.fileSuggestions()).toHaveLength(50)
    }))

    it('takes the keyboard: arrows and Enter go to the dropdown, Escape closes it', fakeAsync(() => {
      const { service } = setupFiles()
      service.onInput('#rea', { set: jest.fn() })
      tick(60)
      const fileRef = mockFileRef()

      const down = new KeyboardEvent('keydown', { key: 'ArrowDown' })
      expect(service.onKeydown(down, mockPromptRef(), mockAgentRef(), undefined, fileRef)).toBe(true)
      expect(fileRef()?.navigate).toHaveBeenCalledWith('ArrowDown')

      const enter = new KeyboardEvent('keydown', { key: 'Enter' })
      expect(service.onKeydown(enter, mockPromptRef(), mockAgentRef(), undefined, fileRef)).toBe(true)
      expect(fileRef()?.navigate).toHaveBeenCalledWith('Enter')

      const tab = new KeyboardEvent('keydown', { key: 'Tab' })
      expect(service.onKeydown(tab, mockPromptRef(), mockAgentRef(), undefined, fileRef)).toBe(true)

      const escape = new KeyboardEvent('keydown', { key: 'Escape' })
      expect(service.onKeydown(escape, mockPromptRef(), mockAgentRef(), undefined, fileRef)).toBe(true)
      expect(service.fileSuggestions()).toEqual([])
    }))

    it('lets Enter through when the dropdown is closed', () => {
      const { service } = setupFiles()
      const enter = new KeyboardEvent('keydown', { key: 'Enter' })

      expect(service.onKeydown(enter, mockPromptRef(), mockAgentRef(), undefined, mockFileRef())).toBe(false)
    })

    it('/effort takes precedence and closes the file dropdown', fakeAsync(() => {
      const { service } = setupFiles()
      service.enableEffortCommand(() => true)
      service.onInput('#rea', { set: jest.fn() })
      tick(60)

      service.onInput('/effort', { set: jest.fn() })

      expect(service.fileSuggestions()).toEqual([])
      expect(service.effortSuggestions().length).toBeGreaterThan(0)
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
