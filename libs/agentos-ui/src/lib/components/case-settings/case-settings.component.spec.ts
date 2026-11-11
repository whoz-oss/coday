import { ComponentRef } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ReasoningEffort } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { ComposerAutocompleteService } from '../composer-autocomplete/composer-autocomplete.service'
import { EffortOption } from '../composer-autocomplete/effort-command'
import { ModelChoice, modelChoices } from '../composer-autocomplete/model-command'
import { CaseSettingsComponent } from './case-settings.component'

const CHOICES = modelChoices([
  { value: 'BIG', label: 'BIG (claude-opus)' },
  { value: 'SMALL', label: 'SMALL (claude-haiku)' },
])

describe('CaseSettingsComponent', () => {
  function render(inputs: { model?: string | null; effort?: ReasoningEffort | null; disabled?: boolean } = {}): {
    ref: ComponentRef<CaseSettingsComponent>
    host: HTMLElement
    autocomplete: ComposerAutocompleteService
    models: ModelChoice[]
    efforts: EffortOption[]
  } {
    TestBed.configureTestingModule({
      providers: [
        ComposerAutocompleteService,
        { provide: PromptStateService, useValue: { listEffective: jest.fn().mockReturnValue(of([])) } },
        { provide: AgentStateService, useValue: { listEffective: jest.fn().mockReturnValue(of([])) } },
      ],
    })
    const autocomplete = TestBed.inject(ComposerAutocompleteService)
    autocomplete.enableModelCommand(
      () => true,
      () => CHOICES
    )
    autocomplete.enableEffortCommand(() => true)
    const fixture = TestBed.createComponent(CaseSettingsComponent)
    fixture.componentRef.setInput('model', inputs.model ?? null)
    fixture.componentRef.setInput('effort', inputs.effort ?? null)
    fixture.componentRef.setInput('disabled', inputs.disabled ?? false)
    const models: ModelChoice[] = []
    const efforts: EffortOption[] = []
    fixture.componentInstance.modelSelected.subscribe((choice) => models.push(choice))
    fixture.componentInstance.effortSelected.subscribe((option) => efforts.push(option))
    fixture.detectChanges()
    return {
      ref: fixture.componentRef,
      host: fixture.nativeElement as HTMLElement,
      autocomplete,
      models,
      efforts,
    }
  }

  const chip = (host: HTMLElement, kind: 'model' | 'effort'): HTMLButtonElement =>
    host.querySelector<HTMLButtonElement>(`.case-settings__chip--${kind}`)!

  beforeAll(() => {
    // jsdom has no layout: scrolling the active entry into view is a no-op there.
    Element.prototype.scrollIntoView = jest.fn()
  })

  afterEach(() => TestBed.resetTestingModule())

  it('names the agent defaults when nothing is chosen', () => {
    const { host } = render()

    expect(chip(host, 'model').textContent?.trim()).toBe('model: agent default')
    expect(chip(host, 'effort').textContent?.trim()).toBe('effort: default')
  })

  it('names the model and the effort in use', () => {
    const { host } = render({ model: 'BIG', effort: ReasoningEffort.XHIGH })

    expect(chip(host, 'model').textContent?.trim()).toBe('model: BIG')
    expect(chip(host, 'effort').textContent?.trim()).toBe('effort: xhigh')
  })

  it('opens the model picker, on the way back to the agents plus every model, from the model chip', () => {
    const { host, autocomplete, ref } = render()

    chip(host, 'model').click()
    ref.changeDetectorRef.detectChanges()

    expect(autocomplete.modelSuggestions().map((c) => c.model)).toEqual([null, 'BIG', 'SMALL'])
    expect(host.querySelector('agentos-model-autocomplete')).not.toBeNull()
    expect(host.querySelector('agentos-effort-autocomplete')).toBeNull()
  })

  it('opens the effort picker, default entry first, from the effort chip, and closes the model one', () => {
    const { host, autocomplete, ref } = render()
    chip(host, 'model').click()

    chip(host, 'effort').click()
    ref.changeDetectorRef.detectChanges()

    expect(autocomplete.effortSuggestions()[0]?.effort).toBeNull()
    expect(autocomplete.modelSuggestions()).toEqual([])
    expect(host.querySelector('agentos-effort-autocomplete')).not.toBeNull()
    expect(host.querySelector('agentos-model-autocomplete')).toBeNull()
  })

  it('shows the same picker for the command as for the chip', () => {
    const { host, autocomplete, ref } = render()

    autocomplete.onInput('/model b', { set: jest.fn() })
    ref.changeDetectorRef.detectChanges()

    expect(host.querySelectorAll('agentos-model-autocomplete li')).toHaveLength(1)
  })

  it('emits the model picked, the way back to each agent included', () => {
    const { host, ref, models } = render({ model: 'BIG' })
    chip(host, 'model').click()
    ref.changeDetectorRef.detectChanges()

    const items = host.querySelectorAll<HTMLLIElement>('agentos-model-autocomplete li')
    items[2]!.click()
    items[0]!.click()

    expect(models.map((choice) => choice.model)).toEqual(['SMALL', null])
  })

  it('emits the effort picked, the way back to the default included', () => {
    const { host, ref, efforts } = render({ effort: ReasoningEffort.LOW })
    chip(host, 'effort').click()
    ref.changeDetectorRef.detectChanges()

    const items = host.querySelectorAll<HTMLLIElement>('agentos-effort-autocomplete li')
    items[0]!.click()
    items[4]!.click()

    expect(efforts.map((option) => option.effort)).toEqual([null, ReasoningEffort.HIGH])
  })

  it('marks the choice in use as current in the picker', () => {
    const { host, ref } = render({ model: 'SMALL' })
    chip(host, 'model').click()
    ref.changeDetectorRef.detectChanges()

    const current = Array.from(host.querySelectorAll('agentos-model-autocomplete li')).filter((li) =>
      li.textContent?.includes('current')
    )
    expect(current).toHaveLength(1)
    expect(current[0]!.textContent).toContain('/model SMALL')
  })

  it('leaves the chips inert when disabled', () => {
    const { host, autocomplete } = render({ disabled: true })

    expect(chip(host, 'model').disabled).toBe(true)
    expect(chip(host, 'effort').disabled).toBe(true)
    chip(host, 'model').click()
    expect(autocomplete.modelSuggestions()).toEqual([])
  })

  it('closes a picker when it asks to be dismissed', () => {
    const { host, autocomplete, ref } = render()
    chip(host, 'model').click()
    ref.changeDetectorRef.detectChanges()

    ref.instance.modelPicker()!.navigate('Escape')
    ref.changeDetectorRef.detectChanges()

    expect(autocomplete.modelSuggestions()).toEqual([])
    expect(host.querySelector('agentos-model-autocomplete')).toBeNull()
  })

  it('exposes the pickers so that the host can drive them from the keyboard', () => {
    const { host, ref } = render()
    expect(ref.instance.modelPicker()).toBeUndefined()
    expect(ref.instance.effortPicker()).toBeUndefined()

    chip(host, 'model').click()
    ref.changeDetectorRef.detectChanges()

    expect(ref.instance.modelPicker()).toBeDefined()
  })
})
