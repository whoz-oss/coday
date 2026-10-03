import { ComponentFixture, TestBed } from '@angular/core/testing'
import { FormGroup } from '@angular/forms'
import { provideRouter, Router } from '@angular/router'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService } from '../../core/factory-api.service'
import { FactoryStore } from '../../core/factory.store'
import { ShellState } from '../../core/shell-state'
import { LaunchPageComponent } from './launch-page.component'

interface ApiStub {
  getWorkflowDefinitions: jest.Mock
  getNamespaces: jest.Mock
  startWorkflow: jest.Mock
  runWorkflow: jest.Mock
}

function createApi(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    getWorkflowDefinitions: jest.fn().mockReturnValue(of({ items: [] })),
    getNamespaces: jest.fn().mockReturnValue(of([])),
    startWorkflow: jest.fn().mockReturnValue(of({})),
    runWorkflow: jest.fn().mockReturnValue(of({ status: 'accepted', submissionId: 'sub-1' })),
    ...overrides,
  }
}

interface StoreStub {
  refresh: jest.Mock
}

function createStore(): StoreStub {
  return { refresh: jest.fn() }
}

interface ComponentAccess {
  form: FormGroup
  workflowTypes(): string[]
  namespaces(): string[]
  onSubmit(): void
}

const conflictError: FactoryApiError = {
  code: 'WORKFLOW_IDENTITY_CONFLICT',
  message: 'instance already exists',
  status: 409,
  raw: null,
}

