import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing'
import { FormGroup } from '@angular/forms'
import { provideRouter, Router } from '@angular/router'
import { Subject, of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService } from '../../core/factory-api.service'
import { FactoryStore } from '../../core/factory.store'
import { LaunchPageComponent } from './launch-page.component'

interface ApiStub {
  getWorkflowDefinitions: jest.Mock
  getNamespaces: jest.Mock
  createWorkflowRun: jest.Mock
}

const NS1 = '0d4bd471-df37-43d8-a8f7-c989f95e71d7'
const NS2 = '1e5ce582-ea48-49e9-b9f8-d0a0b6f82e28'

function api(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    getWorkflowDefinitions: jest.fn().mockReturnValue(of({ items: [{ workflowType: 'delivery' }] })),
    getNamespaces: jest.fn().mockReturnValue(
      of([
        { namespaceId: NS1, name: 'Talent' },
        { id: NS2, name: 'Core' },
      ])
    ),
    createWorkflowRun: jest.fn().mockReturnValue(
      of({
        workflowId: 'generated-id',
        title: 'Run',
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

describe('LaunchPageComponent namespace launch', () => {
  let fixture: ComponentFixture<LaunchPageComponent>

  async function setup(service: ApiStub): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [LaunchPageComponent],
      providers: [
        provideRouter([]),
        { provide: FactoryApiService, useValue: service },
        { provide: FactoryStore, useValue: { refresh: jest.fn(), selectNamespace: jest.fn() } },
      ],
    }).compileComponents()
    fixture = TestBed.createComponent(LaunchPageComponent)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function form(): FormGroup {
    return (fixture.componentInstance as unknown as { form: FormGroup }).form
  }

  it('uses the explicit namespace contract without workstream input', async () => {
    await setup(api())
    expect(Object.keys(form().controls)).toEqual(['workflowType', 'namespaceId', 'title', 'ticket', 'initialRequest'])
    expect(form().controls['namespaceId'].value).toBe('')
    form().controls['namespaceId'].setValue(NS1)
    expect(form().valid).toBe(true)
  })

  it('keeps a namespace visible and selectable without any Factory workstream lookup', async () => {
    const service = api({
      getNamespaces: jest.fn().mockReturnValue(of([{ namespaceId: NS1, name: 'Never launched' }])),
    })
    const host = await setup(service)
    expect(form().controls['namespaceId'].value).toBe(NS1)
    // Material options render in the overlay only when the select is opened;
    // assert the loaded option through component state and the selected id hint.
    const loaded = (
      fixture.componentInstance as unknown as { namespaces: () => Array<{ id: string; name: string }> }
    ).namespaces()
    expect(loaded).toEqual([{ id: NS1, name: 'Never launched' }])
    const namespaceField = host.querySelector('[data-launch-namespace="true"]')?.closest('mat-form-field')
    expect(namespaceField?.querySelector('mat-hint')?.textContent).toContain(`Namespace id: ${NS1}`)
    expect((service as unknown as { getWorkstreams?: jest.Mock }).getWorkstreams).toBeUndefined()
  })

  it('shows loading, error and empty namespace states', fakeAsync(async () => {
    const namespaces = new Subject<unknown[]>()
    const host = await setup(api({ getNamespaces: jest.fn().mockReturnValue(namespaces.asObservable()) }))
    expect(host.querySelector('[data-launch-targets-loading]')).not.toBeNull()
    namespaces.error({ code: 'UNAVAILABLE', message: 'Namespaces unavailable', status: 503, raw: null })
    tick()
    fixture.detectChanges()
    expect(host.querySelector('[data-launch-targets-error]')?.textContent).toContain('Namespaces unavailable')
  }))

  it('shows an explicit empty state', async () => {
    const host = await setup(api({ getNamespaces: jest.fn().mockReturnValue(of([])) }))
    expect(host.querySelector('[data-launch-targets-empty]')).not.toBeNull()
    expect((host.querySelector('[data-launch-submit]') as HTMLButtonElement).disabled).toBe(true)
  })

  it('refuses a namespace not present in the loaded list', async () => {
    const service = api()
    const host = await setup(service)
    form().patchValue({ workflowType: 'delivery', namespaceId: '00000000-0000-4000-8000-000000000000' })
    ;(fixture.componentInstance as unknown as { onSubmit(): void }).onSubmit()
    fixture.detectChanges()
    expect(service.createWorkflowRun).not.toHaveBeenCalled()
    expect(host.querySelector('[data-launch-error]')?.textContent).toContain('Select an available namespace')
  })

  it('submits namespace explicitly and navigates using the returned workflow id', async () => {
    const service = api()
    await setup(service)
    form().patchValue({
      workflowType: 'delivery',
      namespaceId: NS2,
      title: 'Readable',
      ticket: 'ABC-1',
      initialRequest: ' Build ',
    })
    const navigate = jest.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true)
    ;(fixture.componentInstance as unknown as { onSubmit(): void }).onSubmit()
    expect(service.createWorkflowRun.mock.calls[0][0]).toEqual({
      namespaceId: NS2,
      workflowType: 'delivery',
      title: 'Readable',
      initialRequest: 'Build',
      parameters: { ticket: 'ABC-1' },
    })
    expect(navigate).toHaveBeenCalledWith(['/sessions', 'generated-id'], { queryParams: { namespaceId: NS2 } })
  })

  it('surfaces Factory rejection and does not navigate', async () => {
    const error: FactoryApiError = {
      code: 'INVALID_NAMESPACE_ID',
      message: 'invalid namespace',
      status: 400,
      raw: null,
    }
    const service = api({ createWorkflowRun: jest.fn().mockReturnValue(throwError(() => error)) })
    const host = await setup(service)
    form().patchValue({ workflowType: 'delivery', namespaceId: NS1 })
    const navigate = jest.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true)
    ;(fixture.componentInstance as unknown as { onSubmit(): void }).onSubmit()
    fixture.detectChanges()
    expect(navigate).not.toHaveBeenCalled()
    expect(host.querySelector('[data-launch-error]')?.textContent).toContain('invalid namespace')
  })
})
