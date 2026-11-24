import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfigControllerService,
  NamespaceMembershipControllerService,
  UserGroupControllerService,
} from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { UserGroupFormComponent } from './user-group-form.component'

const member = (externalId: string, role = 'MEMBER') => ({ externalId, userId: `id-${externalId}`, role })
const page = (data: ReturnType<typeof member>[], next: string | null, totalCount: number) => ({
  data,
  metadata: { next, totalCount },
})

describe('UserGroupFormComponent agent sharing', () => {
  let fixture: ComponentFixture<UserGroupFormComponent>
  let groupId: string | null
  let existingAgentIds: string[]
  let groups: {
    findByNamespaceIdUserGroup: jest.Mock
    getByIdUserGroup: jest.Mock
    listMembersUserGroup: jest.Mock
    createUserGroup: jest.Mock
    updateUserGroup: jest.Mock
  }

  beforeEach(() => {
    groupId = null
    existingAgentIds = []
    groups = {
      findByNamespaceIdUserGroup: jest.fn().mockReturnValue(
        of([
          {
            userGroupId: 'sales',
            namespaceId: 'ns-1',
            name: 'Sales',
            agentIds: ['platform-agent', 'namespace-agent'],
          },
        ])
      ),
      getByIdUserGroup: jest
        .fn()
        .mockImplementation(() => of({ userGroupId: groupId, name: 'Presales', agentIds: existingAgentIds })),
      listMembersUserGroup: jest.fn().mockReturnValue(of(page([], null, 0))),
      createUserGroup: jest.fn().mockReturnValue(of({})),
      updateUserGroup: jest.fn().mockReturnValue(of({})),
    }
    TestBed.configureTestingModule({
      imports: [UserGroupFormComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              params: { namespaceId: 'ns-1' },
              paramMap: { get: () => groupId },
              queryParamMap: { get: () => null },
            },
          },
        },
        { provide: Router, useValue: { navigate: jest.fn() } },
        { provide: UserGroupControllerService, useValue: groups },
        {
          provide: AgentConfigControllerService,
          useValue: {
            listByParentAgentConfig: jest
              .fn()
              .mockReturnValue(of([{ id: 'namespace-agent', namespaceId: 'ns-1', name: 'Namespace assistant' }])),
            listPlatformAgentsAgentConfig: jest
              .fn()
              .mockReturnValue(of([{ id: 'platform-agent', name: 'Platform assistant' }])),
          },
        },
        { provide: NamespaceMembershipControllerService, useValue: { getMembersNamespaceMembership: () => of([]) } },
      ],
    })
  })

  function render(): HTMLInputElement[] {
    fixture = TestBed.createComponent(UserGroupFormComponent)
    fixture.detectChanges()
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>('.user-group-form__agent-checkbox')
    )
  }

  function submit(): void {
    const element = fixture.nativeElement as HTMLElement
    const name = element.querySelector<HTMLInputElement>('#user-group-name')!
    name.value = 'Presales'
    name.dispatchEvent(new Event('input'))
    element.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    fixture.detectChanges()
  }

  it.each(['create', 'edit'])('allows sharing platform and namespace agents with another group on %s', (mode) => {
    groupId = mode === 'edit' ? 'presales' : null
    const checkboxes = render()

    expect(checkboxes).toHaveLength(2)
    for (const checkbox of checkboxes) {
      expect(checkbox.disabled).toBe(false)
      expect(checkbox.checked).toBe(false)
      checkbox.click()
    }
    fixture.detectChanges()
    expect(checkboxes.every((checkbox) => checkbox.checked)).toBe(true)
    submit()

    const payload = {
      name: 'Presales',
      agentIds: ['platform-agent', 'namespace-agent'],
      userExternalIdsToAdd: [],
      adminExternalIds: [],
    }
    if (mode === 'create') {
      expect(groups.createUserGroup).toHaveBeenCalledWith({ ...payload, namespaceId: 'ns-1' })
      expect(groups.updateUserGroup).not.toHaveBeenCalled()
    } else {
      expect(groups.updateUserGroup).toHaveBeenCalledWith('presales', {
        ...payload,
        userExternalIdsToRemove: [],
        adminExternalIdsToAdd: [],
        adminExternalIdsToRemove: [],
      })
      expect(groups.createUserGroup).not.toHaveBeenCalled()
    }
  })

  it('allows removing shared agents from the edited group without updating the other group', () => {
    groupId = 'presales'
    existingAgentIds = ['platform-agent', 'namespace-agent']
    const checkboxes = render()

    expect(checkboxes).toHaveLength(2)
    for (const checkbox of checkboxes) {
      expect(checkbox.checked).toBe(true)
      expect(checkbox.disabled).toBe(false)
      checkbox.click()
    }
    fixture.detectChanges()
    expect(checkboxes.every((checkbox) => !checkbox.checked)).toBe(true)
    submit()

    expect(groups.updateUserGroup).toHaveBeenCalledTimes(1)
    expect(groups.updateUserGroup).toHaveBeenCalledWith('presales', expect.objectContaining({ agentIds: [] }))
    expect(groups.createUserGroup).not.toHaveBeenCalled()
  })

  it('appends the next page with the cursor, and a failed load-more retries the same cursor', () => {
    groupId = 'presales'
    jest.spyOn(console, 'error').mockImplementation()
    groups.listMembersUserGroup
      .mockReturnValueOnce(of(page([member('alice')], 'cursor-1', 2)))
      .mockReturnValueOnce(throwError(() => new Error('boom')))
      .mockReturnValueOnce(of(page([member('bob')], null, 2)))
    render()
    const loadMore = () => (fixture.componentInstance as unknown as { loadMoreMembers: () => void }).loadMoreMembers()

    loadMore()
    loadMore()

    expect(groups.listMembersUserGroup).toHaveBeenNthCalledWith(2, 'presales', { next: 'cursor-1' })
    expect(groups.listMembersUserGroup).toHaveBeenNthCalledWith(3, 'presales', { next: 'cursor-1' })
  })

  it('partial roster edit sends a delta, never the full admin set, and no removals for unloaded members', () => {
    groupId = 'presales'
    groups.listMembersUserGroup.mockReturnValue(of(page([member('alice', 'ADMIN'), member('bob')], 'cursor-1', 300)))
    render()
    const [alice, bob] = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLSelectElement>('.user-group-form__member-role')
    )
    alice!.value = 'MEMBER'
    alice!.dispatchEvent(new Event('change'))
    bob!.value = 'ADMIN'
    bob!.dispatchEvent(new Event('change'))
    submit()

    expect(groups.updateUserGroup).toHaveBeenCalledWith('presales', {
      name: 'Presales',
      agentIds: [],
      userExternalIdsToAdd: [],
      userExternalIdsToRemove: [],
      adminExternalIds: [],
      adminExternalIdsToAdd: ['bob'],
      adminExternalIdsToRemove: ['alice'],
    })
  })

  describe('member picked while other pages are not loaded', () => {
    const pick = (externalId: string) => {
      const form = fixture.componentInstance as unknown as {
        onMemberSelected: (item: { id: string; name: string }) => void
      }
      form.onMemberSelected({ id: externalId, name: externalId })
      fixture.detectChanges()
    }
    const selects = () =>
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLSelectElement>('.user-group-form__member-role')
      )
    const loadMore = () => (fixture.componentInstance as unknown as { loadMoreMembers: () => void }).loadMoreMembers()

    beforeEach(() => {
      groupId = 'presales'
      groups.listMembersUserGroup.mockReturnValue(of(page([member('alice', 'ADMIN')], 'cursor-1', 300)))
      render()
    })

    it('demotes an unloaded server admin added as MEMBER', () => {
      pick('zed')
      submit()

      expect(groups.updateUserGroup).toHaveBeenCalledWith(
        'presales',
        expect.objectContaining({
          userExternalIdsToAdd: ['zed'],
          adminExternalIdsToAdd: [],
          adminExternalIdsToRemove: ['zed'],
        })
      )
    })

    it('promotes it when picked as ADMIN', () => {
      pick('zed')
      const zed = selects()[1]!
      zed.value = 'ADMIN'
      zed.dispatchEvent(new Event('change'))
      submit()

      expect(groups.updateUserGroup).toHaveBeenCalledWith(
        'presales',
        expect.objectContaining({ adminExternalIdsToAdd: ['zed'], adminExternalIdsToRemove: [] })
      )
    })

    it('keeps the chosen role when the page holding that member loads later', () => {
      pick('zed')
      groups.listMembersUserGroup.mockReturnValue(of(page([member('zed', 'ADMIN')], null, 300)))
      loadMore()
      fixture.detectChanges()
      submit()

      expect(selects().map((select) => select.value)).toEqual(['ADMIN', 'MEMBER'])
      expect(groups.updateUserGroup).toHaveBeenCalledWith(
        'presales',
        expect.objectContaining({
          userExternalIdsToAdd: [],
          adminExternalIdsToAdd: [],
          adminExternalIdsToRemove: ['zed'],
        })
      )
    })

    it('leaves loaded members unchanged', () => {
      pick('zed')
      submit()

      expect(groups.updateUserGroup).toHaveBeenCalledWith(
        'presales',
        expect.objectContaining({ adminExternalIdsToRemove: ['zed'], userExternalIdsToRemove: [] })
      )
    })
  })
})
