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
})
