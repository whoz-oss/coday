import { TestBed } from '@angular/core/testing'
import { PromptCacheTtl, User, UserControllerService } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { UserStateService } from './user-state.service'

describe('UserStateService.updateMe', () => {
  let controller: { getMeUser: jest.Mock; updateUser: jest.Mock }
  let service: UserStateService

  const me: User = {
    id: 'u-1',
    email: 'alice@example.com',
    externalId: 'alice@example.com',
    firstname: 'Alice',
    isAdmin: false,
    promptCacheTtl: PromptCacheTtl.FIVE_MINUTES,
  }

  beforeEach(() => {
    controller = {
      getMeUser: jest.fn().mockReturnValue(of(me)),
      updateUser: jest.fn().mockImplementation((_id: string, user: User) => of(user)),
    }
    TestBed.configureTestingModule({ providers: [{ provide: UserControllerService, useValue: controller }] })
    service = TestBed.inject(UserStateService)
    service.loadMe().subscribe()
  })

  it('sends the full user with the chosen prompt cache duration and stores the result', () => {
    service
      .updateMe({
        firstname: 'Alice',
        lastname: undefined,
        bio: undefined,
        preferredLanguage: undefined,
        promptCacheTtl: PromptCacheTtl.ONE_HOUR,
      })
      .subscribe()

    expect(controller.updateUser).toHaveBeenCalledWith('u-1', { ...me, promptCacheTtl: PromptCacheTtl.ONE_HOUR })
    expect(service.currentUser()?.promptCacheTtl).toBe(PromptCacheTtl.ONE_HOUR)
  })

  it('throws when the current user is not loaded', () => {
    service.currentUser.set(null)

    expect(() =>
      service.updateMe({
        firstname: undefined,
        lastname: undefined,
        bio: undefined,
        preferredLanguage: undefined,
        promptCacheTtl: PromptCacheTtl.ONE_HOUR,
      })
    ).toThrow('Cannot update: current user not loaded')
  })
})
