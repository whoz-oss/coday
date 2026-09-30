import { HttpErrorResponse, provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router'
import { AuthSettingDto, Configuration, NamespaceGit } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { AuthSettingConfigStateService } from '../../services/auth-setting-config-state.service'
import { NamespaceGitComponent } from './namespace-git.component'

describe('NamespaceGitComponent loading and safe updates', () => {
  const url = '/agentos-api/api/namespaces/ns-1/git'
  const configured: NamespaceGit = {
    associated: true,
    repositoryUrl: 'https://forge.example/org/repo.git',
    mainBranch: 'trunk',
    serviceAuthSettingId: 'shared-auth',
    autoWorktreeForRootCases: true,
    setupCommand: 'pnpm install --ignore-scripts',
    checkoutStatus: 'READY',
  }
  let http: HttpTestingController
  let fixture: ComponentFixture<NamespaceGitComponent>
  let authSettings: { loadNamespaceSettings: jest.Mock }

  beforeEach(() => {
    authSettings = {
      loadNamespaceSettings: jest
        .fn()
        .mockReturnValue(
          of([{ id: 'shared-auth', name: 'Git service', authType: 'BearerTokenAuthSetting' } as AuthSettingDto])
        ),
    }
    TestBed.configureTestingModule({
      imports: [NamespaceGitComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Configuration, useValue: new Configuration({ basePath: '/agentos-api' }) },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ namespaceId: 'ns-1' }) } } },
        { provide: Router, useValue: { navigate: jest.fn() } },
        { provide: AuthSettingConfigStateService, useValue: authSettings },
      ],
    })
    http = TestBed.inject(HttpTestingController)
    jest.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    http.verify()
    fixture?.destroy()
    jest.restoreAllMocks()
    TestBed.resetTestingModule()
  })

  function create(): NamespaceGitComponent {
    fixture = TestBed.createComponent(NamespaceGitComponent)
    fixture.detectChanges()
    return fixture.componentInstance
  }

  function enterMinimumFields(component: NamespaceGitComponent): void {
    component.repositoryUrl.set('https://forge.example/org/new.git')
    component.serviceAuthSettingId.set('shared-auth')
    fixture.detectChanges()
  }

  it('cannot save before the initial association response even if fields are populated', () => {
    const component = create()
    enterMinimumFields(component)
    expect(component.canSave()).toBe(false)
    component.save()
    http.expectNone((request) => request.method === 'PUT')
    http.expectOne(url).flush({ associated: false, autoWorktreeForRootCases: false })
  })

  it.each([403, 500])('blocks saves after GET %s, then retries and preserves loaded automation/setup', (status) => {
    const component = create()
    http.expectOne(url).flush({ message: 'Could not read repository settings' }, { status, statusText: 'Load error' })
    enterMinimumFields(component)

    expect(component.canSave()).toBe(false)
    expect(fixture.nativeElement.querySelector('.namespace-git__save').disabled).toBe(true)
    expect(fixture.nativeElement.querySelector('fieldset').disabled).toBe(true)
    expect(fixture.nativeElement.textContent).toContain('Could not read repository settings')
    component.save()
    http.expectNone((request) => request.method === 'PUT')

    fixture.nativeElement.querySelector('.namespace-git__retry').click()
    expect(component.isLoading()).toBe(true)
    expect(component.canSave()).toBe(false)
    http.expectOne(url).flush(configured)
    fixture.detectChanges()
    expect(component.autoWorktree()).toBe(true)
    expect(component.setupCommand()).toBe(configured.setupCommand)
    expect(fixture.nativeElement.querySelector('.namespace-git__retry')).toBeNull()
    component.repositoryUrl.set('https://forge.example/org/corrected.git')
    component.save()
    const save = http.expectOne(url)
    expect(save.request.method).toBe('PUT')
    expect(save.request.body).toEqual({
      repositoryUrl: 'https://forge.example/org/corrected.git',
      mainBranch: 'trunk',
      serviceAuthSettingId: 'shared-auth',
      autoWorktreeForRootCases: true,
      setupCommand: 'pnpm install --ignore-scripts',
    })
    save.flush({ ...configured, repositoryUrl: save.request.body.repositoryUrl })
  })

  it('keeps an incomplete existing association distinct from a new configuration', () => {
    const component = create()
    http.expectOne(url).flush({
      associated: true,
      checkoutStatus: 'FAILED',
      checkoutFailureReason: 'The stored repository configuration needs repair',
      autoWorktreeForRootCases: false,
    })
    enterMinimumFields(component)

    expect(component.isAssociated()).toBe(true)
    expect(component.associationLoaded()).toBe(false)
    expect(component.canSave()).toBe(false)
    expect(fixture.nativeElement.textContent).toContain('The stored repository configuration needs repair')
    expect(fixture.nativeElement.querySelector('.namespace-git__save').textContent.trim()).toBe('Update')
    expect(fixture.nativeElement.querySelector('.namespace-git__remove').disabled).toBe(true)
    component.save()
    http.expectNone((request) => request.method === 'PUT')
    expect(fixture.nativeElement.querySelector('.namespace-git__retry')).not.toBeNull()
  })

  it('does not replace previously loaded setup or automation when a reload returns incomplete data', () => {
    const component = create()
    http.expectOne(url).flush(configured)
    component.retryLoad()
    http.expectOne(url).flush({ associated: true, autoWorktreeForRootCases: false, checkoutStatus: 'FAILED' })
    fixture.detectChanges()

    expect(component.autoWorktree()).toBe(true)
    expect(component.setupCommand()).toBe(configured.setupCommand)
    expect(component.repositoryUrl()).toBe(configured.repositoryUrl)
    expect(component.canSave()).toBe(false)
  })

  it('allows a new association only after an explicit successful not-associated response', () => {
    const component = create()
    http.expectOne(url).flush({ associated: false, autoWorktreeForRootCases: false })
    enterMinimumFields(component)
    expect(component.canSave()).toBe(true)
    component.save()
    const save = http.expectOne(url)
    expect(save.request.body.autoWorktreeForRootCases).toBe(false)
    expect(save.request.body.setupCommand).toBeUndefined()
    save.flush({ ...configured, autoWorktreeForRootCases: false })
  })

  it('exposes an auth-settings load failure as retryable instead of enabling an incomplete form', () => {
    authSettings.loadNamespaceSettings.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500, error: { message: 'Auth settings unavailable' } }))
    )
    const component = create()
    expect(http.expectOne(url).cancelled).toBe(true)
    enterMinimumFields(component)
    expect(component.isLoading()).toBe(false)
    expect(component.canSave()).toBe(false)
    expect(fixture.nativeElement.textContent).toContain('Auth settings unavailable')
    expect(fixture.nativeElement.querySelector('.namespace-git__retry')).not.toBeNull()
  })
})
