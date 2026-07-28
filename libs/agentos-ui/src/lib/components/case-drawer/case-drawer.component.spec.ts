import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Case, CaseRoleEnum } from '@whoz-oss/agentos-api-client'
import { CaseDrawerComponent, CaseTreeItem } from './case-drawer.component'

const adminNode = (overrides: Partial<CaseTreeItem> = {}): CaseTreeItem => ({
  id: 'x',
  name: 'x',
  favorite: false,
  canDelete: true,
  canRename: true,
  status: 'IDLE',
  children: [],
  ...overrides,
})

const memberNode = (overrides: Partial<CaseTreeItem> = {}): CaseTreeItem =>
  adminNode({ id: 'y', name: 'y', canDelete: false, canRename: false, ...overrides })

/**
 * Instantiate CaseDrawerComponent inside an injection context and wire up
 * signal inputs via ComponentRef.setInput().
 *
 * We use createComponent() + ApplicationRef.attachView() so that signal
 * inputs are properly initialised (input() requires an injection context).
 *
 * Returns the ComponentRef so a test can push new inputs afterwards; use [makeComponent]
 * when only the instance is needed.
 */
function makeComponentRef(cases: Case[] = [], activeCaseId: string | null = null): ComponentRef<CaseDrawerComponent> {
  const environmentInjector = TestBed.inject(EnvironmentInjector)
  const appRef = TestBed.inject(ApplicationRef)

  const ref = createComponent(CaseDrawerComponent, { environmentInjector })
  appRef.attachView(ref.hostView)

  if (cases.length) ref.setInput('cases', cases)
  if (activeCaseId) ref.setInput('activeCaseId', activeCaseId)
  ref.changeDetectorRef.detectChanges()

  return ref
}

function makeComponent(cases: Case[] = [], activeCaseId: string | null = null): CaseDrawerComponent {
  return makeComponentRef(cases, activeCaseId).instance
}

/**
 * Change detection PLUS the after-render hooks. The rename editor's focus and its
 * abandon-when-the-row-is-gone guard both live in an afterRenderEffect, which detectChanges()
 * alone does not run.
 */
function tick(ref: ComponentRef<CaseDrawerComponent>): void {
  ref.changeDetectorRef.detectChanges()
  TestBed.inject(ApplicationRef).tick()
}

