import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AuthSettingControllerService,
  IntegrationConfig,
  IntegrationTypeControllerService,
} from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import { IntegrationToolPreviewStateService } from '../../services/integration-tool-preview-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { IntegrationFormComponent } from './integration-form.component'

/**
 * Focused on the "Preview tools" action of the edit form: it is gated on a namespace context
 * (the platform admin screen has none) and forwards the route namespace to the preview state.
 * The component class is driven directly (ngOnInit / previewTools) without rendering the template.
 */
describe('IntegrationFormComponent (tool preview)', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'

  let state: { setNamespace: jest.Mock; getById: jest.Mock }
  let toolPreview: {
    load: jest.Mock
    status: jest.Mock
    preview: jest.Mock
    errorMessage: jest.Mock
    isLoading: jest.Mock
  }
  let fixture: ComponentFixture<IntegrationFormComponent>
  let component: IntegrationFormComponent

  const internals = () =>
    component as unknown as {
      canPreviewTools: () => boolean
      previewToolsTitle: string
      previewTools: () => void
    }

  function configure(routeNamespaceId: string | undefined, config: IntegrationConfig): void {
    state = { setNamespace: jest.fn(), getById: jest.fn().mockReturnValue(of(config)) }
    toolPreview = {
      load: jest.fn(),
      status: jest.fn().mockReturnValue('idle'),
      preview: jest.fn().mockReturnValue(null),
      errorMessage: jest.fn().mockReturnValue(null),
      isLoading: jest.fn().mockReturnValue(false),
    }
    const activatedRoute = {
      snapshot: {
        params: routeNamespaceId ? { namespaceId: routeNamespaceId } : {},
        paramMap: { get: (key: string) => (key === 'integrationId' ? config.id : null) },
        queryParamMap: { get: () => null },
      },
    }

    TestBed.configureTestingModule({
      imports: [IntegrationFormComponent],
      providers: [
        { provide: ActivatedRoute, useValue: activatedRoute },
        { provide: Router, useValue: { navigate: jest.fn() } },
        { provide: IntegrationConfigStateService, useValue: state },
        { provide: IntegrationTypeControllerService, useValue: { listTypesIntegrationType: () => of([]) } },
        { provide: AuthSettingControllerService, useValue: { listAuthSetting: () => of([]) } },
        { provide: NamespaceRoleStateService, useValue: { isAdminOfNamespace$: () => of(true) } },
      ],
    })
    // The preview state is component-provided: override it on the component itself.
    TestBed.overrideComponent(IntegrationFormComponent, {
      set: { providers: [{ provide: IntegrationToolPreviewStateService, useValue: toolPreview }] },
    })

    fixture = TestBed.createComponent(IntegrationFormComponent)
    component = fixture.componentInstance
    component.ngOnInit()
  }

  it('forwards the route namespace when previewing a namespace row opened from its namespace', () => {
    configure(NS_ID, { id: 'cfg-1', namespaceId: NS_ID, name: 'MCP_PROD', integrationType: 'MCP_HTTP' })

    expect(internals().canPreviewTools()).toBe(true)
    expect(internals().previewToolsTitle).toContain('Resolve the tools')

    internals().previewTools()

    expect(toolPreview.load).toHaveBeenCalledWith('cfg-1', NS_ID)
  })

  it('forwards the route namespace for a user-global row opened from a namespace route', () => {
    configure(NS_ID, { id: 'cfg-2', userId: 'me', name: 'BASH_LOCAL', integrationType: 'BASH' })

    internals().previewTools()

    expect(toolPreview.load).toHaveBeenCalledWith('cfg-2', NS_ID)
  })

  it('disables the action with a hint on the platform admin screen, which has no namespace context', () => {
    configure(undefined, { id: 'cfg-3', name: 'MCP_PLATFORM', integrationType: 'MCP_HTTP' })

    expect(internals().canPreviewTools()).toBe(false)
    expect(internals().previewToolsTitle).toContain('namespace')

    internals().previewTools()

    expect(toolPreview.load).not.toHaveBeenCalled()
  })

  it('does not preview while the preview is loading', () => {
    configure(NS_ID, { id: 'cfg-1', namespaceId: NS_ID, name: 'MCP_PROD', integrationType: 'MCP_HTTP' })
    toolPreview.isLoading.mockReturnValue(true)

    internals().previewTools()

    expect(toolPreview.load).not.toHaveBeenCalled()
  })
})
