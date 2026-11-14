import { signal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { By } from '@angular/platform-browser'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { MessagingBotDto, MessagingBotStatus, MessagingPlatform } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { MessagingStateService } from '../../services/messaging-state.service'
import { CaseContinueMessagingComponent } from './case-continue-messaging.component'

describe('CaseContinueMessagingComponent', () => {
  function bot(id: string, namespaceId: string, linked: boolean): MessagingBotDto {
    return {
      id,
      platform: MessagingPlatform.TELEGRAM,
      agentId: `agent-${id}`,
      agentName: `Agent ${id}`,
      namespaceId,
      username: `${id}_bot`,
      displayName: id,
      enabled: true,
      managed: false,
      status: MessagingBotStatus.RUNNING,
      url: `https://t.me/${id}_bot`,
      linked,
      createdAt: '2026-11-04T10:00:00Z',
    }
  }

  let fixture: ComponentFixture<CaseContinueMessagingComponent>
  let continueCase: jest.Mock

  beforeEach(() => {
    continueCase = jest.fn(() => of(undefined))
    TestBed.configureTestingModule({
      imports: [CaseContinueMessagingComponent],
      providers: [
        {
          provide: MessagingStateService,
          useValue: {
            bots: signal([bot('here', 'ns-1', true), bot('unlinked', 'ns-1', false), bot('elsewhere', 'ns-2', true)]),
            ensureLoaded: () => of(undefined),
            continueCase,
          },
        },
      ],
    })
    fixture = TestBed.createComponent(CaseContinueMessagingComponent)
    fixture.componentRef.setInput('caseId', 'case-1')
    fixture.componentRef.setInput('namespaceId', 'ns-1')
    fixture.detectChanges()
  })

  it("offers the linked bots of the case's namespace only, and continues the case with the one chosen", () => {
    const buttons = fixture.debugElement.queryAll(By.directive(IconButtonComponent))

    expect(buttons).toHaveLength(1)
    const button = buttons[0].componentInstance as IconButtonComponent
    expect(button.title).toBe('Continue on Telegram with Agent here (@here_bot)')

    button.action.emit()

    expect(continueCase).toHaveBeenCalledWith('case-1', 'here')
  })
})
