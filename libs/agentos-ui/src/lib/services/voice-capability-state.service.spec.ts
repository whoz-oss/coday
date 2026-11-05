import { TestBed } from '@angular/core/testing'
import { VoiceMessageControllerService } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { VoiceCapabilityStateService } from './voice-capability-state.service'

describe('VoiceCapabilityStateService', () => {
  let api: { capabilityVoiceMessage: jest.Mock }

  function create(): VoiceCapabilityStateService {
    TestBed.configureTestingModule({
      providers: [{ provide: VoiceMessageControllerService, useValue: api }],
    })
    return TestBed.inject(VoiceCapabilityStateService)
  }

  beforeEach(() => {
    api = { capabilityVoiceMessage: jest.fn().mockReturnValue(of({ available: true })) }
  })

  afterEach(() => TestBed.resetTestingModule())

  it('is unavailable until the capability has been loaded', () => {
    const service = create()
    expect(service.available()).toBe(false)
    expect(api.capabilityVoiceMessage).not.toHaveBeenCalled()
  })

  it('reports available when the server says so', () => {
    const service = create()
    service.load()
    expect(service.available()).toBe(true)
  })

  it('reports unavailable when the server says so', () => {
    api.capabilityVoiceMessage.mockReturnValue(of({ available: false }))
    const service = create()
    service.load()
    expect(service.available()).toBe(false)
  })

  it('asks only once per session, however often it is loaded', () => {
    const service = create()
    service.load()
    service.load()
    service.load()
    expect(api.capabilityVoiceMessage).toHaveBeenCalledTimes(1)
  })

  it('keeps the cached answer across consumers (root singleton)', () => {
    const first = create()
    first.load()
    const second = TestBed.inject(VoiceCapabilityStateService)
    second.load()
    expect(second.available()).toBe(true)
    expect(api.capabilityVoiceMessage).toHaveBeenCalledTimes(1)
  })

  it('treats a failure as unavailable', () => {
    api.capabilityVoiceMessage.mockReturnValue(throwError(() => ({ status: 500 })))
    const service = create()
    service.load()
    expect(service.available()).toBe(false)
  })

  it('does not retry after a failure within the session', () => {
    api.capabilityVoiceMessage.mockReturnValue(throwError(() => ({ status: 500 })))
    const service = create()
    service.load()
    service.load()
    expect(api.capabilityVoiceMessage).toHaveBeenCalledTimes(1)
  })

  it('treats anything but an explicit true as unavailable', () => {
    api.capabilityVoiceMessage.mockReturnValue(of({}))
    const service = create()
    service.load()
    expect(service.available()).toBe(false)
  })
})
