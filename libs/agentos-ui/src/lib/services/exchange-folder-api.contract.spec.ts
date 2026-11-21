import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { Configuration, ExchangeFileEntryScopeEnum, ExchangeFolderApiService } from '@whoz-oss/agentos-api-client'

/**
 * The wire contract of the folder routes, which the hand-written client must follow exactly: both scopes, the
 * query parameters, the multipart field, and the ZIP as a blob. (The api-client lib has no test target of its own.)
 */
describe('ExchangeFolderApiService', () => {
  const CASE = ExchangeFileEntryScopeEnum.CASE
  const NAMESPACE = ExchangeFileEntryScopeEnum.NAMESPACE
  let http: HttpTestingController
  let api: ExchangeFolderApiService

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Configuration, useValue: new Configuration({ basePath: '/api-base' }) },
      ],
    })
    http = TestBed.inject(HttpTestingController)
    api = TestBed.inject(ExchangeFolderApiService)
  })

  afterEach(() => http.verify())

  describe('uploadFile', () => {
    it.each([
      [CASE, '/api-base/api/cases/c-1/files'],
      [NAMESPACE, '/api-base/api/namespaces/c-1/files'],
    ])('posts the %s file as multipart `file` with the directory as a query parameter', (scope, url) => {
      const file = new File(['x'], 'report.txt')

      api.uploadFile(scope, 'c-1', 'docs/specs', file).subscribe()

      const request = http.expectOne((r) => r.url === url)
      expect(request.request.method).toBe('POST')
      expect(request.request.params.get('directory')).toBe('docs/specs')
      expect(request.request.body).toBeInstanceOf(FormData)
      expect((request.request.body as FormData).get('file')).toBe(file)
      request.flush({})
    })

    it('sends no directory at the root, which is the unchanged upload', () => {
      api.uploadFile(CASE, 'c-1', '', new File(['x'], 'a.txt')).subscribe()

      const request = http.expectOne('/api-base/api/cases/c-1/files')
      expect(request.request.params.has('directory')).toBe(false)
      request.flush({})
    })

    it('encodes the directory like the generated client does, so `+` and `&` survive', () => {
      api.uploadFile(CASE, 'c-1', 'a+b & c', new File(['x'], 'a.txt')).subscribe()

      const request = http.expectOne((r) => r.url === '/api-base/api/cases/c-1/files')
      expect(request.request.urlWithParams).toContain('directory=a%2Bb%20%26%20c')
      request.flush({})
    })
  })

  describe('createDirectory', () => {
    it.each([
      [CASE, '/api-base/api/cases/c-1/files/directory'],
      [NAMESPACE, '/api-base/api/namespaces/c-1/files/directory'],
    ])('posts to the %s directory route with the path', (scope, url) => {
      api.createDirectory(scope, 'c-1', 'docs/api').subscribe()

      const request = http.expectOne((r) => r.url === url)
      expect(request.request.method).toBe('POST')
      expect(request.request.params.get('path')).toBe('docs/api')
      request.flush({ path: 'docs/api', name: 'api', directory: true })
    })
  })

  describe('deleteDirectory', () => {
    it.each([
      [CASE, '/api-base/api/cases/c-1/files/directory'],
      [NAMESPACE, '/api-base/api/namespaces/c-1/files/directory'],
    ])('deletes on the %s directory route with the path', (scope, url) => {
      api.deleteDirectory(scope, 'c-1', 'docs').subscribe()

      const request = http.expectOne((r) => r.url === url)
      expect(request.request.method).toBe('DELETE')
      expect(request.request.params.get('path')).toBe('docs')
      request.flush({ success: true, message: 'ok' })
    })
  })

  describe('downloadArchive', () => {
    it.each([
      [CASE, '/api-base/api/cases/c-1/files/archive'],
      [NAMESPACE, '/api-base/api/namespaces/c-1/files/archive'],
    ])('gets the %s archive as a zip blob', (scope, url) => {
      let body: Blob | undefined
      api.downloadArchive(scope, 'c-1', 'docs').subscribe((blob) => (body = blob))

      const request = http.expectOne((r) => r.url === url)
      expect(request.request.method).toBe('GET')
      expect(request.request.params.get('path')).toBe('docs')
      expect(request.request.responseType).toBe('blob')
      expect(request.request.headers.get('Accept')).toBe('application/zip')
      request.flush(new Blob(['zip'], { type: 'application/zip' }))
      expect(body).toBeInstanceOf(Blob)
    })

    it('asks for the whole scope with an empty path', () => {
      api.downloadArchive(CASE, 'c-1', '').subscribe()

      const request = http.expectOne((r) => r.url === '/api-base/api/cases/c-1/files/archive')
      expect(request.request.params.get('path')).toBe('')
      request.flush(new Blob(['zip']))
    })
  })

  it('sends credentials when the configuration asks for them', () => {
    TestBed.resetTestingModule()
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Configuration, useValue: new Configuration({ basePath: '', withCredentials: true }) },
      ],
    })
    const credentialed = TestBed.inject(ExchangeFolderApiService)
    const backend = TestBed.inject(HttpTestingController)

    credentialed.createDirectory(CASE, 'c-1', 'docs').subscribe()

    const request = backend.expectOne((r) => r.url === '/api/cases/c-1/files/directory')
    expect(request.request.withCredentials).toBe(true)
    request.flush({})
    backend.verify()
  })
})
