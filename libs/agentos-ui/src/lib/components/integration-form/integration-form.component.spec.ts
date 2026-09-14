import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router'
import { AuthSettingControllerService, IntegrationTypeControllerService } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { IntegrationFormComponent } from './integration-form.component'

describe('IntegrationFormComponent platform authentication', () => {
  it.each(['create', 'edit'])('offers platform authentication settings in %s mode', (mode) => {
    const listAuthSetting = jest.fn((namespaceId?: string, userId?: string) =>
      of(
        namespaceId === 'none' && userId === undefined
          ? [{ name: 'Company Jira' }]
          : [{ name: 'Personal Jira', userId: 'user-1' }]
      )
    )
    TestBed.configureTestingModule({
      imports: [IntegrationFormComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              params: {},
              paramMap: convertToParamMap(mode === 'edit' ? { integrationId: 'integration-1' } : {}),
              queryParamMap: convertToParamMap({}),
            },
          },
        },
        { provide: Router, useValue: { navigate: jest.fn() } },
        {
          provide: IntegrationConfigStateService,
          useValue: {
            getById: jest
              .fn()
              .mockReturnValue(
                of({ id: 'integration-1', name: 'Jira', integrationType: 'MCP_HTTP', authSettingName: 'Company Jira' })
              ),
          },
        },
        { provide: IntegrationTypeControllerService, useValue: { listTypesIntegrationType: () => of([]) } },
        { provide: AuthSettingControllerService, useValue: { listAuthSetting } },
        { provide: NamespaceRoleStateService, useValue: {} },
      ],
    })
    const fixture = TestBed.createComponent(IntegrationFormComponent)

    fixture.detectChanges()

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#integration-auth-setting')
    expect(Array.from(select.options, (option) => option.textContent?.trim())).toEqual(['None', 'Company Jira'])
    expect(listAuthSetting).toHaveBeenCalledWith('none')
    if (mode === 'edit') expect(select.value).toBe('Company Jira')
  })
})
