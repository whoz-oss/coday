import { TestBed } from '@angular/core/testing'
import { MemberItem, MemberItemRoleEnum, User } from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of, throwError } from 'rxjs'
import { CaseMemberStateService } from '../../services/case-member-state.service'
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
  const platformUsers: User[] = [
    { id: 'me', externalId: 'me', email: 'me@example.com', isAdmin: true },
    { id: 'outsider', externalId: 'outsider', email: 'outsider@example.com', isAdmin: false },
  ]

  let memberState: {
    listMembers: jest.Mock
    listAllUsers: jest.Mock
    listNamespaceMembers: jest.Mock
    updateMembers: jest.Mock
  }

  function render(isAdmin: boolean) {
    TestBed.configureTestingModule({
      imports: [CaseMembersComponent],
      providers: [
        { provide: CaseMemberStateService, useValue: memberState },
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
    memberState = {
      listMembers: jest.fn().mockReturnValue(of(caseMembers)),
      listAllUsers: jest.fn().mockReturnValue(of(platformUsers)),
      listNamespaceMembers: jest.fn().mockReturnValue(of(namespaceMembers)),
      updateMembers: jest.fn(),
    }
  })

  it('shows the add-member autocomplete to a non-super-admin', () => {
    const fixture = render(false)
    expect(fixture.nativeElement.querySelector('ds-autocomplete-input')).not.toBeNull()
    expect(fixture.nativeElement.textContent).not.toContain('Only a platform super-admin')
  })

  it('suggests the namespace members (minus current members) to a non-super-admin', async () => {
    const fixture = render(false)
    expect(memberState.listNamespaceMembers).toHaveBeenCalledWith(NS_ID)
    expect(memberState.listAllUsers).not.toHaveBeenCalled()
    expect(await suggestedIds(fixture)).toEqual(['ns-colleague'])
  })

  it('suggests the whole platform directory to a super-admin', async () => {
    const fixture = render(true)
    expect(memberState.listAllUsers).toHaveBeenCalled()
    expect(memberState.listNamespaceMembers).not.toHaveBeenCalled()
    expect(fixture.nativeElement.querySelector('ds-autocomplete-input')).not.toBeNull()
    expect(await suggestedIds(fixture)).toEqual(['outsider'])
  })

  it('keeps the roster usable with an empty autocomplete when the candidates cannot be loaded', async () => {
    memberState.listNamespaceMembers.mockReturnValue(throwError(() => ({ status: 404 })))
    const fixture = render(false)
    expect(fixture.nativeElement.textContent).not.toContain('Failed to load case members')
    expect(fixture.nativeElement.textContent).toContain('me@example.com')
    expect(await suggestedIds(fixture)).toEqual([])
  })
})
