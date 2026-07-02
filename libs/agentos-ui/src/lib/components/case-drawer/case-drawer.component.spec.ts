import { CaseDrawerComponent } from './case-drawer.component'

describe('CaseDrawerComponent', () => {
  it('emits deleteRequested with the case id when a delete is requested', () => {
    const component = new CaseDrawerComponent()
    const emitted: string[] = []
    component.deleteRequested.subscribe((id) => emitted.push(id))

    component['onDeleteRequested']('case-42')

    expect(emitted).toEqual(['case-42'])
  })
})
