import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router'
import {
  AiModel,
  AiModelControllerService,
  AiProviderApiTypeEnum,
  AiProviderControllerService,
} from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { AiModelFormComponent } from './ai-model-form.component'

/** The protected members the tests drive. */
interface Internals {
  form: { valid: boolean; invalid: boolean }
  submit: () => void
}

/** Focused on the prompt cache lifetimes: loaded into the form, saved, and sent as absent when emptied. */
describe('AiModelFormComponent (prompt cache lifetimes)', () => {
  let models: { getByIdAiModel: jest.Mock; createAiModel: jest.Mock; updateAiModel: jest.Mock }
  let fixture: ComponentFixture<AiModelFormComponent>

  const stored: AiModel = {
    id: 'model-1',
    aiProviderId: 'provider-1',
    namespaceId: 'ns-1',
    apiModelName: 'policy/claude-sonnet',
    priority: 0,
    cacheTtlMinutes: 5,
    extendedCacheTtlMinutes: 60,
  }

  function render(modelId: string | null): HTMLElement {
    TestBed.configureTestingModule({
      imports: [AiModelFormComponent],
      providers: [
        { provide: AiModelControllerService, useValue: models },
        {
          provide: AiProviderControllerService,
          useValue: {
            listAiProvider: jest
              .fn()
              .mockReturnValue(of([{ id: 'provider-1', name: 'requesty', apiType: AiProviderApiTypeEnum.OpenAI }])),
          },
        },
        { provide: Router, useValue: { navigate: jest.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { params: { namespaceId: 'ns-1' }, paramMap: convertToParamMap({ modelId }) } },
        },
      ],
    })
    fixture = TestBed.createComponent(AiModelFormComponent)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function input(element: HTMLElement, id: string): HTMLInputElement {
    return element.querySelector(`#${id}`) as HTMLInputElement
  }

  function type(element: HTMLElement, id: string, value: string): void {
    const field = input(element, id)
    field.value = value
    field.dispatchEvent(new Event('input'))
    fixture.detectChanges()
  }

  const internals = (): Internals => fixture.componentInstance as unknown as Internals

  beforeEach(() => {
    models = {
      getByIdAiModel: jest.fn().mockReturnValue(of(stored)),
      createAiModel: jest.fn().mockReturnValue(of(stored)),
      updateAiModel: jest.fn().mockReturnValue(of(stored)),
    }
  })

  it('shows both lifetimes with their labels and hint in the pricing section', () => {
    const element = render('model-1')

    expect(element.querySelector('label[for="model-cache-ttl"]')?.textContent?.trim()).toBe('Cache TTL, standard (min)')
    expect(element.querySelector('label[for="model-extended-cache-ttl"]')?.textContent?.trim()).toBe(
      'Cache TTL, extended (min)'
    )
    const section = input(element, 'model-cache-ttl').closest('ds-fieldset')?.textContent
    expect(section).toContain('How long the provider keeps a prompt cached after its last use.')
    expect(section).toContain('Empty: no cache indicator or keep-alive.')
    expect(input(element, 'model-cache-ttl').value).toBe('5')
    expect(input(element, 'model-extended-cache-ttl').value).toBe('60')
  })

  it('saves the edited lifetimes', () => {
    const element = render('model-1')
    type(element, 'model-cache-ttl', '30')

    internals().submit()

    expect(models.updateAiModel).toHaveBeenCalledWith(
      'model-1',
      expect.objectContaining({ cacheTtlMinutes: 30, extendedCacheTtlMinutes: 60 })
    )
  })

  it('sends emptied lifetimes as absent: the model then has no cache TTL', () => {
    const element = render('model-1')
    type(element, 'model-cache-ttl', '')
    type(element, 'model-extended-cache-ttl', '')

    internals().submit()

    const payload = models.updateAiModel.mock.calls[0][1] as AiModel
    expect(payload.cacheTtlMinutes).toBeUndefined()
    expect(payload.extendedCacheTtlMinutes).toBeUndefined()
  })

  it('refuses a lifetime that is not a positive whole number of minutes', () => {
    const element = render('model-1')
    for (const [id, value] of [
      ['model-cache-ttl', '0'],
      ['model-cache-ttl', '2.5'],
      ['model-extended-cache-ttl', '1.5'],
    ]) {
      type(element, 'model-cache-ttl', '5')
      type(element, 'model-extended-cache-ttl', '60')
      expect(internals().form.valid).toBe(true)

      type(element, id, value)

      expect(internals().form.invalid).toBe(true)
      internals().submit()
    }
    expect(models.updateAiModel).not.toHaveBeenCalled()
  })
})
