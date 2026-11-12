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

  describe('dismissal', () => {
    const pointerDown = (target: EventTarget): void => {
      target.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    }
    const key = (target: EventTarget, name: string): KeyboardEvent => {
      const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true })
      target.dispatchEvent(event)
      return event
    }
    // TestBed attaches the component to the document, so the document listeners see its events.
    const open = (kind: 'model' | 'effort', rendered: ReturnType<typeof render>): void => {
      chip(rendered.host, kind).click()
      rendered.ref.changeDetectorRef.detectChanges()
    }

    it('closes an open picker on a pointerdown outside', () => {
      const rendered = render()
      open('model', rendered)
      expect(rendered.autocomplete.modelSuggestions().length).toBeGreaterThan(0)

      pointerDown(document.body)
      rendered.ref.changeDetectorRef.detectChanges()

      expect(rendered.autocomplete.modelSuggestions()).toEqual([])
      expect(rendered.host.querySelector('agentos-model-autocomplete')).toBeNull()
    })

    it('keeps the picker open on a pointerdown inside, on its list or on a chip', () => {
      const rendered = render()
      open('effort', rendered)

      pointerDown(rendered.host.querySelector('agentos-effort-autocomplete li')!)
      pointerDown(chip(rendered.host, 'model'))

      expect(rendered.autocomplete.effortSuggestions().length).toBeGreaterThan(0)
    })

    it('closes the picker when the backdrop is tapped', () => {
      const rendered = render()
      open('model', rendered)

      rendered.host.querySelector<HTMLElement>('.case-settings__backdrop')!.click()

      expect(rendered.autocomplete.modelSuggestions()).toEqual([])
    })

    it('closes the picker on Escape anywhere, and gives the focus back to the chip', () => {
      const rendered = render()
      open('effort', rendered)
      chip(rendered.host, 'effort').focus()

      const event = key(document.body, 'Escape')
      rendered.ref.changeDetectorRef.detectChanges()

      expect(event.defaultPrevented).toBe(true)
      expect(rendered.autocomplete.effortSuggestions()).toEqual([])
      expect(document.activeElement).toBe(chip(rendered.host, 'effort'))
    })

    it('does nothing on Escape once closed', () => {
      const rendered = render()
      open('model', rendered)
      pointerDown(document.body)
      rendered.ref.changeDetectorRef.detectChanges()

      const event = key(document.body, 'Escape')

      expect(event.defaultPrevented).toBe(false)
    })

    it('closes the open picker when its chip is clicked again', () => {
      const rendered = render()
      open('model', rendered)
      expect(chip(rendered.host, 'model').getAttribute('aria-expanded')).toBe('true')

      chip(rendered.host, 'model').click()
      rendered.ref.changeDetectorRef.detectChanges()

      expect(rendered.autocomplete.modelSuggestions()).toEqual([])
      expect(chip(rendered.host, 'model').getAttribute('aria-expanded')).toBe('false')
    })

    it('reflects the state of each picker in aria-expanded', () => {
      const rendered = render()
      expect(chip(rendered.host, 'model').getAttribute('aria-expanded')).toBe('false')
      expect(chip(rendered.host, 'effort').getAttribute('aria-expanded')).toBe('false')
      expect(chip(rendered.host, 'model').getAttribute('aria-haspopup')).toBe('listbox')

      open('effort', rendered)

      expect(chip(rendered.host, 'effort').getAttribute('aria-expanded')).toBe('true')
      expect(chip(rendered.host, 'model').getAttribute('aria-expanded')).toBe('false')
    })

    it('drives the picker from the focused chip: ArrowDown then Enter picks the next entry', () => {
      const rendered = render()
      open('model', rendered)
      chip(rendered.host, 'model').focus()

      key(chip(rendered.host, 'model'), 'ArrowDown')
      rendered.ref.changeDetectorRef.detectChanges()
      const enter = key(chip(rendered.host, 'model'), 'Enter')

      // Nothing chosen yet: the picker starts on the agent entry, so the next one is the first model.
      expect(rendered.models.map((choice) => choice.model)).toEqual(['BIG'])
      expect(enter.defaultPrevented).toBe(true)
    })

    it('drives the effort picker from the focused chip too', () => {
      const rendered = render()
      open('effort', rendered)

      key(chip(rendered.host, 'effort'), 'ArrowDown')
      rendered.ref.changeDetectorRef.detectChanges()
      key(chip(rendered.host, 'effort'), 'Enter')

      expect(rendered.efforts).toHaveLength(1)
      expect(rendered.efforts[0]!.effort).not.toBeNull()
    })

    it('leaves the keys of a closed picker alone', () => {
      const rendered = render()

      const event = key(chip(rendered.host, 'model'), 'Enter')

      expect(event.defaultPrevented).toBe(false)
      expect(rendered.models).toEqual([])
    })
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
