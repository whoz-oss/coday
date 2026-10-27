import { HttpErrorResponse } from '@angular/common/http'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ConfigChangeControllerService, ConfigChangeViewDto } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { ConfigChangeCardComponent } from './config-change-card.component'

describe('ConfigChangeCardComponent', () => {
  let service: { viewConfigChange: jest.Mock; applyConfigChange: jest.Mock; rejectConfigChange: jest.Mock }
  let fixture: ComponentFixture<ConfigChangeCardComponent>

  const plan = (overrides: Partial<ConfigChangeViewDto> = {}): ConfigChangeViewDto =>
    ({
      questionId: 'q-1',
      caseId: 'case-1',
      recipientUserId: 'u-1',
      agentName: 'config',
      summary: 'Plug Acme',
      planHash: 'sha256:abc',
      createdAt: '2026-10-26T10:00:00Z',
      expiresAt: '2026-10-27T10:00:00Z',
      state: 'OPEN',
      canApply: true,
      warnings: [],
      operations: [
        {
          ref: 'key',
          order: 1,
          kind: 'AUTH_SETTING',
          action: 'CREATE',
          name: 'ACME_KEY',
          affects: 'CALLER_ONLY',
          value: { name: 'ACME_KEY', authType: 'API_KEY' },
          changes: [],
          secretInputs: [{ key: 'key./apiKey', path: '/apiKey', label: 'Acme API key' }],
          status: 'PENDING',
        },
        {
          ref: 'dev',
          order: 2,
          kind: 'AGENT',
          action: 'UPDATE',
          name: 'dev',
          affects: 'NAMESPACE_MEMBERS',
          changes: [{ path: '/instructions', before: 'old', after: 'new', remove: false }],
          secretInputs: [],
          status: 'PENDING',
        },
      ],
      ...overrides,
    }) as ConfigChangeViewDto

  function render(view: ConfigChangeViewDto): HTMLElement {
    service.viewConfigChange.mockReturnValue(of(view))
    fixture = TestBed.createComponent(ConfigChangeCardComponent)
    fixture.componentRef.setInput('caseId', 'case-1')
    fixture.componentRef.setInput('questionId', 'q-1')
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function button(root: HTMLElement, label: string): HTMLButtonElement {
    const found = Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === label)
    if (!found) throw new Error(`No button '${label}'`)
    return found as HTMLButtonElement
  }

  beforeEach(() => {
    service = { viewConfigChange: jest.fn(), applyConfigChange: jest.fn(), rejectConfigChange: jest.fn() }
    TestBed.configureTestingModule({
      imports: [ConfigChangeCardComponent],
      providers: [{ provide: ConfigChangeControllerService, useValue: service }],
    })
  })

  it('shows each operation with its diff and keeps Apply disabled until the secrets are typed', () => {
    const root = render(plan())

    expect(root.textContent).toContain('create auth setting ACME_KEY')
    expect(root.textContent).toContain('/instructions')
    expect(button(root, 'Apply').disabled).toBe(true)

    const secret = root.querySelector('input[type="password"]') as HTMLInputElement
    secret.value = 's3cr3t'
    secret.dispatchEvent(new Event('input'))
    fixture.detectChanges()

    expect(button(root, 'Apply').disabled).toBe(false)
  })

  it('applies the displayed plan with its hash and the typed secrets', () => {
    service.applyConfigChange.mockReturnValue(of({ decision: 'APPLIED', appliedBy: 'u-1', results: [] }))
    const root = render(plan())
    const secret = root.querySelector('input[type="password"]') as HTMLInputElement
    secret.value = 's3cr3t'
    secret.dispatchEvent(new Event('input'))
    fixture.detectChanges()

    button(root, 'Apply').click()

    expect(service.applyConfigChange).toHaveBeenCalledWith('case-1', 'q-1', {
      expectedPlanHash: 'sha256:abc',
      secrets: { 'key./apiKey': 's3cr3t' },
    })
  })

  it('keeps Apply and Reject disabled while the agent turn is still running', () => {
    service.viewConfigChange.mockReturnValue(of(plan({ operations: [plan().operations[1]] })))
    fixture = TestBed.createComponent(ConfigChangeCardComponent)
    fixture.componentRef.setInput('caseId', 'case-1')
    fixture.componentRef.setInput('questionId', 'q-1')
    fixture.componentRef.setInput('disabled', true)
    fixture.detectChanges()
    const root = fixture.nativeElement as HTMLElement

    expect(button(root, 'Apply').disabled).toBe(true)
    expect(button(root, 'Reject').disabled).toBe(true)
  })

  it('offers no action to a viewer the plan was not proposed to', () => {
    const root = render(plan({ canApply: false }))

    expect(root.querySelectorAll('button').length).toBe(0)
    expect(root.textContent).toContain('Only the person this plan was proposed to')
  })

  it('rejects with an optional comment', () => {
    service.rejectConfigChange.mockReturnValue(of(undefined))
    const root = render(plan())

    button(root, 'Reject').click()
    fixture.detectChanges()
    const comment = root.querySelector('textarea') as HTMLTextAreaElement
    comment.value = 'Not now'
    comment.dispatchEvent(new Event('input'))
    button(root, 'Reject the plan').click()

    expect(service.rejectConfigChange).toHaveBeenCalledWith('case-1', 'q-1', { comment: 'Not now' })
  })

  it('explains a conflict and reloads the plan', () => {
    service.applyConfigChange.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409 })))
    const root = render(plan({ operations: [plan().operations[1]] }))

    button(root, 'Apply').click()
    fixture.detectChanges()

    expect(root.textContent).toContain('can no longer be applied as displayed')
    expect(service.viewConfigChange).toHaveBeenCalledTimes(2)
  })
})
