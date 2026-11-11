import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Audio as AudioContent, ExchangeControllerService } from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
import { AudioMessageComponent } from './audio-message.component'

const audio: AudioContent = { type: 'Audio', path: 'voice-messages/v.webm', mimeType: 'audio/webm', durationMs: 65_000 }

describe('AudioMessageComponent', () => {
  let exchange: { downloadCaseFileExchange: jest.Mock }
  let createObjectURL: jest.Mock
  let revokeObjectURL: jest.Mock
  const originalCreate = URL.createObjectURL
  const originalRevoke = URL.revokeObjectURL

  function makeComponent(content: AudioContent = audio): ComponentRef<AudioMessageComponent> {
    const ref = createComponent(AudioMessageComponent, { environmentInjector: TestBed.inject(EnvironmentInjector) })
    TestBed.inject(ApplicationRef).attachView(ref.hostView)
    ref.setInput('caseId', 'c-1')
    ref.setInput('audio', content)
    ref.changeDetectorRef.detectChanges()
    return ref
  }

  const host = (ref: ComponentRef<AudioMessageComponent>) => ref.location.nativeElement as HTMLElement
  const playButton = (ref: ComponentRef<AudioMessageComponent>) =>
    host(ref).querySelector('button') as HTMLButtonElement

  beforeEach(() => {
    exchange = { downloadCaseFileExchange: jest.fn().mockReturnValue(of(new Blob(['bytes'], { type: 'video/webm' }))) }
    createObjectURL = jest.fn().mockReturnValue('blob:voice-1')
    revokeObjectURL = jest.fn()
    URL.createObjectURL = createObjectURL
    URL.revokeObjectURL = revokeObjectURL
    TestBed.configureTestingModule({ providers: [{ provide: ExchangeControllerService, useValue: exchange }] })
  })

  afterEach(() => {
    // Destroy the components (which revoke their URL) while the mock is still installed, then restore.
    TestBed.resetTestingModule()
    URL.createObjectURL = originalCreate
    URL.revokeObjectURL = originalRevoke
  })

  it('downloads nothing until the play button is clicked', () => {
    const ref = makeComponent()
    expect(exchange.downloadCaseFileExchange).not.toHaveBeenCalled()
    expect(host(ref).querySelector('audio')).toBeNull()
    expect(host(ref).textContent).toContain('Voice message')
    expect(host(ref).textContent).toContain('01:05')
  })

  it('fetches the recording of the case through the exchange download endpoint on play', () => {
    const ref = makeComponent()
    playButton(ref).click()
    expect(exchange.downloadCaseFileExchange).toHaveBeenCalledWith('c-1', 'voice-messages/v.webm')
  })

  it('builds the object URL from a NEW blob typed with the message MIME type, not the response one', () => {
    const ref = makeComponent()
    playButton(ref).click()
    ref.changeDetectorRef.detectChanges()

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    const blob = createObjectURL.mock.calls[0]![0] as Blob
    expect(blob.type).toBe('audio/webm') // the server said video/webm
    const player = host(ref).querySelector('audio')
    expect(player).not.toBeNull()
    expect(player!.getAttribute('src')).toBe('blob:voice-1')
  })

  it('uses the MIME type of each message (mp4 recorded by Safari)', () => {
    const ref = makeComponent({ ...audio, path: 'voice-messages/v.m4a', mimeType: 'audio/mp4' })
    playButton(ref).click()
    expect((createObjectURL.mock.calls[0]![0] as Blob).type).toBe('audio/mp4')
  })

  it('shows a loading state, and does not fetch twice, while the download is in flight', () => {
    const pending = new Subject<Blob>()
    exchange.downloadCaseFileExchange.mockReturnValue(pending)
    const ref = makeComponent()
    playButton(ref).click()
    ref.changeDetectorRef.detectChanges()
    playButton(ref).click()

    expect(exchange.downloadCaseFileExchange).toHaveBeenCalledTimes(1)
    expect(playButton(ref).disabled).toBe(true)
  })

  it('shows an unavailable note and keeps no player when the file cannot be loaded', () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined)
    exchange.downloadCaseFileExchange.mockReturnValue(throwError(() => ({ status: 404 })))
    const ref = makeComponent()
    playButton(ref).click()
    ref.changeDetectorRef.detectChanges()

    expect(host(ref).textContent).toContain('Audio unavailable')
    expect(host(ref).querySelector('audio')).toBeNull()
    expect(createObjectURL).not.toHaveBeenCalled()
    warn.mockRestore()
  })

  it('revokes the object URL when destroyed', () => {
    const ref = makeComponent()
    playButton(ref).click()
    ref.destroy()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:voice-1')
  })

  it('revokes nothing when it was destroyed before the recording was played', () => {
    const ref = makeComponent()
    ref.destroy()
    expect(revokeObjectURL).not.toHaveBeenCalled()
  })
})
