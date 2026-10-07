import { ComponentFixture, TestBed } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'
import { AdminPageComponent } from './admin-page.component'

interface ApiStub {
  runGarbageCollection: jest.Mock
  purgeArtifact: jest.Mock
  setLegalHold: jest.Mock
  getWorkflowDefinitions: jest.Mock
  uploadWorkflowDefinition: jest.Mock
  deleteWorkflowDefinition: jest.Mock
}

function createApi(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    runGarbageCollection: jest.fn().mockReturnValue(of({})),
    purgeArtifact: jest.fn().mockReturnValue(of({})),
    setLegalHold: jest.fn().mockReturnValue(of({})),
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

  function setInput(host: HTMLElement, selector: string, value: string, fixture: ComponentFixture<unknown>): void {
    const input = query<HTMLInputElement>(host, selector)
    input.value = value
    input.dispatchEvent(new Event('input'))
    fixture.detectChanges()
  }

  it('sets the breadcrumb and fetches the workflow definitions on init', async () => {
    const definitions = [{ workflowType: 't', version: 'v1', definitionHash: 'h1' }]
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)) })
    const { host } = await setup(api)

    expect(TestBed.inject(ShellState).crumbs()).toEqual([{ label: 'Gouvernance des artefacts' }])
    expect(api.getWorkflowDefinitions).toHaveBeenCalledTimes(1)
    expect(host.querySelector('[data-admin-gc]')).not.toBeNull()
    expect(host.querySelector('[data-admin-purge]')).not.toBeNull()
    expect(host.querySelector('[data-admin-legal]')).not.toBeNull()
    expect(host.querySelector('[data-admin-definitions]')).not.toBeNull()
    expect(host.querySelectorAll('[data-definition-type]')).toHaveLength(1)
    expect(host.querySelector('[data-definition-type]')?.textContent).toContain('t')
  })

  it('runs GC with the dryRun flag and renders the report metrics', async () => {
    const report = {
      reclaimedStagingKeys: ['a', 'b'],
      scannedBlobKeys: ['x'],
      scannedMetadataRows: 7,
      anomalies: [],
      timestamp: '2026-01-01T00:00:00Z',
    }
    const api = createApi({ runGarbageCollection: jest.fn().mockReturnValue(of(report)) })
    const { host, fixture } = await setup(api)

    const dryRun = query<HTMLInputElement>(host, '[data-admin-gc-dry-run] input[type="checkbox"]')
    dryRun.click()
    fixture.detectChanges()

    query<HTMLButtonElement>(host, '[data-admin-gc-run]').click()
    fixture.detectChanges()

    expect(api.runGarbageCollection).toHaveBeenCalledWith({ dryRun: true })
    const result = query<HTMLElement>(host, '[data-admin-gc-result]')
    expect(result.textContent).toContain('staging recyclés : 2')
    expect(result.textContent).toContain('blobs scannés : 1')
    expect(result.textContent).toContain('lignes métadonnées : 7')
    expect(result.textContent).toContain('anomalies : 0')
    expect(result.textContent).toContain('2026-01-01T00:00:00Z')
  })

  it('purges an artifact after confirmation and renders the result', async () => {
    const api = createApi({
      purgeArtifact: jest
        .fn()
        .mockReturnValue(of({ status: 'purged', artifactId: 'art-1', reason: 'expired', metadata: { size: 2048 } })),
    })
    const { host, fixture, dialog } = await setup(api, true)

    setInput(host, '[data-admin-purge-id]', 'art-1', fixture)
    setInput(host, '[data-admin-purge-reason]', 'expired', fixture)
    query<HTMLButtonElement>(host, '[data-admin-purge-submit]').click()
    fixture.detectChanges()

    expect(dialog.open).toHaveBeenCalledTimes(1)
    expect(api.purgeArtifact).toHaveBeenCalledWith('art-1', { reason: 'expired' })
    const result = query<HTMLElement>(host, '[data-admin-purge-result]')
    expect(result.textContent).toContain('purged')
    expect(result.textContent).toContain('art-1')
    expect(result.textContent).toContain('libéré : 2.0 Ko')
    expect(result.textContent).toContain('motif : expired')
  })

  it('does not call the purge API when the confirmation is cancelled', async () => {
    const api = createApi()
    const { host, fixture } = await setup(api, false)

    setInput(host, '[data-admin-purge-id]', 'art-1', fixture)
    query<HTMLButtonElement>(host, '[data-admin-purge-submit]').click()
    fixture.detectChanges()

    expect(api.purgeArtifact).not.toHaveBeenCalled()
    expect(host.querySelector('[data-admin-purge-result]')).toBeNull()
  })

  it('applies a legal hold and renders the active state', async () => {
    const api = createApi({
      setLegalHold: jest.fn().mockReturnValue(of({ id: 'art-2', legalHold: true, legalHoldReason: 'audit' })),
    })
    const { host, fixture } = await setup(api)

    setInput(host, '[data-admin-legal-id]', 'art-2', fixture)
    query<HTMLButtonElement>(host, '[data-admin-legal-submit]').click()
    fixture.detectChanges()

    expect(api.setLegalHold).toHaveBeenCalledWith('art-2', { legalHold: true })
    const result = query<HTMLElement>(host, '[data-admin-legal-result]')
    expect(result.textContent).toContain('legal hold : actif')
    expect(result.textContent).toContain('motif : audit')
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
    expect(query<HTMLButtonElement>(host, '[data-admin-gc-run]').disabled).toBe(true)
    expect(query<HTMLButtonElement>(host, '[data-admin-purge-submit]').disabled).toBe(true)
    expect(query<HTMLButtonElement>(host, '[data-admin-definition-upload]').disabled).toBe(true)
  })

  it('disables admin actions and shows the verbatim message when a command returns 403', async () => {
    const api = createApi({ runGarbageCollection: jest.fn().mockReturnValue(throwError(() => forbiddenError)) })
    const { host, fixture } = await setup(api)

    query<HTMLButtonElement>(host, '[data-admin-gc-run]').click()
    fixture.detectChanges()

    const banner = query<HTMLElement>(host, '[data-admin-forbidden]')
    expect(banner.textContent).toContain('droits d’administration requis')
    expect(banner.textContent).toContain('FORBIDDEN_ADMIN_REQUIRED')
    expect(query<HTMLButtonElement>(host, '[data-admin-gc-run]').disabled).toBe(true)
    expect(query<HTMLInputElement>(host, '[data-admin-purge-id]').disabled).toBe(true)
  })

  it('shows a non-forbidden error in the section banner without locking the page', async () => {
    const serverError: FactoryApiError = {
      code: 'SERVICE_UNAVAILABLE',
      message: 'moteur indisponible',
      status: 503,
      raw: null,
    }
    const api = createApi({ runGarbageCollection: jest.fn().mockReturnValue(throwError(() => serverError)) })
    const { host, fixture } = await setup(api)

    query<HTMLButtonElement>(host, '[data-admin-gc-run]').click()
    fixture.detectChanges()

    expect(host.querySelector('[data-admin-forbidden]')).toBeNull()
    expect(query<HTMLElement>(host, '[data-admin-gc-error]').textContent).toContain('moteur indisponible')
    expect(query<HTMLButtonElement>(host, '[data-admin-gc-run]').disabled).toBe(false)
  })
})
