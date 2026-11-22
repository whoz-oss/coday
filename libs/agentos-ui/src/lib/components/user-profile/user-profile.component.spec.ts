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
 * Focused on the prompt cache choice: shown in read mode, offered as a select in edit mode, and sent through
 * UserStateService.updateMe on save.
 */
describe('UserProfileComponent (prompt cache)', () => {
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

  it('shows Standard in read mode for a user who never chose', () => {
    const element = render()

    expect(element.textContent).toContain('Prompt cache')
    expect(element.textContent).toContain('Standard')
  })

  it('shows the stored choice in read mode', () => {
    currentUser.set(user({ promptCacheTtl: PromptCacheTtl.EXTENDED }))

    const element = render()

    expect(element.textContent).toContain('Extended')
    expect(element.textContent).not.toContain('Standard')
  })

  it('offers both choices with a hint in edit mode, preselecting the stored one', () => {
    currentUser.set(user({ promptCacheTtl: PromptCacheTtl.EXTENDED }))
    render()
    internals().startEditing()

    const element = render()
    const select = element.querySelector('#up-promptCacheTtl') as HTMLSelectElement

    expect(Array.from(select.options).map((option) => option.textContent?.trim())).toEqual([
      'Standard',
      'Extended',
    ])
    expect(select.value).toBe(PromptCacheTtl.EXTENDED)
    expect(element.textContent).toContain(
      "Extended keeps the conversation cached through longer pauses (Claude models via Requesty: 1 hour instead of 5 minutes; each cache write costs more). Other models keep their provider's default."
    )
  })

  it('preselects Standard when the user never chose', () => {
    render()
    internals().startEditing()

    expect(internals().form.controls.promptCacheTtl.value).toBe(PromptCacheTtl.STANDARD)
  })

  it('sends the chosen value through updateMe on save', () => {
    render()
    internals().startEditing()
    internals().form.controls.promptCacheTtl.setValue(PromptCacheTtl.EXTENDED)

    internals().save()

    expect(userState.updateMe).toHaveBeenCalledWith(
      expect.objectContaining({ firstname: 'Alice', promptCacheTtl: PromptCacheTtl.EXTENDED })
    )
    expect(render().textContent).toContain('Extended')
  })

  it('sends Standard back when switching from Extended', () => {
    currentUser.set(user({ promptCacheTtl: PromptCacheTtl.EXTENDED }))
    render()
    internals().startEditing()
    internals().form.controls.promptCacheTtl.setValue(PromptCacheTtl.STANDARD)

    internals().save()

    expect(userState.updateMe).toHaveBeenCalledWith(
      expect.objectContaining({ promptCacheTtl: PromptCacheTtl.STANDARD })
    )
  })
})