describe('LaunchPageComponent', () => {
  let fixture: ComponentFixture<LaunchPageComponent>

  async function setup(api: ApiStub, store: StoreStub = createStore()): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [LaunchPageComponent],
      providers: [
        provideRouter([]),
        { provide: FactoryApiService, useValue: api },
        { provide: FactoryStore, useValue: store },
      ],
    }).compileComponents()
    fixture = TestBed.createComponent(LaunchPageComponent)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function access(): ComponentAccess {
    return fixture.componentInstance as unknown as ComponentAccess
  }

  it('sets the breadcrumb and loads definitions and namespaces on init', async () => {
    const api = createApi({
      getWorkflowDefinitions: jest.fn().mockReturnValue(
        of({
          items: [
            { workflowType: 'adw_simple_sdlc', version: 'v1' },
            { workflowType: 'adw_simple_sdlc', version: 'v2' },
            { workflowType: 'hotfix', version: 'v1' },
          ],
        })
      ),
      getNamespaces: jest.fn().mockReturnValue(of([{ namespaceId: 'ns-1' }, { namespaceId: 'ns-2' }])),
    })
    await setup(api)

    expect(TestBed.inject(ShellState).crumbs()).toEqual([
      { label: 'Sandboxes', link: '/sandboxes' },
      { label: 'Lancer un run' },
    ])
    expect(api.getWorkflowDefinitions).toHaveBeenCalledTimes(1)
    expect(api.getNamespaces).toHaveBeenCalledTimes(1)

    // Definitions are de-duplicated; namespaces are exposed for the select.
    expect(access().workflowTypes()).toEqual(['adw_simple_sdlc', 'hotfix'])
    expect(access().namespaces()).toEqual(['ns-1', 'ns-2'])
  })

  it('enforces required fields and the 4000-character limit', async () => {
    await setup(createApi())
    const form = access().form

    expect(form.valid).toBe(false)
    form.patchValue({ workflowType: 'wf', namespaceId: 'ns', controllerRequest: 'do it' })
    expect(form.valid).toBe(true)

    form.controls['controllerRequest'].setValue('x'.repeat(4001))
    expect(form.controls['controllerRequest'].hasError('maxlength')).toBe(true)
    form.controls['controllerRequest'].setValue('')
    expect(form.controls['controllerRequest'].hasError('required')).toBe(true)

    form.patchValue({ workflowType: 'wf', namespaceId: 'ns', controllerRequest: 'do it' })
    form.controls['workflowType'].setValue('')
    expect(form.controls['workflowType'].hasError('required')).toBe(true)
  })

  it('does not call the API when the form is invalid', async () => {
    const api = createApi()
    await setup(api)

    access().onSubmit()
    fixture.detectChanges()

    expect(api.startWorkflow).not.toHaveBeenCalled()
    expect(api.runWorkflow).not.toHaveBeenCalled()
  })

  it('starts then runs on submit, ignores a start conflict, refreshes and navigates', async () => {
    const api = createApi({
      startWorkflow: jest.fn().mockReturnValue(throwError(() => conflictError)),
      runWorkflow: jest.fn().mockReturnValue(of({ status: 'accepted', submissionId: 'sub-42' })),
    })
    const store = createStore()
    const host = await setup(api, store)

    const form = access().form
    form.patchValue({
      workflowType: 'adw_simple_sdlc',
      namespaceId: 'ns-1',
      controllerRequest: 'Ship the feature',
      repoRoot: '/repo',
      ticket: 'ABC-1',
    })

    const navigate = jest.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true)
    access().onSubmit()
    fixture.detectChanges()

    expect(api.startWorkflow).toHaveBeenCalledTimes(1)
    const [workflowId, startPayload, startNamespace] = api.startWorkflow.mock.calls[0]
    expect(workflowId).toMatch(/^wf-\d+-[a-z0-9]{5}$/)
    expect(startNamespace).toBe('ns-1')
    expect(startPayload).toEqual({
      workflow: {
        workflowId,
        workflowType: 'adw_simple_sdlc',
        title: 'Run adw_simple_sdlc',
        ticket: 'ABC-1',
      },
      execution: {
        namespaceId: 'ns-1',
        runtimeId: 'factory-dashboard',
        kind: 'agentos',
        agentId: 'factory-agent',
      },
      controllerRequest: 'Ship the feature',
    })

    expect(api.runWorkflow).toHaveBeenCalledTimes(1)
    const [runId, runPayload, runNamespace] = api.runWorkflow.mock.calls[0]
    expect(runId).toBe(workflowId)
    expect(runNamespace).toBe('ns-1')
    expect(runPayload).toEqual({ namespaceId: 'ns-1', ticket: 'ABC-1', repoRoot: '/repo' })

    expect(store.refresh).toHaveBeenCalledTimes(1)
    expect(navigate).toHaveBeenCalledWith(['/sessions', workflowId])
    expect(host.querySelector('[data-launch-success]')?.textContent).toContain('Lancement accepté (id: sub-42)')
    expect(host.querySelector('[data-launch-error]')).toBeNull()
  })

  it('propagates a non-conflict start failure without running or navigating', async () => {
    const serverError: FactoryApiError = {
      code: 'SERVICE_UNAVAILABLE',
      message: 'moteur indisponible',
      status: 503,
      raw: null,
    }
    const api = createApi({ startWorkflow: jest.fn().mockReturnValue(throwError(() => serverError)) })
    const store = createStore()
    const host = await setup(api, store)

    access().form.patchValue({
      workflowType: 'adw_simple_sdlc',
      namespaceId: 'ns-1',
      controllerRequest: 'Ship it',
    })
    const navigate = jest.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true)

    access().onSubmit()
    fixture.detectChanges()

    expect(api.runWorkflow).not.toHaveBeenCalled()
    expect(store.refresh).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
    expect(host.querySelector('[data-launch-error]')?.textContent).toContain('moteur indisponible')
    expect(host.querySelector('[data-launch-error]')?.textContent).toContain('SERVICE_UNAVAILABLE')
    expect(host.querySelector('[data-launch-success]')).toBeNull()
  })

  it('shows a readable error when the run call fails and never fabricates success', async () => {
    const serverError: FactoryApiError = {
      code: 'VALIDATION_FAILED',
      message: 'controllerRequest invalide',
      status: 400,
      raw: null,
    }
    const api = createApi({ runWorkflow: jest.fn().mockReturnValue(throwError(() => serverError)) })
    const store = createStore()
    const host = await setup(api, store)

    access().form.patchValue({
      workflowType: 'adw_simple_sdlc',
      namespaceId: 'ns-1',
      controllerRequest: 'Ship it',
    })
    const navigate = jest.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true)

    access().onSubmit()
    fixture.detectChanges()

    expect(api.startWorkflow).toHaveBeenCalledTimes(1)
    expect(api.runWorkflow).toHaveBeenCalledTimes(1)
    expect(store.refresh).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
    expect(host.querySelector('[data-launch-error]')?.textContent).toContain('controllerRequest invalide')
    expect(host.querySelector('[data-launch-success]')).toBeNull()
  })

  it('falls back to a manual workflowType input when no definitions are available', async () => {
    const host = await setup(createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(of({ items: [] })) }))

    expect(host.querySelector('input[data-launch-workflow-type]')).not.toBeNull()
  })
})
