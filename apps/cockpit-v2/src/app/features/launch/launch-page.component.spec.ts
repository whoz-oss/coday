import { ComponentFixture, TestBed } from '@angular/core/testing'
import { FormGroup } from '@angular/forms'
import { provideRouter, Router } from '@angular/router'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService } from '../../core/factory-api.service'
import { FactoryStore } from '../../core/factory.store'
import { LaunchPageComponent } from './launch-page.component'

interface ApiStub {
  getWorkflowDefinitions: jest.Mock
  getNamespaces: jest.Mock
  createWorkflowRun: jest.Mock
}

function api(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    getWorkflowDefinitions: jest.fn().mockReturnValue(of({ items: [{ workflowType: 'delivery' }] })),
    getNamespaces: jest.fn().mockReturnValue(of([{ namespaceId: 'ns-1' }, { id: 'ns-2' }])),
    createWorkflowRun: jest.fn().mockReturnValue(
      of({
        workflowId: 'generated-id',
        title: 'Readable run',
        created: true,
        queued: true,
        idempotent: false,
        submissionId: 'sub-1',
        submissionStatus: 'pending',
        status: 'pending',
        revision: 1,
      })
    ),
    ...overrides,
  }
}

describe('LaunchPageComponent create-run contract', () => {
  let fixture: ComponentFixture<LaunchPageComponent>

  async function setup(service: ApiStub): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [LaunchPageComponent],
      providers: [
        provideRouter([]),
        { provide: FactoryApiService, useValue: service },
        { provide: FactoryStore, useValue: { refresh: jest.fn() } },
      ],
    }).compileComponents()
    fixture = TestBed.createComponent(LaunchPageComponent)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function form(): FormGroup {
    return (fixture.componentInstance as unknown as { form: FormGroup }).form
  }

  it('requires a namespace and exposes the canonical business fields', async () => {
    await setup(api({ getNamespaces: jest.fn().mockReturnValue(of([])) }))
    expect(Object.keys(form().controls)).toEqual(['workflowType', 'namespaceId', 'title', 'ticket', 'initialRequest'])
    form().patchValue({ workflowType: 'delivery', namespaceId: '', title: '', ticket: '', initialRequest: '' })
    expect(form().valid).toBe(false)
    form().controls['namespaceId'].setValue('ns-1')
    expect(form().valid).toBe(true)
    form().controls['title'].setValue('x'.repeat(201))
    expect(form().controls['title'].hasError('maxlength')).toBe(true)
  })

  it('bounds the optional initial request and submits it only when non-blank', async () => {
    const service = api()
    await setup(service)
    form().controls['initialRequest'].setValue('x'.repeat(4001))
    expect(form().controls['initialRequest'].hasError('maxlength')).toBe(true)

    form().patchValue({
      workflowType: 'delivery',
      namespaceId: 'ns-2',
      title: '',
      ticket: '',
      initialRequest: '  Migrer le portail talent  ',
    })
    jest.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true)
    ;(fixture.componentInstance as unknown as { onSubmit(): void }).onSubmit()

    const [payload] = service.createWorkflowRun.mock.calls[0]
    expect(payload).toEqual({ workflowType: 'delivery', initialRequest: 'Migrer le portail talent' })
  })

  it('calls the canonical use case without workflow or execution identity and navigates with the returned id', async () => {
    const service = api()
    await setup(service)
    form().patchValue({ workflowType: 'delivery', namespaceId: 'ns-2', title: 'Readable run', ticket: 'ABC-1' })
    const navigate = jest.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true)

    ;(fixture.componentInstance as unknown as { onSubmit(): void }).onSubmit()
    fixture.detectChanges()

    expect(service.createWorkflowRun).toHaveBeenCalledTimes(1)
    const [payload, namespaceId, key] = service.createWorkflowRun.mock.calls[0]
    expect(payload).toEqual({ workflowType: 'delivery', title: 'Readable run', parameters: { ticket: 'ABC-1' } })
    expect(payload.workflowId).toBeUndefined()
    expect(payload.namespaceId).toBeUndefined()
    expect(payload.execution).toBeUndefined()
    expect(namespaceId).toBe('ns-2')
    expect(key).toEqual(expect.any(String))
    expect(navigate).toHaveBeenCalledWith(['/sessions', 'generated-id'])
  })

  it('surfaces Factory rejection and does not navigate', async () => {
    const error: FactoryApiError = { code: 'INVALID_START_REQUEST', message: 'invalid', status: 400, raw: null }
    const service = api({ createWorkflowRun: jest.fn().mockReturnValue(throwError(() => error)) })
    const host = await setup(service)
    form().patchValue({ workflowType: 'delivery' })
    const navigate = jest.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true)

    ;(fixture.componentInstance as unknown as { onSubmit(): void }).onSubmit()
    fixture.detectChanges()

    expect(navigate).not.toHaveBeenCalled()
    expect(host.querySelector('[data-launch-error]')?.textContent).toContain('invalid')
  })
})
