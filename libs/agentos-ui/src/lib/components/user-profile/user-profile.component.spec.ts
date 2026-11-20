import { signal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { PromptCacheTtl, User } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { AiProviderConfigStateService } from '../../services/ai-provider-config-state.service'
import { AuthSettingConfigStateService } from '../../services/auth-setting-config-state.service'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import { UserStateService } from '../../services/user-state.service'
import { UserProfileComponent } from './user-profile.component'

/** The protected members the tests drive. */
interface Internals {
  form: { controls: { promptCacheTtl: { value: PromptCacheTtl; setValue: (v: PromptCacheTtl) => void } } }
  startEditing: () => void
  save: () => void
}

/**
 * Focused on the prompt cache duration: shown in read mode, offered as a select in edit mode, and sent through
 * UserStateService.updateMe on save.
 */
describe('UserProfileComponent (prompt cache duration)', () => {
  let currentUser: ReturnType<typeof signal<User | null>>
  let userState: { currentUser: typeof currentUser; loadMe: jest.Mock; updateMe: jest.Mock }
  let fixture: ComponentFixture<UserProfileComponent>
  let component: UserProfileComponent

  const internals = (): Internals => component as unknown as Internals

  function user(overrides: Partial<User> = {}): User {
    return { id: 'u-1', email: 'alice@example.com', firstname: 'Alice', isAdmin: false, ...overrides }
  }

  function render(): HTMLElement {
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  beforeEach(() => {
    currentUser = signal<User | null>(user())
    userState = {
      currentUser,
      loadMe: jest.fn().mockReturnValue(of(user())),
      updateMe: jest.fn().mockImplementation((patch: Partial<User>) => {
        const updated = { ...currentUser(), ...patch } as User
        currentUser.set(updated)
        return of(updated)
      }),
    }
    const noUserGlobal = { userGlobal$: of([]), delete: jest.fn().mockReturnValue(of(undefined)) }

    TestBed.configureTestingModule({
      imports: [UserProfileComponent],
      providers: [
        { provide: UserStateService, useValue: userState },
        { provide: IntegrationConfigStateService, useValue: noUserGlobal },
        { provide: AiProviderConfigStateService, useValue: noUserGlobal },
        { provide: AuthSettingConfigStateService, useValue: noUserGlobal },
      ],
    })

    fixture = TestBed.createComponent(UserProfileComponent)
    component = fixture.componentInstance
  })

  it('shows the default duration in read mode for a user who never chose one', () => {
    const element = render()

    expect(element.textContent).toContain('Prompt cache duration (Claude models via Requesty)')
    expect(element.textContent).toContain('5 minutes (default)')
  })

  it('shows the stored duration in read mode', () => {
    currentUser.set(user({ promptCacheTtl: PromptCacheTtl.ONE_HOUR }))

    const element = render()

    expect(element.textContent).toContain('1 hour')
    expect(element.textContent).not.toContain('5 minutes (default)')
  })

  it('offers both durations with a hint in edit mode, preselecting the stored one', () => {
    currentUser.set(user({ promptCacheTtl: PromptCacheTtl.ONE_HOUR }))
    render()
    internals().startEditing()

    const element = render()
    const select = element.querySelector('#up-promptCacheTtl') as HTMLSelectElement

    expect(Array.from(select.options).map((option) => option.textContent?.trim())).toEqual([
      '5 minutes (default)',
      '1 hour',
    ])
    expect(select.value).toBe(PromptCacheTtl.ONE_HOUR)
    expect(element.textContent).toContain(
      '1 hour costs more per cache write but keeps the conversation cached during longer pauses.'
    )
  })

  it('preselects the default when the user never chose a duration', () => {
    render()
    internals().startEditing()

    expect(internals().form.controls.promptCacheTtl.value).toBe(PromptCacheTtl.FIVE_MINUTES)
  })

  it('sends the chosen duration through updateMe on save', () => {
    render()
    internals().startEditing()
    internals().form.controls.promptCacheTtl.setValue(PromptCacheTtl.ONE_HOUR)

    internals().save()

    expect(userState.updateMe).toHaveBeenCalledWith(
      expect.objectContaining({ firstname: 'Alice', promptCacheTtl: PromptCacheTtl.ONE_HOUR })
    )
    expect(render().textContent).toContain('1 hour')
  })

  it('sends the default back when switching from one hour to five minutes', () => {
    currentUser.set(user({ promptCacheTtl: PromptCacheTtl.ONE_HOUR }))
    render()
    internals().startEditing()
    internals().form.controls.promptCacheTtl.setValue(PromptCacheTtl.FIVE_MINUTES)

    internals().save()

    expect(userState.updateMe).toHaveBeenCalledWith(
      expect.objectContaining({ promptCacheTtl: PromptCacheTtl.FIVE_MINUTES })
    )
  })
})
