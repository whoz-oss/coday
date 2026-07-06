import { SimpleChanges } from '@angular/core'
import { Case, CaseRoleEnum } from '@whoz-oss/agentos-api-client'
import { CaseDrawerComponent } from './case-drawer.component'

describe('CaseDrawerComponent', () => {
  it('emits deleteRequested with the case id when a delete is requested', () => {
    const component = new CaseDrawerComponent()
    const emitted: string[] = []
    component.deleteRequested.subscribe((id) => emitted.push(id))

    component['onDeleteRequested']('case-42')

    expect(emitted).toEqual(['case-42'])
  })

  it('emits starToggled with the opposite state when a star is toggled', () => {
    const c = new CaseDrawerComponent()
    const emitted: Array<{ id: string; starred: boolean }> = []
    c.starToggled.subscribe((e) => emitted.push(e))

    c['onStarToggled']({ id: 'case-9', name: 'case-9', favorite: false, canDelete: false })

    expect(emitted).toEqual([{ id: 'case-9', starred: true }])
  })

  it('emits starToggled with starred=false when un-starring a favorited case', () => {
    const c = new CaseDrawerComponent()
    const emitted: Array<{ id: string; starred: boolean }> = []
    c.starToggled.subscribe((e) => emitted.push(e))

    c['onStarToggled']({ id: 'case-9', name: 'case-9', favorite: true, canDelete: false })

    expect(emitted).toEqual([{ id: 'case-9', starred: false }])
  })

  it('optimistically flips favorite so a rapid second toggle emits the opposite state', () => {
    const c = new CaseDrawerComponent()
    const emitted: Array<{ id: string; starred: boolean }> = []
    c.starToggled.subscribe((e) => emitted.push(e))
    const item = { id: 'case-9', name: 'case-9', favorite: false, canDelete: false }

    c['onStarToggled'](item)
    c['onStarToggled'](item) // second click must read the flipped state, not the stale one

    expect(emitted).toEqual([
      { id: 'case-9', starred: true },
      { id: 'case-9', starred: false },
    ])
    expect(item.favorite).toBe(false) // flipped twice → back to original
  })

  it('orders favorites first and groups them when at least one case is starred', () => {
    const c = new CaseDrawerComponent()
    c.cases = [
      { id: 'a', namespaceId: 'ns', favorite: false } as unknown as Case,
      { id: 'b', namespaceId: 'ns', favorite: true } as unknown as Case,
    ]
    c.ngOnChanges({ cases: { currentValue: c.cases } } as unknown as SimpleChanges)

    expect(c['caseItems'].map((i) => i.id)).toEqual(['b', 'a'])
    expect(c['caseItems'][0].groupKey).toBe('favorites')
    expect(c['caseItems'][1].groupKey).toBe('cases')
  })

  it('leaves the list flat (no groups) when no case is starred', () => {
    const c = new CaseDrawerComponent()
    c.cases = [{ id: 'a', namespaceId: 'ns', favorite: false } as unknown as Case]
    c.ngOnChanges({ cases: { currentValue: c.cases } } as unknown as SimpleChanges)

    expect(c['caseItems'][0].groupKey).toBeUndefined()
  })

  it('marks canDelete only for cases where the caller is a direct ADMIN', () => {
    const c = new CaseDrawerComponent()
    c.cases = [
      { id: 'admin-case', namespaceId: 'ns', favorite: false, role: CaseRoleEnum.ADMIN } as unknown as Case,
      { id: 'member-case', namespaceId: 'ns', favorite: false, role: CaseRoleEnum.MEMBER } as unknown as Case,
    ]
    c.ngOnChanges({ cases: { currentValue: c.cases } } as unknown as SimpleChanges)

    const byId = (id: string) => c['caseItems'].find((i) => i.id === id)!
    // The delete button in the template is gated on canDelete.
    expect(byId('admin-case').canDelete).toBe(true)
    expect(byId('member-case').canDelete).toBe(false)
  })
})
