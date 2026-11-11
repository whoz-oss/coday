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
