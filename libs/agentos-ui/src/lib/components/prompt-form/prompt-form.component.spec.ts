import { ComponentFixture, TestBed } from '@angular/core/testing'
import { FormControl } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfigControllerService } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { PromptStateService } from '../../services/prompt-state.service'
import { PromptFormComponent } from './prompt-form.component'

/**
 * Parameters section of the prompt form: a parameter's "Default value" is mandatory (the validator and the
 * server's @NotBlank both say so), and the label and the error message have to say it too.
 *
 * All the state is prepared before the first detectChanges: the component is OnPush and the form state
 * (touched, validity) is not a signal, so a later detectChanges would not refresh the view.
 */
describe('PromptFormComponent (parameters)', () => {
  let fixture: ComponentFixture<PromptFormComponent>
  let component: PromptFormComponent

  type ParameterGroup = { controls: { name: FormControl<string>; defaultValue: FormControl<string> } }
  const internals = () =>
    component as unknown as {
      addParameter: () => void
      parametersArray: { at: (i: number) => ParameterGroup }
      form: { invalid: boolean }
    }
  const root = () => fixture.nativeElement as HTMLElement

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PromptFormComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { params: { namespaceId: 'ns-1' }, paramMap: { get: () => null } } },
        },
        { provide: Router, useValue: { navigate: jest.fn() } },
        { provide: PromptStateService, useValue: {} },
        { provide: AgentConfigControllerService, useValue: { listByParentAgentConfig: () => of([]) } },
      ],
    })
    fixture = TestBed.createComponent(PromptFormComponent)
    component = fixture.componentInstance
  })

  it('flags the parameter "Default value" as required, like the name', () => {
    internals().addParameter()
    fixture.detectChanges()

    const label = root().querySelector('label[for="param-default-0"]')
    expect(label?.textContent).toContain('Default value')
    expect(label?.textContent).toContain('*')
  })

  it('shows "Default value is required." once an empty default value was touched', () => {
    internals().addParameter()
    internals().parametersArray.at(0).controls.defaultValue.markAsTouched()
    fixture.detectChanges()

    expect(root().textContent).toContain('Default value is required.')
  })

  it('shows no error on an untouched default value', () => {
    internals().addParameter()
    fixture.detectChanges()

    expect(root().textContent).not.toContain('Default value is required.')
  })

  it('keeps the form invalid until the default value is filled', () => {
    internals().addParameter()
    const group = internals().parametersArray.at(0)
    group.controls.name.setValue('language')
    group.controls.defaultValue.setValue('')
    expect(group.controls.defaultValue.hasError('required')).toBe(true)
    expect(internals().form.invalid).toBe(true)

    group.controls.defaultValue.setValue('English')
    expect(group.controls.defaultValue.valid).toBe(true)
  })
})