function host(ref: ComponentRef<CaseDrawerComponent>): HTMLElement {
  return ref.location.nativeElement as HTMLElement
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

    component['onStarToggled']({ id: 'case-9', name: 'case-9', favorite: false, canDelete: false, canRename: false })

    expect(emitted).toEqual([{ id: 'case-9', starred: true }])
  })

  it('emits starToggled with starred=false when un-starring a favorited case', () => {
    const component = makeComponent()
    const emitted: Array<{ id: string; starred: boolean }> = []
    component.starToggled.subscribe((e) => emitted.push(e))

    component['onStarToggled']({ id: 'case-9', name: 'case-9', favorite: true, canDelete: false, canRename: false })

    expect(emitted).toEqual([{ id: 'case-9', starred: false }])
  })

  it('does not mutate the node on toggle (optimism lives in CaseStateService)', () => {
    const component = makeComponent()
    const item = { id: 'case-9', name: 'case-9', favorite: false, canDelete: false, canRename: false }

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

  it('carries favorite, canDelete and canRename onto each tree node', () => {
    const cases: Case[] = [
      { id: 'admin-fav', namespaceId: 'ns', favorite: true, role: CaseRoleEnum.ADMIN } as unknown as Case,
      { id: 'member', namespaceId: 'ns', favorite: false, role: CaseRoleEnum.MEMBER } as unknown as Case,
    ]
    const component = makeComponent(cases)

    const byId = (id: string) => component['rootItems']().find((i) => i.id === id)!
    expect(byId('admin-fav').canDelete).toBe(true)
    expect(byId('admin-fav').canRename).toBe(true)
    expect(byId('admin-fav').favorite).toBe(true)
    expect(byId('member').canDelete).toBe(false)
    expect(byId('member').canRename).toBe(false)
  })

  it('groups favorited roots under a Favorites section (favorites first)', () => {
    const cases: Case[] = [
      { id: 'plain', namespaceId: 'ns', favorite: false } as unknown as Case,
      { id: 'fav', namespaceId: 'ns', favorite: true } as unknown as Case,
    ]
    const component = makeComponent(cases)

    const roots = component['rootItems']()
    // Favorites are promoted to the top under a "Favorites" group.
    expect(roots[0].id).toBe('fav')
    expect(roots[0].groupKey).toBe('favorites')
    expect(roots[0].groupLabel).toBe('Favorites')
    // Non-favorites are grouped by date below (no date = 'older').
    expect(roots[1].id).toBe('plain')
    expect(roots[1].groupKey).toBe('older')
  })

  it('keeps a favorited sub-case nested under its favorited parent (not flattened)', () => {
    const cases: Case[] = [
      { id: 'parent', namespaceId: 'ns', title: 'Parent', favorite: true } as unknown as Case,
      {
        id: 'child-fav',
        namespaceId: 'ns',
        title: 'Child fav',
        parentCaseId: 'parent',
        favorite: true,
      } as unknown as Case,
      {
        id: 'child-plain',
        namespaceId: 'ns',
        title: 'Child plain',
        parentCaseId: 'parent',
        favorite: false,
      } as unknown as Case,
    ]
    const component = makeComponent(cases)

    const roots = component['rootItems']()
    // Only the parent is a top-level Favorites entry — the favorited child is NOT flattened beside it.
    expect(roots.map((i) => i.id)).toEqual(['parent'])
    expect(roots[0].groupKey).toBe('favorites')
    // Both children (favorited and not) stay nested under the favorited parent.
    expect(roots[0].children.map((i) => i.id).sort()).toEqual(['child-fav', 'child-plain'])
  })

  it('groups by date when nothing is favorited', () => {
    const cases: Case[] = [{ id: 'a', namespaceId: 'ns', favorite: false } as unknown as Case]
    const component = makeComponent(cases)

    // No favorites: cases are grouped by recency (no date = falls into "older").
    expect(component['rootItems']()[0].groupKey).toBe('older')
  })

  it('builds overflow-menu items: star toggle, and delete only when the caller may delete', () => {
    const component = makeComponent()

    const admin = component['menuItemsFor'](adminNode())
    expect(admin.map((i) => i.key)).toEqual(['star', 'delete'])
    expect(admin[0].label).toBe('Add to favorites')
    expect(admin.find((i) => i.key === 'delete')?.variant).toBe('danger')

    const favMember = component['menuItemsFor'](memberNode({ favorite: true }))
    expect(favMember.map((i) => i.key)).toEqual(['star'])
    expect(favMember[0].label).toBe('Remove from favorites')
  })

  it('dispatches overflow-menu actions to star and delete', () => {
    const component = makeComponent()
    const stars: Array<{ id: string; starred: boolean }> = []
    const deletes: string[] = []
    component.starToggled.subscribe((e) => stars.push(e))
    component.deleteRequested.subscribe((id) => deletes.push(id))
    const node = adminNode({ id: 'case-1', name: 'case-1' })

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

  describe('inline rename', () => {
    const CASE_A = { id: 'a', namespaceId: 'ns', title: 'Old name', role: CaseRoleEnum.ADMIN } as unknown as Case
    const editedNode = (name = 'Old name') => adminNode({ id: 'a', name })

    /** Collect renameRequested emissions for the assertions below. */
    function captureRenames(component: CaseDrawerComponent): Array<{ id: string; title: string }> {
      const emitted: Array<{ id: string; title: string }> = []
      component.renameRequested.subscribe((e) => emitted.push(e))
      return emitted
    }

    it('opens the editor seeded with the row label when rename is picked', () => {
      const component = makeComponent([CASE_A])

      component['startRename'](editedNode())

      expect(component['editingCaseId']()).toBe('a')
      expect(component['editingTitle']()).toBe('Old name')
      expect(component['renameError']()).toBeNull()
    })

    it('emits the trimmed title and leaves edit mode on commit', () => {
      const component = makeComponent([CASE_A])
      const emitted = captureRenames(component)

      component['startRename'](editedNode())
      component['onRenameInput']('  New name  ')
      component['commitRename']()

      expect(emitted).toEqual([{ id: 'a', title: 'New name' }])
      expect(component['editingCaseId']()).toBeNull()
    })

    it('does not emit when the title is unchanged (nothing to persist)', () => {
      const component = makeComponent([CASE_A])
      const emitted = captureRenames(component)

      component['startRename'](editedNode())
      component['commitRename']()

      expect(emitted).toEqual([])
      expect(component['editingCaseId']()).toBeNull()
    })

    it('rejects an empty title, keeps the editor open and emits nothing', () => {
      const component = makeComponent([CASE_A])
      const emitted = captureRenames(component)

      component['startRename'](editedNode())
      component['onRenameInput']('   ')
      component['commitRename']()

      expect(emitted).toEqual([])
      expect(component['renameError']()).toBe('Name cannot be empty')
      expect(component['editingCaseId']()).toBe('a')
    })

    it('rejects a title over the maximum length', () => {
      const component = makeComponent([CASE_A])
      const emitted = captureRenames(component)

      component['startRename'](editedNode())
      component['onRenameInput']('x'.repeat(201))
      component['commitRename']()

      expect(emitted).toEqual([])
      expect(component['renameError']()).toContain('too long')
    })

    it('clears the error as soon as the user edits the draft again', () => {
      const component = makeComponent([CASE_A])

      component['startRename'](editedNode())
      component['onRenameInput']('')
      component['commitRename']()
      expect(component['renameError']()).not.toBeNull()

      component['onRenameInput']('New name')

      expect(component['renameError']()).toBeNull()
    })

    it('emits once when commit is called twice', () => {
      const component = makeComponent([CASE_A])
      const emitted = captureRenames(component)

      component['startRename'](editedNode())
      component['onRenameInput']('New name')
      component['commitRename']()
      component['commitRename']()

      expect(emitted).toHaveLength(1)
    })

    it('emits once when the teardown blur follows a commit', () => {
      const component = makeComponent([CASE_A])
      const emitted = captureRenames(component)

      component['startRename'](editedNode())
      component['onRenameInput']('New name')
      component['commitRename']()
      component['onRenameBlur']()

      expect(emitted).toHaveLength(1)
    })

    it('emits nothing when a blur follows a cancel', () => {
      const component = makeComponent([CASE_A])
      const emitted = captureRenames(component)

      component['startRename'](editedNode())
      component['onRenameInput']('New name')
      component['cancelRename']()
      component['onRenameBlur']()

      expect(emitted).toEqual([])
    })

    it('discards an invalid draft on blur rather than leaving the row stuck in edit mode', () => {
      const component = makeComponent([CASE_A])
      const emitted = captureRenames(component)

      component['startRename'](editedNode())
      component['onRenameInput']('')
      component['onRenameBlur']()

      expect(emitted).toEqual([])
      expect(component['editingCaseId']()).toBeNull()
    })

    it('commits on Enter and cancels on Escape', () => {
      const component = makeComponent([CASE_A])
      const emitted = captureRenames(component)

      component['startRename'](editedNode())
      component['onRenameInput']('Renamed')
      component['onRenameKeydown'](new KeyboardEvent('keydown', { key: 'Enter' }))
      expect(emitted).toEqual([{ id: 'a', title: 'Renamed' }])

      component['startRename'](editedNode('Renamed'))
      component['onRenameInput']('Another')
      component['onRenameKeydown'](new KeyboardEvent('keydown', { key: 'Escape' }))

      expect(emitted).toHaveLength(1)
      expect(component['editingCaseId']()).toBeNull()
    })

    it('keeps the editor open when the tree is rebuilt around it', () => {
      // The optimistic title patch replaces every tree node object, hence the id keying.
      const ref = makeComponentRef([CASE_A])
      ref.instance['startRename'](editedNode())

      ref.setInput('cases', [{ ...CASE_A, title: 'Patched' } as unknown as Case])
      tick(ref)

      expect(ref.instance['editingCaseId']()).toBe('a')
    })

    // The editor is abandoned whenever its row stops being rendered. The guard checks the
    // input's presence rather than enumerating the reasons a row can disappear, so every one
    // of these is covered by the same code path. (blur) never fires when Angular destroys the
    // view, so without the guard the draft would survive and re-open on stale state.
    it.each([
      ['the case leaves the list', (ref: ComponentRef<CaseDrawerComponent>) => ref.setInput('cases', [])],
      ['a search hides the row', (ref: ComponentRef<CaseDrawerComponent>) => ref.setInput('filterQuery', 'zzz')],
      ['compact mode replaces the tree', (ref: ComponentRef<CaseDrawerComponent>) => ref.setInput('compact', true)],
    ])('abandons the edit when %s', (_label, mutate) => {
      const ref = makeComponentRef([CASE_A])
      ref.instance['startRename'](editedNode())
      tick(ref)
      expect(ref.instance['editingCaseId']()).toBe('a')

      mutate(ref)
      tick(ref)

      expect(ref.instance['editingCaseId']()).toBeNull()
    })

    it('abandons the edit when an ancestor collapses because the active case changed', () => {
      // Renaming a sub-case then navigating away collapses its parent, destroying the row.
      const parent = { id: 'p', namespaceId: 'ns', title: 'Parent', role: CaseRoleEnum.ADMIN } as unknown as Case
      const child = {
        id: 'c',
        namespaceId: 'ns',
        title: 'Child',
        parentCaseId: 'p',
        role: CaseRoleEnum.ADMIN,
      } as unknown as Case
      const other = { id: 'o', namespaceId: 'ns', title: 'Other', role: CaseRoleEnum.ADMIN } as unknown as Case
      const ref = makeComponentRef([parent, child, other], 'c')

      ref.instance['startRename'](adminNode({ id: 'c', name: 'Child' }))
      tick(ref)
      expect(host(ref).querySelector('.case-tree-node__name-input')).not.toBeNull()

      ref.setInput('activeCaseId', 'o')
      tick(ref)

      expect(ref.instance['isExpanded']('p')).toBe(false)
      expect(ref.instance['editingCaseId']()).toBeNull()
    })
  })

  // The harness renders a real host element, so the template wiring can be asserted directly
  // rather than only through the protected handlers.
  describe('inline rename, rendered', () => {
    const CASE_A = { id: 'a', namespaceId: 'ns', title: 'Old name', role: CaseRoleEnum.ADMIN } as unknown as Case

    it('shows a rename action only when the caller may rename', () => {
      const admin = makeComponentRef([CASE_A])
      expect(host(admin).querySelector('[title="Rename case"]')).not.toBeNull()

      const member = makeComponentRef([{ ...CASE_A, role: CaseRoleEnum.MEMBER } as unknown as Case])
      expect(host(member).querySelector('[title="Rename case"]')).toBeNull()
    })

    it('swaps the title button for an input seeded with the current name when the action is clicked', () => {
      const ref = makeComponentRef([CASE_A])

      host(ref).querySelector<HTMLButtonElement>('[title="Rename case"]')!.click()
      tick(ref)

      const input = host(ref).querySelector<HTMLInputElement>('.case-tree-node__name-input')
      expect(input).not.toBeNull()
      expect(input!.value).toBe('Old name')
      expect(host(ref).querySelector('.case-tree-node__select')).toBeNull()
    })

    it('commits through the template wiring on blur', () => {
      const ref = makeComponentRef([CASE_A])
      const emitted: Array<{ id: string; title: string }> = []
      ref.instance.renameRequested.subscribe((e) => emitted.push(e))

      host(ref).querySelector<HTMLButtonElement>('[title="Rename case"]')!.click()
      tick(ref)
      const input = host(ref).querySelector<HTMLInputElement>('.case-tree-node__name-input')!
      input.value = 'New name'
      input.dispatchEvent(new Event('input'))
      input.dispatchEvent(new Event('blur'))

      expect(emitted).toEqual([{ id: 'a', title: 'New name' }])
    })

    it('renders the validation message as an alert tied to the input', () => {
      const ref = makeComponentRef([CASE_A])

      host(ref).querySelector<HTMLButtonElement>('[title="Rename case"]')!.click()
      tick(ref)
      const input = host(ref).querySelector<HTMLInputElement>('.case-tree-node__name-input')!
      input.value = '  '
      input.dispatchEvent(new Event('input'))
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
      tick(ref)

      const error = host(ref).querySelector('.case-tree-node__rename-error')
      expect(error?.getAttribute('role')).toBe('alert')
      expect(error?.textContent).toContain('cannot be empty')
      expect(input.getAttribute('aria-invalid')).toBe('true')
      expect(input.getAttribute('aria-describedby')).toBe(error?.id)
    })
  })
})
