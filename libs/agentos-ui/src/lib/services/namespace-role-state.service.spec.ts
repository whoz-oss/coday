import { TestBed } from '@angular/core/testing'
import { NamespacePermissionEndpointsService, NamespaceUserListItemRoleEnum, User } from '@whoz-oss/agentos-api-client'
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

  let permissions: jest.Mocked<NamespacePermissionEndpointsService>
  let userState: UserStateService
  let service: NamespaceRoleStateService

  beforeEach(() => {
    permissions = {
      listNamespaceUsers: jest.fn(),
    } as unknown as jest.Mocked<NamespacePermissionEndpointsService>

    TestBed.configureTestingModule({
      providers: [
        UserStateService,
        NamespaceRoleStateService,
        { provide: NamespacePermissionEndpointsService, useValue: permissions },
      ],
    })
    userState = TestBed.inject(UserStateService)
    service = TestBed.inject(NamespaceRoleStateService)
  })

  it('returns false when no user is loaded yet (default-safe)', async () => {
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(false)
    expect(permissions.listNamespaceUsers).not.toHaveBeenCalled()
  })

  it('short-circuits to true for a super-admin without hitting the network', async () => {
    userState.currentUser.set(makeUser(true))
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(true)
    expect(permissions.listNamespaceUsers).not.toHaveBeenCalled()
  })

  it('returns true when the user appears as ADMIN in listNamespaceUsers', async () => {
    userState.currentUser.set(makeUser(false))
    permissions.listNamespaceUsers.mockReturnValue(
      of([{ id: USER_ID, externalId: 'me', email: 'me@example.com', role: NamespaceUserListItemRoleEnum.ADMIN }])
    )
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(true)
    expect(permissions.listNamespaceUsers).toHaveBeenCalledWith(NS_ID)
  })

  it('returns false when the user appears as MEMBER (not ADMIN) in listNamespaceUsers', async () => {
    userState.currentUser.set(makeUser(false))
    permissions.listNamespaceUsers.mockReturnValue(
      of([{ id: USER_ID, externalId: 'me', email: 'me@example.com', role: NamespaceUserListItemRoleEnum.MEMBER }])
    )
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(false)
  })

  it('returns false when the listNamespaceUsers call fails (default-safe on 403)', async () => {
    userState.currentUser.set(makeUser(false))
    permissions.listNamespaceUsers.mockReturnValue(throwError(() => new Error('403')))
    const result = await firstValueFrom(service.isAdminOfNamespace$(NS_ID))
    expect(result).toBe(false)
  })

  it('caches the lookup per namespaceId so concurrent subscribers share one HTTP call', async () => {
    userState.currentUser.set(makeUser(false))
    permissions.listNamespaceUsers.mockReturnValue(
      of([{ id: USER_ID, externalId: 'me', email: 'me@example.com', role: NamespaceUserListItemRoleEnum.ADMIN }])
    )
    await Promise.all([
      firstValueFrom(service.isAdminOfNamespace$(NS_ID)),
      firstValueFrom(service.isAdminOfNamespace$(NS_ID)),
      firstValueFrom(service.isAdminOfNamespace$(NS_ID)),
    ])
    expect(permissions.listNamespaceUsers).toHaveBeenCalledTimes(1)
  })

  it('returns false for an empty namespaceId without any HTTP call', async () => {
    userState.currentUser.set(makeUser(false))
    const result = await firstValueFrom(service.isAdminOfNamespace$(''))
    expect(result).toBe(false)
    expect(permissions.listNamespaceUsers).not.toHaveBeenCalled()
  })
})
