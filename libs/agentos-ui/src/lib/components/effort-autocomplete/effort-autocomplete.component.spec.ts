import { TestBed } from '@angular/core/testing'
import { ReasoningEffort } from '@whoz-oss/agentos-api-client'
import { EFFORT_OPTIONS, EffortOption } from '../composer-autocomplete/effort-command'
import { EffortAutocompleteComponent } from './effort-autocomplete.component'

describe('EffortAutocompleteComponent', () => {
  function render(current: ReasoningEffort | null): { component: EffortAutocompleteComponent; chosen: EffortOption[] } {
    const fixture = TestBed.createComponent(EffortAutocompleteComponent)
    fixture.componentRef.setInput('options', [...EFFORT_OPTIONS])
    fixture.componentRef.setInput('current', current)
    fixture.detectChanges()
    const chosen: EffortOption[] = []
    fixture.componentInstance.selected.subscribe((option) => chosen.push(option))
    return { component: fixture.componentInstance, chosen }
  }

  afterEach(() => TestBed.resetTestingModule())

  // jsdom has no layout: scrolling the active entry into view is a no-op there.
  beforeAll(() => {
    Element.prototype.scrollIntoView = jest.fn()
  })

  it('opens on the current level, so Enter right after /effort keeps it', () => {
    const { component, chosen } = render(ReasoningEffort.HIGH)

    component.navigate('Enter')

    expect(chosen.map((o) => o.effort)).toEqual([ReasoningEffort.HIGH])
  })

  it('opens on default when the case has no override', () => {
    const { component, chosen } = render(null)

    component.navigate('Enter')

    expect(chosen.map((o) => o.effort)).toEqual([null])
  })

  it('jumps to the first and the last level with Home and End', () => {
    const { component, chosen } = render(ReasoningEffort.HIGH)

    component.navigate('Home')
    component.navigate('Enter')
    component.navigate('End')
    component.navigate('Enter')

    expect(chosen[0]!.effort).toBeNull()
    expect(chosen[1]!.effort).toBe(EFFORT_OPTIONS[EFFORT_OPTIONS.length - 1]!.effort)
  })

  describe('accessibility', () => {
    function renderDom(current: ReasoningEffort | null, idPrefix?: string) {
      const fixture = TestBed.createComponent(EffortAutocompleteComponent)
      fixture.componentRef.setInput('options', [...EFFORT_OPTIONS])
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
      const { fixture, component, host, options } = renderDom(null, 'effort-x')
      const ids = options().map((o) => o.id)

      expect(ids).toEqual(EFFORT_OPTIONS.map((_, i) => `effort-x-option-${i}`))
      expect(host.querySelector('[role="listbox"]')?.id).toBe('effort-x-listbox')
      expect(component.listboxId()).toBe('effort-x-listbox')

      component.navigate('ArrowDown')
      fixture.detectChanges()
      expect(options().map((o) => o.id)).toEqual(ids)
    })

    it('generates a different prefix for each instance by default', () => {
      const first = renderDom(null)
      const second = renderDom(null)

      const all = [...first.options(), ...second.options()].map((o) => o.id)
      expect(new Set(all).size).toBe(all.length)
    })

    it('exposes the id of the active option, which follows the navigation', () => {
      const { fixture, component, options } = renderDom(null)
      expect(component.activeOptionId()).toBe(options()[0]!.id)

      component.navigate('End')
      fixture.detectChanges()
      expect(component.activeOptionId()).toBe(options()[options().length - 1]!.id)
    })

    it('marks the level in use as selected, whichever entry is active', () => {
      const { fixture, component, options } = renderDom(ReasoningEffort.HIGH)
      const selected = () => options().map((o) => o.getAttribute('aria-selected'))
      const expected = EFFORT_OPTIONS.map((o) => String(o.effort === ReasoningEffort.HIGH))
      expect(selected()).toEqual(expected)

      component.navigate('Home')
      fixture.detectChanges()
      expect(selected()).toEqual(expected)
      expect(options()[0]!.classList).toContain('prompt-autocomplete__item--active')
    })

    it('marks default as selected when no effort is chosen', () => {
      const { options } = renderDom(null)

      expect(options()[0]!.getAttribute('aria-selected')).toBe('true')
      expect(options().filter((o) => o.getAttribute('aria-selected') === 'true')).toHaveLength(1)
    })

    it('cancels the mousedown on the list so that no option takes the focus', () => {
      const { options } = renderDom(null)
      const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true })

      options()[1]!.dispatchEvent(event)

      expect(event.defaultPrevented).toBe(true)
    })
  })
})
