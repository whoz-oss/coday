import { ComponentFixture, TestBed } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'
import { AdminPageComponent } from './admin-page.component'

interface ApiStub {
  getWorkflowDefinitions: jest.Mock
  uploadWorkflowDefinition: jest.Mock
  deleteWorkflowDefinition: jest.Mock
}

function createApi(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    getWorkflowDefinitions: jest.fn().mockReturnValue(of([])),
    uploadWorkflowDefinition: jest.fn().mockReturnValue(of({})),
    deleteWorkflowDefinition: jest.fn().mockReturnValue(of({})),
    ...overrides,
  }
}

interface DialogStub {
  open: jest.Mock
}

function createDialog(confirm: boolean): DialogStub {
  return { open: jest.fn().mockReturnValue({ afterClosed: () => of(confirm) }) }
}

const forbiddenError: FactoryApiError = {
  code: 'FORBIDDEN_ADMIN_REQUIRED',
  message: 'droits d’administration requis',
  status: 403,
  raw: null,
}

interface SetupResult {
  host: HTMLElement
  fixture: ComponentFixture<AdminPageComponent>
  dialog: DialogStub
}

describe('AdminPageComponent', () => {
  async function setup(api: ApiStub, confirm = true): Promise<SetupResult> {
    const dialog = createDialog(confirm)
    await TestBed.configureTestingModule({
      imports: [AdminPageComponent],
      providers: [
        { provide: FactoryApiService, useValue: api },
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents()
    const fixture = TestBed.createComponent(AdminPageComponent)
    fixture.detectChanges()
    return { host: fixture.nativeElement as HTMLElement, fixture, dialog }
  }

  function query<T extends HTMLElement>(host: HTMLElement, selector: string): T {
    const element = host.querySelector(selector)
    if (!element) throw new Error(`Expected element ${selector}`)
    return element as T
  }

  it('sets the breadcrumb and fetches the workflow definitions on init', async () => {
    const definitions = [{ workflowType: 't', version: 'v1', definitionHash: 'h1' }]
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)) })
    const { host } = await setup(api)

    expect(TestBed.inject(ShellState).crumbs()).toEqual([{ label: 'Workflow definitions' }])
    expect(api.getWorkflowDefinitions).toHaveBeenCalledTimes(1)
    expect(host.querySelector('[data-admin-gc]')).toBeNull()
    expect(host.querySelector('[data-admin-purge]')).toBeNull()
    expect(host.querySelector('[data-admin-legal]')).toBeNull()
    expect(host.querySelector('[data-admin-definitions]')).not.toBeNull()
    expect(host.querySelectorAll('[data-definition-type]')).toHaveLength(1)
    expect(host.querySelector('[data-definition-type]')?.textContent).toContain('t')
  })

  it('uploads the selected file and refreshes the definitions', async () => {
    const api = createApi({
      getWorkflowDefinitions: jest.fn().mockReturnValue(of([])),
      uploadWorkflowDefinition: jest.fn().mockReturnValue(of({ ok: true })),
    })
    const { host, fixture } = await setup(api)

    const file = new File(['{}'], 'definition.json', { type: 'application/json' })
    const fileInput = query<HTMLInputElement>(host, '[data-admin-definition-file]')
    Object.defineProperty(fileInput, 'files', { value: [file], configurable: true })
    fileInput.dispatchEvent(new Event('change'))
    fixture.detectChanges()

    query<HTMLButtonElement>(host, '[data-admin-definition-upload]').click()
    fixture.detectChanges()

    expect(api.uploadWorkflowDefinition).toHaveBeenCalledWith(file)
    // initial load + refresh after upload
    expect(api.getWorkflowDefinitions).toHaveBeenCalledTimes(2)
  })

  it('deletes a definition after confirmation and refreshes the list', async () => {
    const definitions = [{ workflowType: 't', version: 'v1', definitionHash: 'h1' }]
    const api = createApi({
      getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)),
      deleteWorkflowDefinition: jest.fn().mockReturnValue(of({ status: 'deleted' })),
    })
    const { host, fixture, dialog } = await setup(api, true)

    query<HTMLButtonElement>(host, '[data-admin-definition-delete]').click()
    fixture.detectChanges()

    expect(dialog.open).toHaveBeenCalledTimes(1)
    expect(api.deleteWorkflowDefinition).toHaveBeenCalledWith('t', 'v1')
    expect(api.getWorkflowDefinitions).toHaveBeenCalledTimes(2)
  })

  it('does not delete a definition when the confirmation is cancelled', async () => {
    const definitions = [{ workflowType: 't', version: 'v1', definitionHash: 'h1' }]
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)) })
    const { host, fixture } = await setup(api, false)

    query<HTMLButtonElement>(host, '[data-admin-definition-delete]').click()
    fixture.detectChanges()

    expect(api.deleteWorkflowDefinition).not.toHaveBeenCalled()
    expect(api.getWorkflowDefinitions).toHaveBeenCalledTimes(1)
  })

  it('locks the page and renders the verbatim server message on a 403 at init', async () => {
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(throwError(() => forbiddenError)) })
    const { host } = await setup(api)

    const banner = query<HTMLElement>(host, '[data-admin-forbidden]')
    expect(banner.textContent).toContain('droits d’administration requis')
    expect(banner.textContent).toContain('FORBIDDEN_ADMIN_REQUIRED')
    expect(query<HTMLButtonElement>(host, '[data-admin-definition-upload]').disabled).toBe(true)
  })

  it('shows a non-forbidden error in the definitions section banner without locking the page', async () => {
    const serverError: FactoryApiError = {
      code: 'SERVICE_UNAVAILABLE',
      message: 'moteur indisponible',
      status: 503,
      raw: null,
    }
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(throwError(() => serverError)) })
    const { host } = await setup(api)

    expect(host.querySelector('[data-admin-forbidden]')).toBeNull()
    expect(query<HTMLElement>(host, '[data-admin-definition-error]').textContent).toContain('moteur indisponible')
    expect(query<HTMLButtonElement>(host, '[data-admin-definition-upload]').disabled).toBe(false)
  })
})
