import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { Namespace, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { NamespaceFormComponent } from './namespace-form.component'

/**
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
  let routeNamespaceId: string | null
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
    controller = {
      getByIdNamespace: jest.fn(),
      createNamespace: jest.fn().mockReturnValue(of({})),
      updateNamespace: jest.fn().mockReturnValue(of({})),
    }
    router = { navigate: jest.fn() }

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
