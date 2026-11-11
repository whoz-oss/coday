import { signal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import {
  Namespace,
  NamespaceControllerService,
  UsageConfigurationControllerService,
} from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { UserStateService } from '../../services/user-state.service'
import { NamespaceFormComponent } from './namespace-form.component'

/**
 * Also covers the inherited run cost threshold: the platform default is only displayed (placeholder and
 * hint), never written to the namespace, so an empty field is still sent as absent.
 *
 * Focused on `maxToolRounds`: empty inherits the server default, a whole number >= 1 overrides it, and,
 * because a PUT that omits the field clears an existing override, an edit of another property must send
 * the loaded value back.
 *
 * The component class is driven directly (ngOnInit / submit); the template is rendered only for the
 * hint, the validation message and the submit button.
 */
describe('NamespaceFormComponent (maximum tool rounds)', () => {
  let controller: { getByIdNamespace: jest.Mock; createNamespace: jest.Mock; updateNamespace: jest.Mock }
  let router: { navigate: jest.Mock }
  let usageConfiguration: { getUsageConfiguration: jest.Mock }
  let routeNamespaceId: string | null
  let currentUser: ReturnType<typeof signal<{ isAdmin?: boolean } | null>>
  let fixture: ComponentFixture<NamespaceFormComponent>
  let component: NamespaceFormComponent

  const internals = () =>
    component as unknown as {
      nameControl: { setValue: (v: string) => void }
      maxToolRoundsControl: {
        setValue: (v: number | null) => void
        value: number | null
        invalid: boolean
        markAsTouched: () => void
      }
      form: { invalid: boolean }
      submit: () => void
      onMaxToolRoundsInput: (event: Event) => void
      maxToolRoundsUnparsable: () => boolean
    }

  function existingNamespace(overrides: Partial<Namespace> = {}): Namespace {
    return { id: 'ns-1', name: 'engineering', ...overrides }
  }

  /** Opens the form on an existing namespace (edit mode) and returns the component ready to submit. */
  function editing(overrides: Partial<Namespace> = {}): void {
    routeNamespaceId = 'ns-1'
    controller.getByIdNamespace.mockReturnValue(of(existingNamespace(overrides)))
    component.ngOnInit()
  }

  beforeEach(() => {
    routeNamespaceId = null
    // A plain member by default: the shared files folder is only editable by a super-admin.
    currentUser = signal<{ isAdmin?: boolean } | null>({ isAdmin: false })
    controller = {
      getByIdNamespace: jest.fn(),
      createNamespace: jest.fn().mockReturnValue(of({})),
      updateNamespace: jest.fn().mockReturnValue(of({})),
    }
    router = { navigate: jest.fn() }
    usageConfiguration = {
      getUsageConfiguration: jest.fn().mockReturnValue(of({ enabled: true, runCostThreshold: 2 })),
    }

    TestBed.configureTestingModule({
      imports: [NamespaceFormComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: (key: string) => (key === 'namespaceId' ? routeNamespaceId : null) } },
            parent: null,
          },
        },
        { provide: Router, useValue: router },
        { provide: NamespaceControllerService, useValue: controller },
        { provide: UsageConfigurationControllerService, useValue: usageConfiguration },
        { provide: UserStateService, useValue: { currentUser, loadMe: jest.fn().mockReturnValue(of({})) } },
      ],
    })

    fixture = TestBed.createComponent(NamespaceFormComponent)
    component = fixture.componentInstance
  })

  describe('hydration', () => {
    it('shows the stored override in the field', () => {
      editing({ maxToolRounds: 250 })

      expect(internals().maxToolRoundsControl.value).toBe(250)
    })

    it('leaves the field empty when the namespace inherits the server default', () => {
      editing()

      expect(internals().maxToolRoundsControl.value).toBeNull()
    })
  })

  describe('submit payload', () => {
    it('sends the value typed on create', () => {
      component.ngOnInit()
      internals().nameControl.setValue('new-namespace')
      internals().maxToolRoundsControl.setValue(42)

      internals().submit()

      expect(controller.createNamespace.mock.calls[0][0].maxToolRounds).toBe(42)
      expect(controller.updateNamespace).not.toHaveBeenCalled()
      expect(router.navigate).toHaveBeenCalled()
    })

    it('sends no value on create when the field is left empty', () => {
      component.ngOnInit()
      internals().nameControl.setValue('new-namespace')

      internals().submit()

      expect(controller.createNamespace.mock.calls[0][0].maxToolRounds).toBeUndefined()
    })

    it('sends the new value on update', () => {
      editing({ maxToolRounds: 250 })
      internals().maxToolRoundsControl.setValue(500)

      internals().submit()

      expect(controller.updateNamespace).toHaveBeenCalledWith('ns-1', expect.objectContaining({ maxToolRounds: 500 }))
    })

    it('preserves a stored override when another field is edited', () => {
      // The regression this guards: a PUT without the field clears the override, so an unrelated
      // edit must send the loaded value back.
      editing({ maxToolRounds: 250 })
      internals().nameControl.setValue('renamed')

      internals().submit()

      const payload = controller.updateNamespace.mock.calls[0][1]
      expect(payload.name).toBe('renamed')
      expect(payload.maxToolRounds).toBe(250)
    })

    it('sends the field as absent when the user clears the override, which makes the backend inherit again', () => {
      editing({ maxToolRounds: 250 })
      internals().maxToolRoundsControl.setValue(null)

      internals().submit()

      const payload = controller.updateNamespace.mock.calls[0][1]
      expect(payload.maxToolRounds).toBeUndefined()
      expect(JSON.parse(JSON.stringify(payload))).not.toHaveProperty('maxToolRounds')
    })

    it('keeps a namespace that inherits the default inheriting when saved untouched', () => {
      editing()

      internals().submit()

      expect(controller.updateNamespace.mock.calls[0][1].maxToolRounds).toBeUndefined()
    })
  })

  describe('validation', () => {
    it.each([0, -1, 1.5, 2147483648])('rejects %s and does not submit', (value) => {
      component.ngOnInit()
      internals().nameControl.setValue('new-namespace')
      internals().maxToolRoundsControl.setValue(value)

      internals().submit()

      expect(internals().maxToolRoundsControl.invalid).toBe(true)
      expect(controller.createNamespace).not.toHaveBeenCalled()
    })

    it('refuses an entry the number input cannot parse instead of clearing the override', () => {
      // type="number" hands Angular an empty value for "1O0" or "--50", and the control stores null,
      // which this form reads as "inherit": without the badInput check the override would be wiped.
      editing({ maxToolRounds: 250 })
      internals().maxToolRoundsControl.setValue(null)
      internals().onMaxToolRoundsInput({ target: { validity: { badInput: true } } } as unknown as Event)

      internals().submit()

      expect(internals().maxToolRoundsUnparsable()).toBe(true)
      expect(controller.updateNamespace).not.toHaveBeenCalled()
    })

    it('still treats a deliberately emptied field as inherit', () => {
      editing({ maxToolRounds: 250 })
      internals().maxToolRoundsControl.setValue(null)
      internals().onMaxToolRoundsInput({ target: { validity: { badInput: false } } } as unknown as Event)

      internals().submit()

      expect(controller.updateNamespace.mock.calls[0][1].maxToolRounds).toBeUndefined()
    })

    it.each([1, 20, 1000, 2147483647])('accepts %s', (value) => {
      component.ngOnInit()
      internals().nameControl.setValue('new-namespace')
      internals().maxToolRoundsControl.setValue(value)

      internals().submit()

      expect(internals().maxToolRoundsControl.invalid).toBe(false)
      expect(controller.createNamespace.mock.calls[0][0].maxToolRounds).toBe(value)
    })

    it('treats an empty field as valid, since it means inherit', () => {
      component.ngOnInit()
      internals().nameControl.setValue('new-namespace')
      internals().maxToolRoundsControl.setValue(null)

      expect(internals().maxToolRoundsControl.invalid).toBe(false)
      expect(internals().form.invalid).toBe(false)
    })
  })

  describe('run cost threshold default', () => {
    const thresholdInput = (): HTMLInputElement =>
      (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#ns-run-cost-threshold')!
    const thresholdHint = (): string =>
      (fixture.nativeElement as HTMLElement).querySelector('#ns-run-cost-threshold-hint')?.textContent?.trim() ?? ''

    function renderWith(configuration: { enabled: boolean; runCostThreshold?: number }): void {
      usageConfiguration.getUsageConfiguration.mockReturnValue(of(configuration))
      // The shared service reads the setting once, at injection: build a fresh one for this configuration.
      TestBed.resetTestingModule()
      TestBed.configureTestingModule({
        imports: [NamespaceFormComponent],
        providers: [
          {
            provide: ActivatedRoute,
            useValue: {
              snapshot: { paramMap: { get: (key: string) => (key === 'namespaceId' ? routeNamespaceId : null) } },
              parent: null,
            },
          },
          { provide: Router, useValue: router },
          { provide: NamespaceControllerService, useValue: controller },
          { provide: UsageConfigurationControllerService, useValue: usageConfiguration },
          { provide: UserStateService, useValue: { currentUser, loadMe: jest.fn().mockReturnValue(of({})) } },
        ],
      })
      fixture = TestBed.createComponent(NamespaceFormComponent)
      component = fixture.componentInstance
      fixture.detectChanges()
    }

    it('shows the platform default in the placeholder and the hint while usage tracking is enabled', () => {
      renderWith({ enabled: true, runCostThreshold: 2 })

      expect(thresholdInput().placeholder).toBe('Platform default: 2')
      expect(thresholdHint()).toContain('inherit the platform default (2)')
      expect(thresholdInput().getAttribute('aria-describedby')).toBe('ns-run-cost-threshold-hint')
    })

    it('says there is no platform limit when the platform defines none', () => {
      renderWith({ enabled: true })

      expect(thresholdInput().placeholder).toBe('No platform limit')
      expect(thresholdHint()).toContain('no limit')
    })

    it('explains that cost limits are inactive while usage tracking is disabled', () => {
      renderWith({ enabled: false, runCostThreshold: 2 })

      expect(thresholdInput().placeholder).not.toContain('Platform default')
      expect(thresholdHint()).toContain('inactive')
      expect(thresholdHint()).toContain('usage tracking is disabled')
    })

    it('never writes the platform default into a namespace that inherits it', () => {
      routeNamespaceId = 'ns-1'
      controller.getByIdNamespace.mockReturnValue(of(existingNamespace()))
      renderWith({ enabled: true, runCostThreshold: 2 })

      internals().submit()

      const payload = controller.updateNamespace.mock.calls[0][1]
      expect(payload.runCostThreshold).toBeUndefined()
      expect(JSON.parse(JSON.stringify(payload))).not.toHaveProperty('runCostThreshold')
    })

    it('keeps a threshold the namespace sets itself, whatever the platform default is', () => {
      routeNamespaceId = 'ns-1'
      controller.getByIdNamespace.mockReturnValue(of(existingNamespace({ runCostThreshold: 5 })))
      renderWith({ enabled: true, runCostThreshold: 2 })

      internals().submit()

      expect(thresholdInput().value).toBe('5')
      expect(controller.updateNamespace).toHaveBeenCalledWith('ns-1', expect.objectContaining({ runCostThreshold: 5 }))
    })
  })

  describe('shared files folder', () => {
    const pathControl = () =>
      (
        component as unknown as {
          sharedExchangePathControl: { value: string; invalid: boolean; setValue: (v: string) => void }
        }
      ).sharedExchangePathControl
    const pathInput = (): HTMLInputElement =>
      (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#ns-shared-exchange-path')!
    const pathHint = (): string =>
      (fixture.nativeElement as HTMLElement).querySelector('#ns-shared-exchange-path-hint')?.textContent?.trim() ?? ''
    const sentPayload = () => controller.updateNamespace.mock.calls[0][1]

    describe('as a super-admin', () => {
      beforeEach(() => currentUser.set({ isAdmin: true }))

      it('shows the stored folder in the field', () => {
        editing({ sharedExchangePath: '/home/me/Coday' })

        expect(pathControl().value).toBe('/home/me/Coday')
      })

      it('sends the new folder, trimmed', () => {
        editing({ sharedExchangePath: '/home/me/Coday' })
        pathControl().setValue('  /data/shared  ')

        internals().submit()

        expect(controller.updateNamespace).toHaveBeenCalledWith(
          'ns-1',
          expect.objectContaining({ sharedExchangePath: '/data/shared' })
        )
      })

      it('sends the stored folder back when another field is edited', () => {
        editing({ sharedExchangePath: '/home/me/Coday' })
        internals().nameControl.setValue('renamed')

        internals().submit()

        expect(sentPayload().sharedExchangePath).toBe('/home/me/Coday')
      })

      it('clears a stored folder with a blank value, since an omitted one would keep it', () => {
        editing({ sharedExchangePath: '/home/me/Coday' })
        pathControl().setValue('   ')

        internals().submit()

        expect(sentPayload().sharedExchangePath).toBe('')
      })

      it('sends nothing when there is no folder and none is typed', () => {
        editing()

        internals().submit()

        expect(sentPayload().sharedExchangePath).toBeUndefined()
      })

      it('sends the folder typed on create', () => {
        component.ngOnInit()
        internals().nameControl.setValue('new-namespace')
        pathControl().setValue('/data/shared')

        internals().submit()

        expect(controller.createNamespace.mock.calls[0][0].sharedExchangePath).toBe('/data/shared')
      })

      it.each(['Coday', 'relative/folder', '../escape', '~/Coday'])('rejects the relative path %s', (value) => {
        component.ngOnInit()
        internals().nameControl.setValue('new-namespace')
        pathControl().setValue(value)

        internals().submit()

        expect(pathControl().invalid).toBe(true)
        expect(controller.createNamespace).not.toHaveBeenCalled()
      })

      it.each(['/home/me/Coday', '/', 'C:\\Users\\me\\Coday', 'C:/Users/me/Coday', '\\\\server\\share\\Coday'])(
        'accepts the absolute path %s',
        (value) => {
          component.ngOnInit()
          internals().nameControl.setValue('new-namespace')
          pathControl().setValue(value)

          internals().submit()

          expect(pathControl().invalid).toBe(false)
          expect(controller.createNamespace).toHaveBeenCalled()
        }
      )

      it('renders an editable field and explains an invalid value', () => {
        fixture.detectChanges()
        pathInput().value = 'Coday'
        pathInput().dispatchEvent(new Event('input'))
        pathInput().dispatchEvent(new Event('blur'))
        fixture.detectChanges()

        expect(pathInput().readOnly).toBe(false)
        expect(pathInput().getAttribute('aria-invalid')).toBe('true')
        expect(
          (fixture.nativeElement as HTMLElement).querySelector('#ns-shared-exchange-path-error')?.textContent
        ).toContain('absolute path')
        expect(
          ((fixture.nativeElement as HTMLElement).querySelector('button[type="submit"]') as HTMLButtonElement).disabled
        ).toBe(true)
      })
    })

    describe('as anyone else', () => {
      it('shows the stored folder, read-only, with the reason', () => {
        routeNamespaceId = 'ns-1'
        controller.getByIdNamespace.mockReturnValue(of(existingNamespace({ sharedExchangePath: '/home/me/Coday' })))
        fixture.detectChanges()

        expect(pathInput().value).toBe('/home/me/Coday')
        expect(pathInput().readOnly).toBe(true)
        expect(pathHint()).toContain('Only a super-admin can change this folder.')
      })

      it('sends the stored folder back untouched, so the server never answers 403', () => {
        editing({ sharedExchangePath: '/home/me/Coday' })
        // Even a value forced into the control (the input is read-only) never reaches the payload.
        pathControl().setValue('/somewhere/else')
        internals().nameControl.setValue('renamed')

        internals().submit()

        expect(sentPayload().name).toBe('renamed')
        expect(sentPayload().sharedExchangePath).toBe('/home/me/Coday')
      })

      it('sends no folder when the namespace has none', () => {
        editing()

        internals().submit()

        expect(sentPayload().sharedExchangePath).toBeUndefined()
      })

      it('does not block the save over a stored value it cannot edit', () => {
        editing({ sharedExchangePath: 'legacy/relative' })

        internals().submit()

        expect(controller.updateNamespace).toHaveBeenCalledWith(
          'ns-1',
          expect.objectContaining({ sharedExchangePath: 'legacy/relative' })
        )
      })

      it('treats a user not loaded yet as a non-admin', () => {
        currentUser.set(null)
        fixture.detectChanges()

        expect(pathInput().readOnly).toBe(true)
      })
    })
  })

  describe('admins can list all cases', () => {
    const select = (): HTMLSelectElement =>
      (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#ns-admin-list-all-cases')!
    const choice = () =>
      (component as unknown as { adminCanListAllCasesControl: { value: string; setValue: (v: string) => void } })
        .adminCanListAllCasesControl
    const sent = () => controller.updateNamespace.mock.calls[0][1]

    it.each([
      [undefined, 'inherit'],
      [true, 'yes'],
      [false, 'no'],
    ])('shows a stored %s as %s', (stored, expected) => {
      editing({ adminCanListAllCases: stored })

      expect(choice().value).toBe(expected)
    })

    it('renders the three choices, inherit first', () => {
      fixture.detectChanges()

      const options = Array.from(select().options).map((option) => [option.value, option.textContent?.trim()])
      expect(options).toEqual([
        ['inherit', 'Inherit platform default'],
        ['yes', 'Yes'],
        ['no', 'No'],
      ])
      expect(select().value).toBe('inherit')
    })

    it('sends true for Yes', () => {
      editing()
      choice().setValue('yes')

      internals().submit()

      expect(sent().adminCanListAllCases).toBe(true)
    })

    it('sends false for No, which must not be mistaken for inherit', () => {
      editing()
      choice().setValue('no')

      internals().submit()

      expect(sent().adminCanListAllCases).toBe(false)
      expect(JSON.parse(JSON.stringify(sent()))).toHaveProperty('adminCanListAllCases', false)
    })

    it('sends the field as absent for inherit, which clears an existing override', () => {
      editing({ adminCanListAllCases: true })
      choice().setValue('inherit')

      internals().submit()

      expect(sent().adminCanListAllCases).toBeUndefined()
      expect(JSON.parse(JSON.stringify(sent()))).not.toHaveProperty('adminCanListAllCases')
    })

    it('keeps a stored override when another field is edited', () => {
      editing({ adminCanListAllCases: false })
      internals().nameControl.setValue('renamed')

      internals().submit()

      expect(sent().name).toBe('renamed')
      expect(sent().adminCanListAllCases).toBe(false)
    })

    it('sends the choice made on create', () => {
      component.ngOnInit()
      internals().nameControl.setValue('new-namespace')
      choice().setValue('yes')

      internals().submit()

      expect(controller.createNamespace.mock.calls[0][0].adminCanListAllCases).toBe(true)
    })

    it('leaves a new namespace inheriting by default', () => {
      component.ngOnInit()
      internals().nameControl.setValue('new-namespace')

      internals().submit()

      expect(controller.createNamespace.mock.calls[0][0].adminCanListAllCases).toBeUndefined()
    })

    it('routes a pick in the select to the control', () => {
      fixture.detectChanges()
      select().value = 'no'
      select().dispatchEvent(new Event('change'))

      expect(choice().value).toBe('no')
    })
  })

  describe('template', () => {
    const input = (): HTMLInputElement => {
      const el = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#ns-max-tool-rounds')
      expect(el).not.toBeNull()
      return el!
    }
    const submitButton = (): HTMLButtonElement =>
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!

    it('renders a labelled number field with the inherit and next-run hint', () => {
      fixture.detectChanges()

      const element = fixture.nativeElement as HTMLElement
      expect(element.querySelector('label[for="ns-max-tool-rounds"]')?.textContent).toContain('Maximum tool rounds')
      expect(input().type).toBe('number')
      expect(input().min).toBe('1')
      expect(input().placeholder).toBe('Server default')
      const hint = element.querySelector('#ns-max-tool-rounds-hint')?.textContent ?? ''
      expect(hint).toContain('inherit the server default')
      expect(hint).toContain('next agent run')
    })

    it('renders the stored override', () => {
      routeNamespaceId = 'ns-1'
      controller.getByIdNamespace.mockReturnValue(of(existingNamespace({ maxToolRounds: 250 })))
      fixture.detectChanges()

      expect(input().value).toBe('250')
    })

    it('routes typing in the field to the control', () => {
      fixture.detectChanges()
      input().value = '75'
      input().dispatchEvent(new Event('input'))

      expect(internals().maxToolRoundsControl.value).toBe(75)
    })

    it('shows no error while the field is untouched', () => {
      fixture.detectChanges()

      expect((fixture.nativeElement as HTMLElement).querySelector('#ns-max-tool-rounds-error')).toBeNull()
    })

    it('explains an invalid value and disables the submit button', () => {
      fixture.detectChanges()
      internals().nameControl.setValue('new-namespace')
      input().value = '0'
      input().dispatchEvent(new Event('input'))
      input().dispatchEvent(new Event('blur'))
      fixture.detectChanges()

      const error = (fixture.nativeElement as HTMLElement).querySelector('#ns-max-tool-rounds-error')
      expect(error?.textContent).toContain('at least 1')
      expect(input().getAttribute('aria-invalid')).toBe('true')
      expect(submitButton().disabled).toBe(true)
    })

    it('keeps the submit button enabled for a valid value', () => {
      fixture.detectChanges()
      internals().nameControl.setValue('new-namespace')
      input().value = '100'
      input().dispatchEvent(new Event('input'))
      fixture.detectChanges()

      expect(submitButton().disabled).toBe(false)
    })
  })
})
