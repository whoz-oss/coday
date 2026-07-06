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

  it('nests child cases under their parent (tree from parentCaseId)', () => {
    const c = new CaseDrawerComponent()
    c.cases = [
      { id: 'parent', namespaceId: 'ns', title: 'Parent' } as unknown as Case,
      { id: 'child', namespaceId: 'ns', title: 'Child', parentCaseId: 'parent' } as unknown as Case,
    ]
    c.ngOnChanges({ cases: { currentValue: c.cases } } as unknown as SimpleChanges)

    expect(c['rootItems'].map((i) => i.id)).toEqual(['parent'])
    expect(c['rootItems'][0].children.map((i) => i.id)).toEqual(['child'])
  })

  it('carries favorite and canDelete onto each tree node', () => {
    const c = new CaseDrawerComponent()
    c.cases = [
      { id: 'admin-fav', namespaceId: 'ns', favorite: true, role: CaseRoleEnum.ADMIN } as unknown as Case,
      { id: 'member', namespaceId: 'ns', favorite: false, role: CaseRoleEnum.MEMBER } as unknown as Case,
    ]
    c.ngOnChanges({ cases: { currentValue: c.cases } } as unknown as SimpleChanges)

    const byId = (id: string) => c['rootItems'].find((i) => i.id === id)!
    // The delete button in the template is gated on canDelete (direct ADMIN only).
    expect(byId('admin-fav').canDelete).toBe(true)
    expect(byId('admin-fav').favorite).toBe(true)
    expect(byId('member').canDelete).toBe(false)
  })

  it('groups favorited roots under a Favorites section (favorites first)', () => {
    const c = new CaseDrawerComponent()
    c.cases = [
      { id: 'plain', namespaceId: 'ns', favorite: false } as unknown as Case,
      { id: 'fav', namespaceId: 'ns', favorite: true } as unknown as Case,
    ]
    c.ngOnChanges({ cases: { currentValue: c.cases } } as unknown as SimpleChanges)

    expect(c['rootItems'].map((i) => i.id)).toEqual(['fav', 'plain'])
    expect(c['rootItems'][0].groupKey).toBe('favorites')
    expect(c['rootItems'][0].groupLabel).toBe('Favorites')
    expect(c['rootItems'][1].groupKey).toBe('others')
    expect(c['rootItems'][1].groupLabel).toBe('Others')
  })

  it('leaves roots ungrouped when nothing is favorited', () => {
    const c = new CaseDrawerComponent()
    c.cases = [{ id: 'a', namespaceId: 'ns', favorite: false } as unknown as Case]
    c.ngOnChanges({ cases: { currentValue: c.cases } } as unknown as SimpleChanges)

    expect(c['rootItems'][0].groupKey).toBeUndefined()
  })

  it('builds overflow-menu items: star toggle, and delete only when the caller may delete', () => {
    const c = new CaseDrawerComponent()

    const admin = c['menuItemsFor']({ id: 'x', name: 'x', favorite: false, canDelete: true, children: [] })
    expect(admin.map((i) => i.key)).toEqual(['star', 'delete'])
    expect(admin[0].label).toBe('Add to favorites')
    expect(admin.find((i) => i.key === 'delete')?.variant).toBe('danger')

    const favMember = c['menuItemsFor']({ id: 'y', name: 'y', favorite: true, canDelete: false, children: [] })
    expect(favMember.map((i) => i.key)).toEqual(['star']) // non-ADMIN: no delete entry
    expect(favMember[0].label).toBe('Remove from favorites')
  })

  it('dispatches overflow-menu actions to star and delete', () => {
    const c = new CaseDrawerComponent()
    const stars: Array<{ id: string; starred: boolean }> = []
    const deletes: string[] = []
    c.starToggled.subscribe((e) => stars.push(e))
    c.deleteRequested.subscribe((id) => deletes.push(id))
    const node = { id: 'case-1', name: 'case-1', favorite: false, canDelete: true, children: [] }

    c['onMenuAction'](node, 'star')
    c['onMenuAction'](node, 'delete')

    expect(stars).toEqual([{ id: 'case-1', starred: true }])
    expect(deletes).toEqual(['case-1'])
  })
})
