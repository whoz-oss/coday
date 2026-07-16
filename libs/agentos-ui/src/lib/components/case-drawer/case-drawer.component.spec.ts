import { ApplicationRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Case, CaseRoleEnum, CaseStatusEnum } from '@whoz-oss/agentos-api-client'
import { CaseDrawerComponent } from './case-drawer.component'
import { CaseItemComponent } from '../case-item/case-item.component'

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

const caseFixture = (overrides: Partial<Case> = {}): Case =>
  ({
    id: 'case-1',
    namespaceId: 'ns',
    title: 'Case 1',
    favorite: false,
    removed: false,
    status: CaseStatusEnum.IDLE,
    ...overrides,
  }) as Case

describe('CaseDrawerComponent', () => {
  afterEach(() => TestBed.resetTestingModule())

  it('sets pendingDeleteId when a delete is requested (confirmation is now inline)', () => {
    const component = makeComponent()
    component['onDeleteRequested']('case-42')
    expect(component['pendingDeleteId']()).toBe('case-42')
  })

  it('emits deleteRequested and clears pendingDeleteId when delete is confirmed', () => {
    const component = makeComponent()
    const emitted: string[] = []
    component.deleteRequested.subscribe((id) => emitted.push(id))
    component['onDeleteRequested']('case-42')
    component['onDeleteConfirmed']('case-42')
    expect(emitted).toEqual(['case-42'])
    expect(component['pendingDeleteId']()).toBeNull()
  })

  it('clears pendingDeleteId without emitting when delete is cancelled', () => {
    const component = makeComponent()
    const emitted: string[] = []
    component.deleteRequested.subscribe((id) => emitted.push(id))
    component['onDeleteRequested']('case-42')
    component['onDeleteCancelled']()
    expect(emitted).toEqual([])
    expect(component['pendingDeleteId']()).toBeNull()
  })

  it('emits starToggled with the opposite state when a star is toggled', () => {
    const component = makeComponent()
    const emitted: Array<{ id: string; starred: boolean }> = []
    component.starToggled.subscribe((e) => emitted.push(e))
    component['onStarToggled'](CaseItemComponent.toListItem(caseFixture({ id: 'case-9', favorite: false })))
    expect(emitted).toEqual([{ id: 'case-9', starred: true }])
  })

  it('emits starToggled with starred=false when un-starring a favorited case', () => {
    const component = makeComponent()
    const emitted: Array<{ id: string; starred: boolean }> = []
    component.starToggled.subscribe((e) => emitted.push(e))
    component['onStarToggled'](CaseItemComponent.toListItem(caseFixture({ id: 'case-9', favorite: true })))
    expect(emitted).toEqual([{ id: 'case-9', starred: false }])
  })

  it('does not mutate the source item on toggle', () => {
    const component = makeComponent()
    const item = CaseItemComponent.toListItem(caseFixture({ id: 'case-9', favorite: false }))
    component['onStarToggled'](item)
    expect(item.favorite).toBe(false)
  })

  it('nests child cases under their parent (tree from parentCaseId)', () => {
    const cases: Case[] = [
      caseFixture({ id: 'parent', title: 'Parent' }),
      caseFixture({ id: 'child', title: 'Child', parentCaseId: 'parent' }),
    ]
    const component = makeComponent(cases)
    const roots = component['rootItems']()
    expect(roots.map((i) => i.id)).toEqual(['parent'])
    expect(roots[0].children.map((i) => i.id)).toEqual(['child'])
  })

  it('carries favorite, canDelete and status onto each tree node', () => {
    const cases: Case[] = [
      caseFixture({ id: 'admin-fav', favorite: true, role: CaseRoleEnum.ADMIN, status: CaseStatusEnum.RUNNING }),
      caseFixture({ id: 'member', favorite: false, role: CaseRoleEnum.MEMBER, status: CaseStatusEnum.IDLE }),
    ]
    const component = makeComponent(cases)
    const byId = (id: string) => component['rootItems']().find((i) => i.id === id)!
    expect(byId('admin-fav').canDelete).toBe(true)
    expect(byId('admin-fav').favorite).toBe(true)
    expect(byId('admin-fav').status).toBe(CaseStatusEnum.RUNNING)
    expect(byId('member').canDelete).toBe(false)
    expect(byId('member').status).toBe(CaseStatusEnum.IDLE)
  })

  it('groups favorited roots under a Favorites section at the TOP in tree mode', () => {
    const cases: Case[] = [caseFixture({ id: 'plain', favorite: false }), caseFixture({ id: 'fav', favorite: true })]
    const component = makeComponent(cases)
    const roots = component['rootItems']()
    expect(roots[0].id).toBe('fav')
    expect(roots[0].groupKey).toBe('favorites')
    expect(roots[1].id).toBe('plain')
    expect(roots[1].groupKey).toBeUndefined()
  })

  it('leaves roots ungrouped when nothing is favorited', () => {
    const cases: Case[] = [caseFixture({ id: 'a', favorite: false })]
    const component = makeComponent(cases)
    expect(component['rootItems']()[0].groupKey).toBeUndefined()
  })

  it('puts favorites FIRST and non-favorites at the BOTTOM in compact mode', () => {
    const cases: Case[] = [caseFixture({ id: 'plain', favorite: false }), caseFixture({ id: 'fav', favorite: true })]
    const component = makeComponent(cases)
    const compact = component['compactItems']()
    // Favorites first (top of sidebar), non-favorites last
    expect(compact[0].id).toBe('fav')
    expect(compact[1].id).toBe('plain')
  })

  it('marks isFirstFavorite on the last favorite in compact mode (divider position)', () => {
    const cases: Case[] = [caseFixture({ id: 'plain', favorite: false }), caseFixture({ id: 'fav', favorite: true })]
    const component = makeComponent(cases)
    const compact = component['compactItems']()
    expect(compact[0].isFirstFavorite).toBe(true) // last favorite = divider after it
    expect(compact[1].isFirstFavorite).toBe(false)
  })

  it('does not mark isFirstFavorite when there are no favorites', () => {
    const cases: Case[] = [caseFixture({ id: 'a', favorite: false }), caseFixture({ id: 'b', favorite: false })]
    const component = makeComponent(cases)
    const compact = component['compactItems']()
    expect(compact.every((i) => !i.isFirstFavorite)).toBe(true)
  })

  it('lets the user collapse an auto-expanded ancestor of the active case', () => {
    const cases = [
      caseFixture({ id: 'p', title: 'Parent' }),
      caseFixture({ id: 'c', title: 'Child', parentCaseId: 'p' }),
    ]
    const component = makeComponent(cases, 'c')
    expect(component['isExpanded']('p')).toBe(true)
    component['toggleExpand'](new Event('click'), 'p')
    expect(component['isExpanded']('p')).toBe(false)
    component['toggleExpand'](new Event('click'), 'p')
    expect(component['isExpanded']('p')).toBe(true)
  })
})
