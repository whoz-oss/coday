import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { Configuration, NamespaceGitControllerService } from '@whoz-oss/agentos-api-client'

describe('Namespace Git API', () => {
  let http: HttpTestingController
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Configuration, useValue: new Configuration({ basePath: '/agentos-api' }) },
      ],
    })
    http = TestBed.inject(HttpTestingController)
  })
  afterEach(() => {
    http.verify()
    TestBed.resetTestingModule()
  })

  it('decodes the namespace association as JSON', () => {
    const received = jest.fn()
    TestBed.inject(NamespaceGitControllerService).getAssociationNamespaceGit('namespace').subscribe(received)
    const request = http.expectOne('/agentos-api/api/namespaces/namespace/git')
    expect(request.request.responseType).toBe('json')
    request.flush({ associated: false })
    expect(received).toHaveBeenCalledWith({ associated: false })
  })

  it('saves the repository and shared credential UUID through the generated client', () => {
    const association = {
      repositoryUrl: 'https://example.com/repo.git',
      mainBranch: 'main',
      autoWorktreeForRootCases: false,
      serviceAuthSettingId: 'shared-credential',
    }
    TestBed.inject(NamespaceGitControllerService).setAssociationNamespaceGit('namespace', association).subscribe()
    const request = http.expectOne('/agentos-api/api/namespaces/namespace/git')
    expect(request.request.method).toBe('PUT')
    expect(request.request.body).toEqual(association)
    expect(request.request.responseType).toBe('json')
    request.flush({ associated: true, ...association })
  })
})
