import { ComponentFixture, TestBed } from '@angular/core/testing'
import { MessagingBotDto, MessagingBotStatus, MessagingPlatform } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { MessagingBotConfigService } from '../../services/messaging-bot-config.service'
import { MessagingStateService } from '../../services/messaging-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { AgentMessagingComponent } from './agent-messaging.component'

describe('AgentMessagingComponent', () => {
  const bot: MessagingBotDto = {
    id: 'bot-1',
    platform: MessagingPlatform.TELEGRAM,
    agentId: 'agent-1',
    agentName: 'Helper',
    namespaceId: 'ns-1',
    username: 'helper_bot',
    displayName: 'Helper',
    enabled: true,
    managed: true,
    status: MessagingBotStatus.RUNNING,
    url: 'https://t.me/helper_bot',
    linked: false,
    createdAt: '2026-11-04T10:00:00Z',
  }

  let fixture: ComponentFixture<AgentMessagingComponent>
  let botConfig: {
    agentBots: jest.Mock
    attach: jest.Mock
    requestCreation: jest.Mock
    setEnabled: jest.Mock
    remove: jest.Mock
  }
  let messagingState: { capabilities: jest.Mock; createLink: jest.Mock; load: jest.Mock }

  function render(options: { admin: boolean; canCreate: boolean; bots: MessagingBotDto[] }): HTMLElement {
    botConfig.agentBots.mockReturnValue(of(options.bots))
    messagingState.capabilities.mockReturnValue(
      of({ botCreation: options.canCreate ? [MessagingPlatform.TELEGRAM] : [] })
    )
    TestBed.configureTestingModule({
      imports: [AgentMessagingComponent],
      providers: [
        { provide: MessagingBotConfigService, useValue: botConfig },
        { provide: MessagingStateService, useValue: messagingState },
        { provide: NamespaceRoleStateService, useValue: { isAdminOfNamespace$: () => of(options.admin) } },
      ],
    })
    fixture = TestBed.createComponent(AgentMessagingComponent)
    fixture.componentRef.setInput('namespaceId', 'ns-1')
    fixture.componentRef.setInput('agentId', 'agent-1')
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function button(element: HTMLElement, label: string): HTMLButtonElement {
    const found = Array.from(element.querySelectorAll('button')).find((b) => b.textContent?.trim() === label)
    if (!found) throw new Error(`No button "${label}" in: ${element.textContent}`)
    return found
  }

  beforeEach(() => {
    botConfig = {
      agentBots: jest.fn(),
      attach: jest.fn(() => of(bot)),
      manifest: jest.fn(() =>
        of({
          platform: MessagingPlatform.SLACK,
          manifest: '{"name":"Helper"}',
          createUrl: 'https://api.slack.com/apps?new_app=1',
        })
      ),
      requestCreation: jest.fn(() =>
        of({ url: 'https://t.me/agentos_manager_bot?start=x', expiresAt: '2099-01-01T00:00:00Z' })
      ),
      setEnabled: jest.fn(() => of({ ...bot, enabled: false, status: MessagingBotStatus.DISABLED })),
      remove: jest.fn(() => of(undefined)),
    }
    messagingState = {
      capabilities: jest.fn(),
      createLink: jest.fn(() => of({ url: 'https://t.me/helper_bot?start=y', expiresAt: '2099-01-01T00:00:00Z' })),
      load: jest.fn(() => of([[], []])),
    }
  })

  it('offers an admin to create a bot without a token when the instance has a bot manager', () => {
    const element = render({ admin: true, canCreate: true, bots: [] })

    button(element, 'Create a Telegram bot').click()
    fixture.detectChanges()

    expect(botConfig.requestCreation).toHaveBeenCalledWith('ns-1', 'agent-1', MessagingPlatform.TELEGRAM)
    const open = element.querySelector('a[href="https://t.me/agentos_manager_bot?start=x"]')
    expect(open?.textContent).toContain('Open Telegram')
  })

  it('lets an admin attach a bot from its token when there is no bot manager', () => {
    const element = render({ admin: true, canCreate: false, bots: [] })
    expect(element.textContent).not.toContain('Create a Telegram bot')

    button(element, 'Use an existing bot').click()
    fixture.detectChanges()
    const input = element.querySelector('input[type="password"]') as HTMLInputElement
    input.value = ' 4242:secret '
    input.dispatchEvent(new Event('input'))
    fixture.detectChanges()
    button(element, 'Attach').click()
    fixture.detectChanges()

    expect(botConfig.attach).toHaveBeenCalledWith(
      'ns-1',
      'agent-1',
      MessagingPlatform.TELEGRAM,
      '4242:secret',
      undefined
    )
    expect(element.textContent).toContain('@helper_bot')
  })

  it('shows the server reason when a token is refused', () => {
    botConfig.attach.mockReturnValue(throwError(() => ({ error: { message: 'Telegram rejected this token' } })))
    const element = render({ admin: true, canCreate: false, bots: [] })
    button(element, 'Use an existing bot').click()
    fixture.detectChanges()
    const input = element.querySelector('input[type="password"]') as HTMLInputElement
    input.value = 'bad'
    input.dispatchEvent(new Event('input'))
    fixture.detectChanges()

    button(element, 'Attach').click()
    fixture.detectChanges()

    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Telegram rejected this token')
  })

  it("shows the agent's bot to a member, with a way to connect their own account and nothing to change", () => {
    const element = render({ admin: false, canCreate: true, bots: [bot] })

    expect(element.textContent).toContain('@helper_bot')
    expect(element.textContent).toContain('Running')
    expect(element.textContent).not.toContain('Turn off')

    button(element, 'Connect my account').click()
    fixture.detectChanges()

    expect(messagingState.createLink).toHaveBeenCalledWith('bot-1')
    expect(element.querySelector('a[href="https://t.me/helper_bot?start=y"]')).not.toBeNull()
  })

  it('walks an admin through the Slack app: its manifest, then its two tokens', () => {
    const slackBot = { ...bot, id: 'bot-2', platform: MessagingPlatform.SLACK, username: 'helper' }
    botConfig.attach.mockReturnValue(of(slackBot))
    const element = render({ admin: true, canCreate: false, bots: [] })

    button(element, 'Connect a Slack app').click()
    fixture.detectChanges()

    expect(botConfig.manifest).toHaveBeenCalledWith('ns-1', 'agent-1', MessagingPlatform.SLACK)
    expect(element.querySelector('pre')?.textContent).toContain('"name":"Helper"')
    const attach = button(element, 'Attach')
    expect(attach.disabled).toBe(true)
    const [token, appToken] = Array.from(element.querySelectorAll('input[type="password"]')) as HTMLInputElement[]
    token.value = 'xoxb-1'
    token.dispatchEvent(new Event('input'))
    appToken.value = 'xapp-1'
    appToken.dispatchEvent(new Event('input'))
    fixture.detectChanges()
    attach.click()
    fixture.detectChanges()

    expect(botConfig.attach).toHaveBeenCalledWith('ns-1', 'agent-1', MessagingPlatform.SLACK, 'xoxb-1', 'xapp-1')
    expect(element.textContent).toContain('@helper')
  })

  it('tells a Slack user the message to send, since the link only opens the chat', () => {
    messagingState.createLink.mockReturnValue(
      of({ url: 'https://slack.com/app_redirect?app=A1', code: 'link abc', expiresAt: '2099-01-01T00:00:00Z' })
    )
    const element = render({ admin: false, canCreate: false, bots: [{ ...bot, platform: MessagingPlatform.SLACK }] })

    button(element, 'Connect my account').click()
    fixture.detectChanges()

    expect(element.querySelector('code')?.textContent).toBe('link abc')
  })

  it('lets an admin turn the bot off', () => {
    const element = render({ admin: true, canCreate: false, bots: [bot] })

    button(element, 'Turn off').click()
    fixture.detectChanges()

    expect(botConfig.setEnabled).toHaveBeenCalledWith('bot-1', false)
    expect(element.textContent).toContain('Off')
    expect(element.textContent).toContain('Turn on')
  })
})
