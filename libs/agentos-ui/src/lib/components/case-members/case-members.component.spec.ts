import { TestBed } from '@angular/core/testing'
import {
  CaseMembershipControllerService,
  MemberItem,
  MemberItemRoleEnum,
  NamespaceMembershipControllerService,
  UserControllerService,
} from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of, throwError } from 'rxjs'
import { UserStateService } from '../../services/user-state.service'
import { CaseMembersComponent } from './case-members.component'

describe('CaseMembersComponent', () => {
  const CASE_ID = 'case-1'
  const NS_ID = 'ns-1'

  const caseMembers: MemberItem[] = [
    { id: 'me', externalId: 'me', email: 'me@example.com', role: MemberItemRoleEnum.ADMIN },
  ]
  const namespaceMembers: MemberItem[] = [
    { id: 'me', externalId: 'me', email: 'me@example.com', role: MemberItemRoleEnum.ADMIN },
    { id: 'ns-colleague', externalId: 'colleague', email: 'colleague@example.com', role: MemberItemRoleEnum.MEMBER },
  ]

  let caseMembership: { getMembersCaseMembership: jest.Mock; updateMembersCaseMembership: jest.Mock }
  let namespaceMembership: { getMembersNamespaceMembership: jest.Mock }
  let userController: { listAllUser: jest.Mock }

  /** The real CaseMemberStateService runs on top of mocked API services, so the spec sees which endpoints are hit. */
  function render(isAdmin: boolean) {
    TestBed.configureTestingModule({
      imports: [CaseMembersComponent],
      providers: [
        { provide: CaseMembershipControllerService, useValue: caseMembership },
        { provide: NamespaceMembershipControllerService, useValue: namespaceMembership },
        { provide: UserControllerService, useValue: userController },
        { provide: UserStateService, useValue: { currentUser: () => ({ id: 'me', isAdmin }), loadMe: jest.fn() } },
      ],
    })
    const fixture = TestBed.createComponent(CaseMembersComponent)
    fixture.componentRef.setInput('caseId', CASE_ID)
    fixture.componentRef.setInput('namespaceId', NS_ID)
    fixture.detectChanges()
    return fixture
  }

  /** Ids the autocomplete would suggest for an empty query. */
  async function suggestedIds(fixture: ReturnType<typeof render>): Promise<string[]> {
    const dataSource = fixture.componentInstance['memberDataSource']
    return (await firstValueFrom(dataSource.search(''))).map((item) => item.id)
  }

  beforeEach(() => {
    caseMembership = {
      getMembersCaseMembership: jest.fn().mockReturnValue(of(caseMembers)),
      updateMembersCaseMembership: jest.fn(),
    }
    namespaceMembership = { getMembersNamespaceMembership: jest.fn().mockReturnValue(of(namespaceMembers)) }
    userController = { listAllUser: jest.fn().mockReturnValue(of([])) }
  })

  it.each([
    ['a regular user', false],
    ['a super-admin', true],
  ])('shows the add-member autocomplete to %s', (_label, isAdmin) => {
    const fixture = render(isAdmin)
    expect(fixture.nativeElement.querySelector('ds-autocomplete-input')).not.toBeNull()
    expect(fixture.nativeElement.textContent).not.toContain('Only a platform super-admin')
  })

  it.each([
    ['a regular user', false],
    ['a super-admin', true],
  ])(
    'suggests the namespace members (minus current members) to %s, never the platform directory',
    async (_label, isAdmin) => {
      const fixture = render(isAdmin)
      expect(namespaceMembership.getMembersNamespaceMembership).toHaveBeenCalledWith(NS_ID)
      expect(userController.listAllUser).not.toHaveBeenCalled()
      expect(await suggestedIds(fixture)).toEqual(['ns-colleague'])
    }
  )

  it('keeps the roster usable with an empty autocomplete when the candidates cannot be loaded', async () => {
    namespaceMembership.getMembersNamespaceMembership.mockReturnValue(throwError(() => ({ status: 404 })))
    const fixture = render(false)
    expect(fixture.nativeElement.textContent).not.toContain('Failed to load case members')
    expect(fixture.nativeElement.textContent).toContain('me@example.com')
    expect(await suggestedIds(fixture)).toEqual([])
  })
})
