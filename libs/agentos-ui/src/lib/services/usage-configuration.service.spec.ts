import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { Configuration, UsageConfigurationControllerService } from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
import { UsageConfigurationService } from './usage-configuration.service'

describe('UsageConfigurationService', () => {
  let api: { getUsageConfiguration: jest.Mock }

  beforeEach(() => {
    api = { getUsageConfiguration: jest.fn() }
    TestBed.configureTestingModule({
      providers: [{ provide: UsageConfigurationControllerService, useValue: api }],
    })
  })

  it('defaults to non-enabled while sharing one pending configuration request', () => {
    const response = new Subject<{ enabled: boolean }>()
    api.getUsageConfiguration.mockReturnValue(response)
    const configuration = TestBed.inject(UsageConfigurationService)
    expect(configuration.state()).toBe('loading')
    expect(configuration.enabled()).toBe(false)
    expect(TestBed.inject(UsageConfigurationService)).toBe(configuration)
    configuration.retry()
    configuration.retry()
    expect(api.getUsageConfiguration).toHaveBeenCalledTimes(1)
    response.next({ enabled: true })
    response.complete()
    expect(configuration.state()).toBe('enabled')
    expect(configuration.enabled()).toBe(true)
    configuration.retry()
    expect(api.getUsageConfiguration).toHaveBeenCalledTimes(1)
  })

  it('keeps a successful disabled setting for the application lifetime', () => {
    api.getUsageConfiguration.mockReturnValue(of({ enabled: false }))
    const configuration = TestBed.inject(UsageConfigurationService)
    expect(configuration.state()).toBe('disabled')
    expect(configuration.enabled()).toBe(false)
    configuration.retry()
    expect(api.getUsageConfiguration).toHaveBeenCalledTimes(1)
  })

  it.each([null, {}, { enabled: 'false' }])('treats malformed configuration %p as a retriable error', (response) => {
    api.getUsageConfiguration.mockReturnValue(of(response))
    const configuration = TestBed.inject(UsageConfigurationService)
    expect(configuration.state()).toBe('error')
    expect(configuration.enabled()).toBe(false)
    api.getUsageConfiguration.mockReturnValue(of({ enabled: false }))
    configuration.retry()
    expect(configuration.state()).toBe('disabled')
  })

  it('preserves configuration errors and shares an explicit retry until it succeeds', () => {
    const retried = new Subject<{ enabled: boolean }>()
    api.getUsageConfiguration.mockReturnValueOnce(throwError(() => new Error('offline'))).mockReturnValue(retried)
    const configuration = TestBed.inject(UsageConfigurationService)
    expect(configuration.state()).toBe('error')
    expect(configuration.enabled()).toBe(false)
    expect(TestBed.inject(UsageConfigurationService).state()).toBe('error')
    expect(api.getUsageConfiguration).toHaveBeenCalledTimes(1)
    configuration.retry()
    configuration.retry()
    expect(configuration.state()).toBe('loading')
    expect(api.getUsageConfiguration).toHaveBeenCalledTimes(2)
    retried.next({ enabled: false })
    retried.complete()
    expect(configuration.state()).toBe('disabled')
  })

  it('keeps a failed retry available for another explicit attempt', () => {
    api.getUsageConfiguration.mockReturnValue(throwError(() => new Error('offline')))
    const configuration = TestBed.inject(UsageConfigurationService)
    configuration.retry()
    expect(configuration.state()).toBe('error')
    api.getUsageConfiguration.mockReturnValue(of({ enabled: true }))
    configuration.retry()
    expect(configuration.enabled()).toBe(true)
    expect(api.getUsageConfiguration).toHaveBeenCalledTimes(3)
  })
})

describe('UsageConfigurationService API configuration', () => {
  it('uses the generated client with the configured API base path', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Configuration, useValue: new Configuration({ basePath: 'https://agentos.example.test' }) },
      ],
    })
    const configuration = TestBed.inject(UsageConfigurationService)
    const http = TestBed.inject(HttpTestingController)
    const request = http.expectOne('https://agentos.example.test/api/usage-configuration')
    expect(request.request.method).toBe('GET')
    request.flush({ enabled: false })
    expect(configuration.state()).toBe('disabled')
    http.verify()
  })
})
