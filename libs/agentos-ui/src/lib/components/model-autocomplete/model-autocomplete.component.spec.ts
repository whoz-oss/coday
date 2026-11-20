import { TestBed } from '@angular/core/testing'
import { AGENT_MODEL_CHOICE, ModelChoice, modelChoices } from '../composer-autocomplete/model-command'
import { ModelAutocompleteComponent } from './model-autocomplete.component'

const CHOICES = modelChoices([
  { value: 'BIG', label: 'BIG (claude-opus)' },
  { value: 'SMALL', label: 'SMALL (claude-haiku)' },
])

describe('ModelAutocompleteComponent', () => {
  function render(current: string | null): { component: ModelAutocompleteComponent; chosen: ModelChoice[] } {
    const fixture = TestBed.createComponent(ModelAutocompleteComponent)
    fixture.componentRef.setInput('options', CHOICES)
    fixture.componentRef.setInput('current', current)
    fixture.detectChanges()
    const chosen: ModelChoice[] = []
    fixture.componentInstance.selected.subscribe((option) => chosen.push(option))
    return { component: fixture.componentInstance, chosen }
  }

  afterEach(() => TestBed.resetTestingModule())

  // jsdom has no layout: scrolling the active entry into view is a no-op there.
  beforeAll(() => {
    Element.prototype.scrollIntoView = jest.fn()
  })

  it('opens on the current model, so Enter right after /model keeps it', () => {
    const { component, chosen } = render('SMALL')

    component.navigate('Enter')

    expect(chosen.map((o) => o.model)).toEqual(['SMALL'])
  })

  it('matches the current model case-insensitively', () => {
    const { component, chosen } = render('big')

    component.navigate('Enter')

    expect(chosen.map((o) => o.model)).toEqual(['BIG'])
  })

  it('opens on the way back to the agents own models when the case has no override', () => {
    const { component, chosen } = render(null)

    component.navigate('Enter')

    expect(chosen).toEqual([AGENT_MODEL_CHOICE])
  })

  it('moves with the arrows and stays inside the list', () => {
    const { component, chosen } = render(null)

    component.navigate('ArrowDown')
    component.navigate('ArrowDown')
    component.navigate('ArrowDown')
    component.navigate('ArrowDown')
    component.navigate('Enter')

    expect(chosen.map((o) => o.model)).toEqual(['SMALL'])
  })

  it('jumps to the first and the last entry with Home and End', () => {
    const { component, chosen } = render('SMALL')

    component.navigate('Home')
    component.navigate('Enter')
    component.navigate('End')
    component.navigate('Enter')

    expect(chosen.map((o) => o.model)).toEqual([null, 'SMALL'])
  })

  describe('accessibility', () => {
    function renderDom(current: string | null, idPrefix?: string) {
      const fixture = TestBed.createComponent(ModelAutocompleteComponent)
      fixture.componentRef.setInput('options', CHOICES)
      fixture.componentRef.setInput('current', current)
      if (idPrefix) fixture.componentRef.setInput('idPrefix', idPrefix)
      fixture.detectChanges()
      const host = fixture.nativeElement as HTMLElement
      return {
        fixture,
        component: fixture.componentInstance,
        host,
        options: () => Array.from(host.querySelectorAll<HTMLElement>('[role="option"]')),
      }
    }

    it('gives the list and every option an id, derived from the prefix and stable', () => {
      const { fixture, component, host, options } = renderDom(null, 'picker-x')
      const ids = options().map((o) => o.id)

      expect(ids).toEqual(['picker-x-option-0', 'picker-x-option-1', 'picker-x-option-2'])
      expect(host.querySelector('[role="listbox"]')?.id).toBe('picker-x-listbox')
      expect(component.listboxId()).toBe('picker-x-listbox')

      component.navigate('ArrowDown')
      fixture.detectChanges()
      expect(options().map((o) => o.id)).toEqual(ids)
    })

    it('generates a different prefix for each instance by default', () => {
      const first = renderDom(null)
      const second = renderDom(null)

      const all = [...first.options(), ...second.options()].map((o) => o.id)
      expect(new Set(all).size).toBe(all.length)
      expect(first.component.listboxId()).not.toBe(second.component.listboxId())
    })

    it('exposes the id of the active option, which follows the navigation', () => {
      const { fixture, component, options } = renderDom(null)
      expect(component.activeOptionId()).toBe(options()[0]!.id)

      component.navigate('End')
      fixture.detectChanges()
      expect(component.activeOptionId()).toBe(options()[2]!.id)

      component.navigate('Home')
      expect(component.activeOptionId()).toBe(options()[0]!.id)
    })

    it('marks the model in use as selected, whichever entry is active', () => {
      const { fixture, component, options } = renderDom('SMALL')
      const selected = () => options().map((o) => o.getAttribute('aria-selected'))
      expect(selected()).toEqual(['false', 'false', 'true'])

      component.navigate('Home')
      fixture.detectChanges()
      expect(selected()).toEqual(['false', 'false', 'true'])
      expect(options()[0]!.classList).toContain('prompt-autocomplete__item--active')
    })

    it('marks the way back to each agent as selected when no model is chosen', () => {
      const { options } = renderDom(null)

      expect(options().map((o) => o.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false'])
    })

    it('cancels the mousedown on the list so that no option takes the focus', () => {
      const { options } = renderDom(null)
      const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true })

      options()[1]!.dispatchEvent(event)

      expect(event.defaultPrevented).toBe(true)
    })
  })

  it('asks to be closed on Escape', () => {
    const fixture = TestBed.createComponent(ModelAutocompleteComponent)
    fixture.componentRef.setInput('options', CHOICES)
    fixture.detectChanges()
    let dismissed = 0
    fixture.componentInstance.dismissed.subscribe(() => dismissed++)

    fixture.componentInstance.navigate('Escape')

    expect(dismissed).toBe(1)
  })
})
