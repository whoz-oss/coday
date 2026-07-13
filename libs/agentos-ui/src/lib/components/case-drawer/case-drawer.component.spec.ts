import { ApplicationRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Case, CaseRoleEnum } from '@whoz-oss/agentos-api-client'
import { CaseDrawerComponent } from './case-drawer.component'

/**
 * Instantiate CaseDrawerComponent inside an injection context and wire up
 * signal inputs via ComponentRef.setInput().
 *
 * We use createComponent() + ApplicationRef.attachView() so that signal
 * inputs are properly initialised (input() requires an injection context).
 */
function makeComponent(cases: Case[] = [], activeCaseId: string | null = null): CaseDrawerComponent {
  const environmentInjector = TestBed.inject(EnvironmentInjector)
  const appRef = TestBed.inject(ApplicationRef)

  const ref = createComponent(CaseDrawerComponent, { environmentInjector })
  appRef.attachView(ref.hostView)

  if (cases.length) ref.setInput('cases', cases)
  if (activeCaseId) ref.setInput('activeCaseId', activeCaseId)
  ref.changeDetectorRef.detectChanges()

  return ref.instance
}

describe('CaseDrawerComponent', () => {
  afterEach(() => TestBed.resetTestingModule())

  it('emits deleteRequested with the case id when a delete is requested', () => {
    const component = makeComponent()
    const emitted: string[] = []
    component.deleteRequested.subscribe((id) => emitted.push(id))

    component['onDeleteRequested']('case-42')

    expect(emitted).toEqual(['case-42'])
  })

  it('emits starToggled with the opposite state when a star is toggled', () => {
    const component = makeComponent()
    const emitted: Array<{ id: string; starred: boolean }> = []
    component.starToggled.subscribe((e) => emitted.push(e))

    component['onStarToggled']({ id: 'case-9', name: 'case-9', favorite: false, canDelete: false })

    expect(emitted).toEqual([{ id: 'case-9', starred: true }])
  })

  it('emits starToggled with starred=false when un-starring a favorited case', () => {
    const component = makeComponent()
    const emitted: Array<{ id: string; starred: boolean }> = []
    component.starToggled.subscribe((e) => emitted.push(e))

    component['onStarToggled']({ id: 'case-9', name: 'case-9', favorite: true, canDelete: false })

    expect(emitted).toEqual([{ id: 'case-9', starred: false }])
  })

  it('does not mutate the node on toggle (optimism lives in CaseStateService)', () => {
    const component = makeComponent()
    const item = { id: 'case-9', name: 'case-9', favorite: false, canDelete: false }

    component['onStarToggled'](item)

    expect(item.favorite).toBe(false)
  })

  it('nests child cases under their parent (tree from parentCaseId)', () => {
    const cases: Case[] = [
      { id: 'parent', namespaceId: 'ns', title: 'Parent' } as unknown as Case,
      { id: 'child', namespaceId: 'ns', title: 'Child', parentCaseId: 'parent' } as unknown as Case,
    ]
    const component = makeComponent(cases)

    const roots = component['rootItems']()
    expect(roots.map((i) => i.id)).toEqual(['parent'])
    expect(roots[0].children.map((i) => i.id)).toEqual(['child'])
  })

  it('carries favorite and canDelete onto each tree node', () => {
    const cases: Case[] = [
      { id: 'admin-fav', namespaceId: 'ns', favorite: true, role: CaseRoleEnum.ADMIN } as unknown as Case,
      { id: 'member', namespaceId: 'ns', favorite: false, role: CaseRoleEnum.MEMBER } as unknown as Case,
    ]
    const component = makeComponent(cases)

    const byId = (id: string) => component['rootItems']().find((i) => i.id === id)!
    expect(byId('admin-fav').canDelete).toBe(true)
    expect(byId('admin-fav').favorite).toBe(true)
    expect(byId('member').canDelete).toBe(false)
  })

  it('groups favorited roots under a Favorites section (favorites first)', () => {
    const cases: Case[] = [
      { id: 'plain', namespaceId: 'ns', favorite: false } as unknown as Case,
      { id: 'fav', namespaceId: 'ns', favorite: true } as unknown as Case,
    ]
    const component = makeComponent(cases)

    const roots = component['rootItems']()
    // Favorites are promoted to the top
    expect(roots[0].id).toBe('fav')
    expect(roots[0].groupKey).toBe('favorites')
    expect(roots[0].groupLabel).toBe('Favorites')
    // Non-favorite is bucketed by time (groupKey is one of the time-bucket keys)
    expect(roots[1].id).toBe('plain')
    expect(roots[1].groupKey).toBeDefined()
    expect(roots[1].groupKey).not.toBe('favorites')
  })

  it('leaves roots ungrouped when nothing is favorited and only one time bucket', () => {
    const cases: Case[] = [{ id: 'a', namespaceId: 'ns', favorite: false } as unknown as Case]
    const component = makeComponent(cases)

    // Single bucket with no favorites: grouping is skipped
    expect(component['rootItems']()[0].groupKey).toBeUndefined()
  })

  it('builds overflow-menu items: star toggle, and delete only when the caller may delete', () => {
    const component = makeComponent()

    const admin = component['menuItemsFor']({ id: 'x', name: 'x', favorite: false, canDelete: true, children: [] })
    expect(admin.map((i) => i.key)).toEqual(['star', 'delete'])
    expect(admin[0].label).toBe('Add to favorites')
    expect(admin.find((i) => i.key === 'delete')?.variant).toBe('danger')

    const favMember = component['menuItemsFor']({ id: 'y', name: 'y', favorite: true, canDelete: false, children: [] })
    expect(favMember.map((i) => i.key)).toEqual(['star'])
    expect(favMember[0].label).toBe('Remove from favorites')
  })

  it('dispatches overflow-menu actions to star and delete', () => {
    const component = makeComponent()
    const stars: Array<{ id: string; starred: boolean }> = []
    const deletes: string[] = []
    component.starToggled.subscribe((e) => stars.push(e))
    component.deleteRequested.subscribe((id) => deletes.push(id))
    const node = { id: 'case-1', name: 'case-1', favorite: false, canDelete: true, children: [] }

    component['onMenuAction'](node, 'star')
    component['onMenuAction'](node, 'delete')

    expect(stars).toEqual([{ id: 'case-1', starred: true }])
    expect(deletes).toEqual(['case-1'])
  })

  it('lets the user collapse an auto-expanded ancestor of the active case', () => {
    // Parent P with child C; C is the active case, so P is auto-expanded as an ancestor.
    const cases = [
      { id: 'p', title: 'Parent', favorite: false } as Case,
      { id: 'c', title: 'Child', favorite: false, parentCaseId: 'p' } as Case,
    ]
    const component = makeComponent(cases, 'c')

    expect(component['isExpanded']('p')).toBe(true)

    // The user can now collapse it despite the auto-expand, and re-expand it.
    component['toggleExpand'](new Event('click'), 'p')
    expect(component['isExpanded']('p')).toBe(false)

    component['toggleExpand'](new Event('click'), 'p')
    expect(component['isExpanded']('p')).toBe(true)
  })
})
