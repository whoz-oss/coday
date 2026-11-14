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

    it('leaves the keys it does not handle alone, closed or open', () => {
      const rendered = render()

      for (const name of ['Tab', 'a', 'Escape']) {
        expect(key(chip(rendered.host, 'model'), name).defaultPrevented).toBe(false)
      }
      open('model', rendered)
      for (const name of ['a', 'Shift']) {
        expect(key(chip(rendered.host, 'model'), name).defaultPrevented).toBe(false)
      }

      expect(rendered.models).toEqual([])
    })
  })

  describe('accessibility (select-only combobox)', () => {
    const key = (target: EventTarget, name: string, init: KeyboardEventInit = {}): KeyboardEvent => {
      const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true, ...init })
      target.dispatchEvent(event)
      return event
    }
    const open = (kind: 'model' | 'effort', rendered: ReturnType<typeof render>): void => {
      chip(rendered.host, kind).click()
      rendered.ref.changeDetectorRef.detectChanges()
    }
    const press = (rendered: ReturnType<typeof render>, kind: 'model' | 'effort', name: string): KeyboardEvent => {
      const event = key(chip(rendered.host, kind), name)
      rendered.ref.changeDetectorRef.detectChanges()
      return event
    }
    const activeId = (rendered: ReturnType<typeof render>, kind: 'model' | 'effort'): string | null =>
      chip(rendered.host, kind).getAttribute('aria-activedescendant')
    const optionIds = (host: HTMLElement, kind: 'model' | 'effort'): string[] =>
      Array.from(host.querySelectorAll(`agentos-${kind}-autocomplete [role="option"]`)).map((li) => li.id)
    const outside = (): HTMLButtonElement => {
      const button = document.createElement('button')
      document.body.appendChild(button)
      return button
    }
    afterEach(() => (document.body.innerHTML = ''))

    it('makes each chip a combobox with an accessible name that contains its visible text', () => {
      const { host } = render({ model: 'BIG', effort: ReasoningEffort.HIGH })

      expect(chip(host, 'model').getAttribute('role')).toBe('combobox')
      expect(chip(host, 'effort').getAttribute('role')).toBe('combobox')
      expect(chip(host, 'model').getAttribute('aria-label')).toBe('AI model: BIG')
      expect(chip(host, 'effort').getAttribute('aria-label')).toBe('Reasoning effort: high')
      // WCAG 2.5.3: the value shown is part of the name.
      expect(chip(host, 'model').getAttribute('aria-label')).toContain('BIG')
      expect(chip(host, 'effort').getAttribute('aria-label')).toContain('high')
    })

    it('points at the listbox and the active option only while open, at elements that exist', () => {
      const rendered = render({ model: 'SMALL' })
      expect(chip(rendered.host, 'model').hasAttribute('aria-controls')).toBe(false)
      expect(chip(rendered.host, 'model').hasAttribute('aria-activedescendant')).toBe(false)

      open('model', rendered)
      rendered.ref.changeDetectorRef.detectChanges()

      const controls = chip(rendered.host, 'model').getAttribute('aria-controls')!
      const listbox = document.getElementById(controls)
      expect(listbox?.getAttribute('role')).toBe('listbox')
      const active = document.getElementById(activeId(rendered, 'model')!)
      expect(active?.getAttribute('role')).toBe('option')
      // Opens on the model in use.
      expect(active?.textContent).toContain('/model SMALL')
      expect(listbox?.contains(active)).toBe(true)

      chip(rendered.host, 'model').click()
      rendered.ref.changeDetectorRef.detectChanges()
      expect(chip(rendered.host, 'model').hasAttribute('aria-controls')).toBe(false)
      expect(chip(rendered.host, 'model').hasAttribute('aria-activedescendant')).toBe(false)
    })

    it('gives the model and the effort pickers ids that never collide', () => {
      const rendered = render()
      open('model', rendered)
      const modelIds = optionIds(rendered.host, 'model')
      const modelListbox = chip(rendered.host, 'model').getAttribute('aria-controls')!
      open('effort', rendered)
      const effortIds = optionIds(rendered.host, 'effort')
      const effortListbox = chip(rendered.host, 'effort').getAttribute('aria-controls')!

      expect(new Set([...modelIds, ...effortIds, modelListbox, effortListbox]).size).toBe(
        modelIds.length + effortIds.length + 2
      )
      expect(modelIds[0]).toMatch(/^case-settings-\d+-model-option-0$/)
      expect(effortIds[0]).toMatch(/^case-settings-\d+-effort-option-0$/)
    })

    it('follows the active option with ArrowDown, ArrowUp, End and Home', () => {
      const rendered = render()
      open('model', rendered)
      rendered.ref.changeDetectorRef.detectChanges()
      const ids = optionIds(rendered.host, 'model')
      expect(activeId(rendered, 'model')).toBe(ids[0])

      press(rendered, 'model', 'ArrowDown')
      expect(activeId(rendered, 'model')).toBe(ids[1])
      press(rendered, 'model', 'End')
      expect(activeId(rendered, 'model')).toBe(ids[2])
      press(rendered, 'model', 'ArrowUp')
      expect(activeId(rendered, 'model')).toBe(ids[1])
      press(rendered, 'model', 'Home')
      expect(activeId(rendered, 'model')).toBe(ids[0])
    })

    it('marks the model in use as selected, not the active entry', () => {
      const rendered = render({ model: 'BIG' })
      open('model', rendered)
      press(rendered, 'model', 'End')

      const selected = Array.from(rendered.host.querySelectorAll('agentos-model-autocomplete [role="option"]')).map(
        (li) => li.getAttribute('aria-selected')
      )
      expect(selected).toEqual(['false', 'true', 'false'])
      expect(rendered.host.querySelector('.prompt-autocomplete__item--active')?.textContent).toContain('/model SMALL')
    })

    it.each(['ArrowDown', 'ArrowUp', 'Enter', ' '])('opens a closed chip on %j, without a click', (name) => {
      const rendered = render()

      const event = press(rendered, 'effort', name)

      expect(event.defaultPrevented).toBe(true)
      expect(rendered.autocomplete.effortSuggestions().length).toBeGreaterThan(0)
      expect(chip(rendered.host, 'effort').getAttribute('aria-expanded')).toBe('true')
      expect(rendered.efforts).toEqual([])
    })

    it('opens a closed chip on Alt+ArrowDown', () => {
      const rendered = render()

      key(chip(rendered.host, 'model'), 'ArrowDown', { altKey: true })

      expect(rendered.autocomplete.modelSuggestions().length).toBeGreaterThan(0)
    })

    it.each(['Enter', ' '])('picks the active entry on %j and emits it', (name) => {
      const rendered = render()
      open('model', rendered)
      press(rendered, 'model', 'ArrowDown')

      const event = press(rendered, 'model', name)

      expect(event.defaultPrevented).toBe(true)
      expect(rendered.models.map((choice) => choice.model)).toEqual(['BIG'])
    })

    it('does not pick on the click that Space would fire on release', () => {
      const rendered = render()
      open('model', rendered)

      const keyup = new KeyboardEvent('keyup', { key: ' ', bubbles: true, cancelable: true })
      chip(rendered.host, 'model').dispatchEvent(keyup)

      expect(keyup.defaultPrevented).toBe(true)
    })

    it('closes on Tab without picking, and lets the focus move on', () => {
      const rendered = render()
      open('effort', rendered)
      press(rendered, 'effort', 'ArrowDown')

      const event = press(rendered, 'effort', 'Tab')

      expect(event.defaultPrevented).toBe(false)
      expect(rendered.autocomplete.effortSuggestions()).toEqual([])
      expect(rendered.efforts).toEqual([])
    })

    it('cancels the mousedown on an option so that the focus stays where it is', () => {
      const rendered = render()
      open('model', rendered)

      const option = rendered.host.querySelector('agentos-model-autocomplete [role="option"]')!
      const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true })
      option.dispatchEvent(event)

      expect(event.defaultPrevented).toBe(true)
    })

    it('keeps the focus on the chip after a pick made with the mouse or the keyboard', () => {
      const rendered = render()
      open('model', rendered)
      chip(rendered.host, 'model').focus()
      press(rendered, 'model', 'Enter')
      expect(document.activeElement).toBe(chip(rendered.host, 'model'))

      open('effort', rendered)
      chip(rendered.host, 'effort').focus()
      rendered.host.querySelectorAll<HTMLElement>('agentos-effort-autocomplete li')[2]!.click()
      expect(document.activeElement).toBe(chip(rendered.host, 'effort'))
    })

    it('gives the focus back to the chip when a pick left it on the body', () => {
      const rendered = render()
      open('model', rendered)
      ;(document.activeElement as HTMLElement | null)?.blur()

      rendered.host.querySelectorAll<HTMLElement>('agentos-model-autocomplete li')[1]!.click()

      expect(document.activeElement).toBe(chip(rendered.host, 'model'))
    })

    it('leaves the focus in the textarea when the picker was opened by the command', () => {
      const rendered = render()
      const textarea = document.createElement('textarea')
      document.body.appendChild(textarea)
      textarea.focus()

      rendered.autocomplete.onInput('/model b', { set: jest.fn() })
      rendered.ref.changeDetectorRef.detectChanges()
      rendered.host.querySelector<HTMLElement>('agentos-model-autocomplete li')!.click()

      expect(rendered.models.map((choice) => choice.model)).toEqual(['BIG'])
      expect(document.activeElement).toBe(textarea)
    })

    it('closes the picker when the focus leaves from a chip for something outside', () => {
      const rendered = render()
      open('model', rendered)
      const elsewhere = outside()

      chip(rendered.host, 'model').dispatchEvent(
        new FocusEvent('focusout', { bubbles: true, relatedTarget: elsewhere })
      )
      rendered.ref.changeDetectorRef.detectChanges()

      expect(rendered.autocomplete.modelSuggestions()).toEqual([])
    })

    it('keeps the picker open when the focus moves between the chips', () => {
      const rendered = render()
      open('model', rendered)

      chip(rendered.host, 'model').dispatchEvent(
        new FocusEvent('focusout', { bubbles: true, relatedTarget: chip(rendered.host, 'effort') })
      )

      expect(rendered.autocomplete.modelSuggestions().length).toBeGreaterThan(0)
    })

    it('keeps a picker opened by the command open while the focus is in the textarea outside', () => {
      const rendered = render()
      const textarea = document.createElement('textarea')
      document.body.appendChild(textarea)
      textarea.focus()
      rendered.autocomplete.onInput('/model', { set: jest.fn() })
      rendered.ref.changeDetectorRef.detectChanges()

      textarea.dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: outside() }))

      expect(rendered.autocomplete.modelSuggestions().length).toBeGreaterThan(0)
    })

    it('keeps the backdrop out of the accessibility tree and of the tab order', () => {
      const rendered = render()
      open('model', rendered)

      const backdrop = rendered.host.querySelector('.case-settings__backdrop')!
      expect(backdrop.getAttribute('aria-hidden')).toBe('true')
      expect(backdrop.hasAttribute('tabindex')).toBe(false)
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
