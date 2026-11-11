import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router'
import { AuthSettingControllerService, IntegrationTypeControllerService } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { UserStateService } from '../../services/user-state.service'
import { IntegrationFormComponent } from './integration-form.component'

const authSettings = [
  { name: 'Company Jira', namespaceId: null, userId: null },
  { name: 'Namespace Jira', namespaceId: 'ns-1', userId: null },
  { name: 'Personal namespace Jira', namespaceId: 'ns-1', userId: 'user-1' },
  { name: 'Personal Jira', namespaceId: null, userId: 'user-1' },
  // Same name defined at several layers: offered once, labelled with every layer.
  { name: 'shared', namespaceId: null, userId: null },
  { name: 'shared', namespaceId: 'ns-1', userId: null },
  { name: 'github', namespaceId: null, userId: 'user-1' },
]

// Queries sent to `listAuthSetting(namespaceId, userId)`, by layer.
const PLATFORM = ['none']
const NAMESPACE = ['ns-1']
const USER_ON_NS = ['ns-1', 'me']
const USER_GLOBAL = ['none', 'me']

interface Case {
  scope: string
  /** Raw config of the integration being edited (its own layer decides namespaceId / userId). */
  owner: { namespaceId: string | null; userId: string | null }
  expectedQueries: string[][]
  expectedOptions: string[]
}

const cases: Case[] = [
  {
    scope: 'platform',
    owner: { namespaceId: null, userId: null },
    expectedQueries: [PLATFORM, USER_GLOBAL],
    expectedOptions: ['Company Jira (platform)', 'github (mine)', 'Personal Jira (mine)', 'shared (platform)'],
  },
  {
    scope: 'namespace',
    owner: { namespaceId: 'ns-1', userId: null },
    expectedQueries: [PLATFORM, NAMESPACE, USER_ON_NS, USER_GLOBAL],
    expectedOptions: [
      'Company Jira (platform)',
      'github (mine)',
      'Namespace Jira (namespace)',
      'Personal Jira (mine)',
      'Personal namespace Jira (mine)',
      'shared (namespace, platform)',
    ],
  },
  {
    scope: 'userOnNs',
    owner: { namespaceId: 'ns-1', userId: 'user-1' },
    expectedQueries: [PLATFORM, NAMESPACE, USER_ON_NS, USER_GLOBAL],
    expectedOptions: [
      'Company Jira (platform)',
      'github (mine)',
      'Namespace Jira (namespace)',
      'Personal Jira (mine)',
      'Personal namespace Jira (mine)',
      'shared (namespace, platform)',
    ],
  },
  {
    scope: 'userGlobal',
    owner: { namespaceId: null, userId: 'user-1' },
    expectedQueries: [PLATFORM, USER_GLOBAL],
    expectedOptions: ['Company Jira (platform)', 'github (mine)', 'Personal Jira (mine)', 'shared (platform)'],
  },
]

function setup(
  { scope, owner }: Pick<Case, 'scope' | 'owner'>,
  mode: 'create' | 'edit',
  savedAuthSettingName: string | null = null,
  failingQuery?: string[]
) {
  // Keep the real AuthSettingConfigStateService: this checks the full frontend path
  // from the rendered form through the facade to the HTTP client's query arguments.
  const listAuthSetting = jest.fn((namespaceId?: string, userId?: string) => {
    if (failingQuery && failingQuery[0] === namespaceId && failingQuery[1] === userId) {
      return throwError(() => new Error('boom'))
    }
    return of(
      authSettings.filter(
        (candidate) =>
          candidate.namespaceId === (namespaceId === 'none' ? null : namespaceId) &&
          candidate.userId === (userId === 'me' ? 'user-1' : null)
      )
    )
  })
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
              authSettingName: savedAuthSettingName,
              ...owner,
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
  const optionTexts = () => Array.from(select.options, (option) => option.textContent?.trim())
  return { fixture, select, listAuthSetting, optionTexts }
}

describe.each(cases)('IntegrationFormComponent $scope authentication', (testCase) => {
  it.each(['create', 'edit'] as const)(
    'offers every auth setting name that can resolve for this scope in %s mode',
    (mode) => {
      const { listAuthSetting, optionTexts } = setup(testCase, mode)

      expect(optionTexts()).toEqual(['None', ...testCase.expectedOptions])
      expect(listAuthSetting).toHaveBeenCalledTimes(testCase.expectedQueries.length)
      for (const query of testCase.expectedQueries) {
        expect(listAuthSetting).toHaveBeenCalledWith(...query)
      }
    }
  )

  it('keeps the saved name selected in edit mode, the option value being the bare name', () => {
    const { select } = setup(testCase, 'edit', 'github')

    expect(select.value).toBe('github')
  })

  it('still shows a saved name that no visible layer defines, so saving does not drop it', () => {
    const { fixture, select, optionTexts } = setup(testCase, 'edit', 'ghost')
    fixture.detectChanges()

    expect(optionTexts()).toContain('ghost (not found)')
    expect(select.value).toBe('ghost')
  })

  it('does not blank the other layers when one layer fails to load', () => {
    const { optionTexts } = setup(testCase, 'create', null, USER_GLOBAL)

    expect(optionTexts()).toContain('Company Jira (platform)')
    expect(optionTexts()).not.toContain('github (mine)')
  })
})

describe('IntegrationFormComponent authentication hint', () => {
  it('explains that the name is resolved at run time, the most specific layer winning', () => {
    const { fixture } = setup(cases[1]!, 'create')

    expect(fixture.nativeElement.textContent).toContain('resolved at run time')
    expect(fixture.nativeElement.textContent).toContain('user on this namespace > namespace > user > platform')
  })
})
