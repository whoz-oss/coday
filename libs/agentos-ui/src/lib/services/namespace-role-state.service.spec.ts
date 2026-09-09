import { TestBed } from '@angular/core/testing'
import { MemberItemRoleEnum, NamespaceMembershipControllerService, User } from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of, throwError } from 'rxjs'
import { NamespaceRoleStateService } from './namespace-role-state.service'
import { UserStateService } from './user-state.service'

describe('NamespaceRoleStateService', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'
  const USER_ID = 'me-id'

  function makeUser(isAdmin: boolean): User {
    return {
      id: USER_ID,
      externalId: 'me',
      email: 'me@example.com',
      isAdmin,
    }
  }

  let membership: jest.Mocked<NamespaceMembershipControllerService>
  let userState: UserStateService
  let service: NamespaceRoleStateService

  beforeEach(() => {
    membership = {
      getMembersNamespaceMembership: jest.fn(),
    } as unknown as jest.Mocked<NamespaceMembershipControllerService>

    TestBed.configureTestingModule({
      providers: [
        UserStateService,
        NamespaceRoleStateService,
        { provide: NamespaceMembershipControllerService, useValue: membership },
      ],
    })
    userState = TestBed.inject(UserStateService)
    service = TestBed.inject(NamespaceRoleStateService)
  })

  it('returns false when no user is loaded yet (default-safe)', async () => {
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(false)
    expect(membership.getMembersNamespaceMembership).not.toHaveBeenCalled()
  })

  it('short-circuits to true for a super-admin without hitting the network', async () => {
    userState.currentUser.set(makeUser(true))
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(true)
    expect(membership.getMembersNamespaceMembership).not.toHaveBeenCalled()
  })

  it('returns true when the user appears as ADMIN in getMembersNamespaceMembership', async () => {
    userState.currentUser.set(makeUser(false))
    membership.getMembersNamespaceMembership.mockReturnValue(
      of([{ id: USER_ID, externalId: 'me', email: 'me@example.com', role: MemberItemRoleEnum.ADMIN }])
    )
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(true)
    expect(membership.getMembersNamespaceMembership).toHaveBeenCalledWith(NS_ID)
  })

  it('returns false when the user appears as MEMBER (not ADMIN) in getMembersNamespaceMembership', async () => {
    userState.currentUser.set(makeUser(false))
    membership.getMembersNamespaceMembership.mockReturnValue(
      of([{ id: USER_ID, externalId: 'me', email: 'me@example.com', role: MemberItemRoleEnum.MEMBER }])
    )
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(false)
  })

  it('returns false when the getMembersNamespaceMembership call fails (default-safe on 403)', async () => {
    userState.currentUser.set(makeUser(false))
    membership.getMembersNamespaceMembership.mockReturnValue(throwError(() => new Error('403')))
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(false)
  })

  it('caches the lookup per namespaceId so concurrent subscribers share one HTTP call', async () => {
    userState.currentUser.set(makeUser(false))
    membership.getMembersNamespaceMembership.mockReturnValue(
      of([{ id: USER_ID, externalId: 'me', email: 'me@example.com', role: MemberItemRoleEnum.ADMIN }])
    )
    await Promise.all([
      firstValueFrom(service.isAdminOfNamespace$(NS_ID)),
      firstValueFrom(service.isAdminOfNamespace$(NS_ID)),
      firstValueFrom(service.isAdminOfNamespace$(NS_ID)),
    ])
    expect(membership.getMembersNamespaceMembership).toHaveBeenCalledTimes(1)
  })

  it('returns false for an empty namespaceId without any HTTP call', async () => {
    userState.currentUser.set(makeUser(false))
    const result = await firstValueFrom(service.isAdminOfNamespace$(''))
    expect(result).toBe(false)
    expect(membership.getMembersNamespaceMembership).not.toHaveBeenCalled()
  })
})
