import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router'
import { AuthSettingControllerService, IntegrationTypeControllerService } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { UserStateService } from '../../services/user-state.service'
import { IntegrationFormComponent } from './integration-form.component'

const authSettings = [
  { name: 'Company Jira', namespaceId: null, userId: null },
  { name: 'Namespace Jira', namespaceId: 'ns-1', userId: null },
  { name: 'Personal namespace Jira', namespaceId: 'ns-1', userId: 'user-1' },
  { name: 'Personal Jira', namespaceId: null, userId: 'user-1' },
]

describe.each([
  { scope: 'platform', setting: authSettings[0]!, expectedQuery: ['none'] },
  { scope: 'namespace', setting: authSettings[1]!, expectedQuery: ['ns-1'] },
  { scope: 'userOnNs', setting: authSettings[2]!, expectedQuery: ['ns-1', 'me'] },
  { scope: 'userGlobal', setting: authSettings[3]!, expectedQuery: ['none', 'me'] },
])('IntegrationFormComponent $scope authentication', ({ scope, setting, expectedQuery }) => {
  it.each(['create', 'edit'])('offers only the matching authentication settings in %s mode', (mode) => {
    // Keep the real AuthSettingConfigStateService: this checks the full frontend path
    // from the rendered form through the facade to the HTTP client's query arguments.
    const listAuthSetting = jest.fn((namespaceId?: string, userId?: string) =>
      of(
        authSettings.filter((candidate) => {
          if (namespaceId === undefined) return candidate.userId === 'user-1'
          return (
            candidate.namespaceId === (namespaceId === 'none' ? null : namespaceId) &&
            candidate.userId === (userId === 'me' ? 'user-1' : null)
          )
        })
      )
    )
    TestBed.configureTestingModule({
      imports: [IntegrationFormComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              params: scope === 'platform' ? {} : { namespaceId: 'ns-1' },
              paramMap: convertToParamMap(mode === 'edit' ? { integrationId: 'integration-1' } : {}),
              queryParamMap: convertToParamMap({ scope }),
            },
          },
        },
        { provide: Router, useValue: { navigate: jest.fn() } },
        {
          provide: IntegrationConfigStateService,
          useValue: {
            setNamespace: jest.fn(),
            getById: jest.fn().mockReturnValue(
              of({
                id: 'integration-1',
                name: 'Jira',
                integrationType: 'MCP_HTTP',
                authSettingName: setting.name,
                namespaceId: setting.namespaceId,
                userId: setting.userId,
              })
            ),
          },
        },
        { provide: IntegrationTypeControllerService, useValue: { listTypesIntegrationType: () => of([]) } },
        { provide: AuthSettingControllerService, useValue: { listAuthSetting } },
        { provide: UserStateService, useValue: { currentUser: () => ({ id: 'user-1' }) } },
        { provide: NamespaceRoleStateService, useValue: { isAdminOfNamespace$: () => of(true) } },
      ],
    })
    const fixture = TestBed.createComponent(IntegrationFormComponent)

    fixture.detectChanges()

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#integration-auth-setting')
    expect(Array.from(select.options, (option) => option.textContent?.trim())).toEqual(['None', setting.name])
    expect(listAuthSetting).toHaveBeenCalledTimes(1)
    expect(listAuthSetting).toHaveBeenCalledWith(...expectedQuery)
    if (mode === 'edit') expect(select.value).toBe(setting.name)
  })
})
