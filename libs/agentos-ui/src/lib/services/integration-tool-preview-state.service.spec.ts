import { TestBed } from '@angular/core/testing'
import { HttpErrorResponse } from '@angular/common/http'
import {
  IntegrationConfigToolPreview,
  IntegrationConfigToolPreviewToolConfirmationModeEnum,
} from '@whoz-oss/agentos-api-client'
import { Subject, of, throwError } from 'rxjs'
import { IntegrationConfigStateService } from './integration-config-state.service'
import { IntegrationToolPreviewStateService, describePreviewError } from './integration-tool-preview-state.service'

describe('IntegrationToolPreviewStateService', () => {
  const preview: IntegrationConfigToolPreview = {
    integrationType: 'MCP_HTTP',
    configName: 'MCP_PROD',
    namespaceDescription: '3 tools exposed',
    tools: [
      {
        name: 'MCP_PROD__ListTickets',
        description: 'Lists tickets',
        inputSchema: '{"type":"object"}',
        confirmationMode: IntegrationConfigToolPreviewToolConfirmationModeEnum.NONE,
      },
    ],
  }

  let integrationState: { previewTools: jest.Mock }
  let service: IntegrationToolPreviewStateService

  beforeEach(() => {
    integrationState = { previewTools: jest.fn().mockReturnValue(of(preview)) }
    TestBed.configureTestingModule({
      providers: [
        IntegrationToolPreviewStateService,
        { provide: IntegrationConfigStateService, useValue: integrationState },
      ],
    })
    service = TestBed.inject(IntegrationToolPreviewStateService)
  })

  it('starts idle with no preview and no error', () => {
    expect(service.status()).toBe('idle')
    expect(service.preview()).toBeNull()
    expect(service.errorMessage()).toBeNull()
    expect(service.isLoading()).toBe(false)
  })

  it('goes idle -> loading -> success and exposes the preview', () => {
    const pending = new Subject<IntegrationConfigToolPreview>()
    integrationState.previewTools.mockReturnValue(pending.asObservable())

    service.load('cfg-1', 'ns-1')
    expect(service.status()).toBe('loading')
    expect(service.isLoading()).toBe(true)

    pending.next(preview)
    pending.complete()
    expect(service.status()).toBe('success')
    expect(service.preview()).toEqual(preview)
    expect(service.errorMessage()).toBeNull()
  })

  it('goes idle -> loading -> error with the backend message and no preview', () => {
    integrationState.previewTools.mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: 400, error: { message: 'namespaceId query parameter is required' } })
      )
    )

    service.load('cfg-1', null)

    expect(service.status()).toBe('error')
    expect(service.preview()).toBeNull()
    expect(service.errorMessage()).toBe('namespaceId query parameter is required')
  })

  it('passes the namespaceId of a namespace route through to the state service', () => {
    service.load('cfg-1', 'ns-1')
    expect(integrationState.previewTools).toHaveBeenCalledWith('cfg-1', 'ns-1')
  })

  it('passes null when the form has no namespace context', () => {
    service.load('cfg-1', null)
    expect(integrationState.previewTools).toHaveBeenCalledWith('cfg-1', null)
  })

  it('ignores a second load while one is in flight', () => {
    integrationState.previewTools.mockReturnValue(new Subject<IntegrationConfigToolPreview>().asObservable())

    service.load('cfg-1', 'ns-1')
    service.load('cfg-1', 'ns-1')

    expect(integrationState.previewTools).toHaveBeenCalledTimes(1)
  })

  it('drops the previous result and error when a new load starts', () => {
    service.load('cfg-1', 'ns-1')
    expect(service.preview()).toEqual(preview)

    integrationState.previewTools.mockReturnValue(new Subject<IntegrationConfigToolPreview>().asObservable())
    service.load('cfg-1', 'ns-1')

    expect(service.status()).toBe('loading')
    expect(service.preview()).toBeNull()
    expect(service.errorMessage()).toBeNull()
  })
})

describe('describePreviewError', () => {
  it('prefers the message of a Spring error body', () => {
    const err = new HttpErrorResponse({ status: 422, error: { message: 'No plugin is loaded for JIRA' } })
    expect(describePreviewError(err)).toBe('No plugin is loaded for JIRA')
  })

  it('falls back to the error field written by the access-denied handler', () => {
    const err = new HttpErrorResponse({ status: 404, error: { error: 'Resource not found', status: 404 } })
    expect(describePreviewError(err)).toBe('Resource not found')
  })

  it('falls back to the HTTP status when the body carries no message', () => {
    const err = new HttpErrorResponse({ status: 500, statusText: 'Internal Server Error' })
    expect(describePreviewError(err)).toBe('Tool preview failed (HTTP 500)')
  })

  it('describes a non-HTTP failure generically', () => {
    expect(describePreviewError(new Error('boom'))).toBe('Tool preview failed: boom')
    expect(describePreviewError('weird')).toBe('Tool preview failed')
  })
})
