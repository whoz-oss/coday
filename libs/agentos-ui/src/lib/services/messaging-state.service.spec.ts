import { TestBed } from '@angular/core/testing'
import {
  MessagingBotDto,
  MessagingBotStatus,
  MessagingControllerService,
  MessagingIdentityDto,
  MessagingPlatform,
} from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of } from 'rxjs'
import { MessagingStateService } from './messaging-state.service'

describe('MessagingStateService', () => {
  const bot: MessagingBotDto = {
    id: 'bot-1',
    platform: MessagingPlatform.TELEGRAM,
    agentId: 'agent-1',
    agentName: 'Helper',
    namespaceId: 'ns-1',
    username: 'helper_bot',
    displayName: 'Helper',
    enabled: true,
    managed: false,
    status: MessagingBotStatus.RUNNING,
    url: 'https://t.me/helper_bot',
    linked: true,
    createdAt: '2026-11-04T10:00:00Z',
  }
  const identity: MessagingIdentityDto = {
    id: 'identity-1',
    platform: MessagingPlatform.TELEGRAM,
    botId: 'bot-1',
    botUsername: 'helper_bot',
    agentName: 'Helper',
    displayName: 'Ada L.',
    linkedAt: '2026-11-04T10:00:00Z',
  }

  let controller: {
    listMessagingBots: jest.Mock
    listMessagingIdentities: jest.Mock
    createMessagingLink: jest.Mock
    deleteMessagingIdentity: jest.Mock
    continueCaseInMessaging: jest.Mock
  }
  let service: MessagingStateService

  beforeEach(() => {
    controller = {
      listMessagingBots: jest.fn(() => of([bot])),
      listMessagingIdentities: jest.fn(() => of([identity])),
      createMessagingLink: jest.fn(() =>
        of({ url: 'https://t.me/helper_bot?start=x', expiresAt: '2026-11-04T10:10:00Z' })
      ),
      deleteMessagingIdentity: jest.fn(() => of(undefined)),
      continueCaseInMessaging: jest.fn(() => of(undefined)),
    }
    TestBed.configureTestingModule({ providers: [{ provide: MessagingControllerService, useValue: controller }] })
    service = TestBed.inject(MessagingStateService)
  })

  it('loads the bots the user can talk to and the accounts of the user', async () => {
    await firstValueFrom(service.load())

    expect(service.bots()).toEqual([bot])
    expect(service.identities()).toEqual([identity])
  })

  it('loads once for the headers of the cases', async () => {
    await firstValueFrom(service.ensureLoaded())
    await firstValueFrom(service.ensureLoaded())

    expect(controller.listMessagingIdentities).toHaveBeenCalledTimes(1)
  })

  it('asks for a link to a given bot', async () => {
    await firstValueFrom(service.createLink('bot-1'))

    expect(controller.createMessagingLink).toHaveBeenCalledWith('bot-1')
  })

  it('drops an unlinked account from the list, and its bot is no longer linked', async () => {
    await firstValueFrom(service.load())

    await firstValueFrom(service.unlink('identity-1'))

    expect(controller.deleteMessagingIdentity).toHaveBeenCalledWith('identity-1')
    expect(service.identities()).toEqual([])
    expect(service.bots()[0].linked).toBe(false)
  })

  it('continues a case with the bot asked for', async () => {
    await firstValueFrom(service.continueCase('case-1', 'bot-1'))

    expect(controller.continueCaseInMessaging).toHaveBeenCalledWith('case-1', { botId: 'bot-1' })
  })
})
