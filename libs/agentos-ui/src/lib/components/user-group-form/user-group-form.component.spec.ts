import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfigControllerService, UserGroupControllerService } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { UserGroupFormComponent } from './user-group-form.component'

describe('UserGroupFormComponent agent sharing', () => {
  let fixture: ComponentFixture<UserGroupFormComponent>
  let groupId: string | null
  let existingAgentIds: string[]
  let groups: {
    findByNamespaceIdUserGroup: jest.Mock
    getByIdUserGroup: jest.Mock
    getMembersUserGroup: jest.Mock
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
      getMembersUserGroup: jest.fn().mockReturnValue(of([])),
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
      expect(groups.updateUserGroup).toHaveBeenCalledWith('presales', { ...payload, userExternalIdsToRemove: [] })
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
})
