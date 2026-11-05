import { TestBed } from '@angular/core/testing'
import { VoiceMessageControllerService } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { VOICE_MEDIA_PORT, VoiceMediaPort } from './voice-media.port'
import { VoiceRecorderHandlers, VoiceRecorderService } from './voice-recorder.service'
import { MAX_RECORDING_MS, VOICE_ERRORS } from './voice-recorder.utils'

/** MediaRecorder / getUserMedia do not exist in jsdom: these doubles stand in for them. */
class FakeRecorder {
  state: 'inactive' | 'recording' = 'inactive'
  ondataavailable: ((event: { data: Blob }) => void) | null = null
  onstop: (() => void) | null = null
  onerror: (() => void) | null = null
  data = new Blob(['audio-bytes'])
  constructor(readonly mimeType: string) {}
  start = jest.fn(() => {
    this.state = 'recording'
  })
  stop = jest.fn(() => {
    this.state = 'inactive'
    this.ondataavailable?.({ data: this.data })
    this.onstop?.()
  })
}

function fakeStream() {
  const track = { stop: jest.fn() }
  return { stream: { getTracks: () => [track] } as unknown as MediaStream, track }
}

const flush = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve()
}

describe('VoiceRecorderService', () => {
  let media: { [K in keyof VoiceMediaPort]: jest.Mock }
  let api: { postVoiceMessage: jest.Mock }
  let recorder: FakeRecorder
  let mic: ReturnType<typeof fakeStream>
  let service: VoiceRecorderService
  let handlers: Required<VoiceRecorderHandlers> & { onSent: jest.Mock }

  beforeEach(() => {
    jest.useFakeTimers()
    mic = fakeStream()
    media = {
      isTypeSupported: jest.fn().mockReturnValue(true),
      getUserMedia: jest.fn().mockImplementation(() => Promise.resolve(mic.stream)),
      createRecorder: jest.fn().mockImplementation((_stream: MediaStream, mimeType: string) => {
        recorder = new FakeRecorder(mimeType)
        return recorder as unknown as MediaRecorder
      }),
    }
    api = { postVoiceMessage: jest.fn().mockReturnValue(of({ transcript: 'hello', path: 'voice-messages/v.webm' })) }
    handlers = { onSending: jest.fn(), onSent: jest.fn(), onSendFailed: jest.fn() }
    TestBed.configureTestingModule({
      providers: [
        VoiceRecorderService,
        { provide: VOICE_MEDIA_PORT, useValue: media },
        { provide: VoiceMessageControllerService, useValue: api },
      ],
    })
    service = TestBed.inject(VoiceRecorderService)
  })

  afterEach(() => {
    jest.useRealTimers()
    TestBed.resetTestingModule()
  })

  describe('format selection', () => {
    it('records in the first supported format and names the file after it', async () => {
      media.isTypeSupported.mockImplementation((m: string) => m === 'audio/mp4')
      await service.start('c-1', handlers)
      expect(media.createRecorder).toHaveBeenCalledWith(mic.stream, 'audio/mp4')

      await service.stopAndSend()

      const file = api.postVoiceMessage.mock.calls[0]![1] as File
      expect(file.type).toBe('audio/mp4')
      expect(file.name).toBe('voice.m4a')
    })

    it('prefers webm/opus when available', async () => {
      await service.start('c-1', handlers)
      expect(media.createRecorder).toHaveBeenCalledWith(mic.stream, 'audio/webm;codecs=opus')
    })

    it('fails without asking for the microphone when no format is supported', async () => {
      media.isTypeSupported.mockReturnValue(false)
      await service.start('c-1', handlers)
      expect(media.getUserMedia).not.toHaveBeenCalled()
      expect(service.state()).toBe('error')
      expect(service.error()).toBe(VOICE_ERRORS.unsupported)
    })
  })

  describe('recording', () => {
    it('starts recording and counts the elapsed time', async () => {
      await service.start('c-1', handlers)
      expect(service.state()).toBe('recording')
      expect(service.isActive()).toBe(true)
      expect(recorder.start).toHaveBeenCalled()

      jest.advanceTimersByTime(3_000)

      expect(service.elapsedMs()).toBeGreaterThanOrEqual(3_000)
    })

    it('ignores a second start while recording', async () => {
      await service.start('c-1', handlers)
      await service.start('c-1', handlers)
      expect(media.getUserMedia).toHaveBeenCalledTimes(1)
    })

    it('maps a denied permission and leaves nothing running', async () => {
      media.getUserMedia.mockRejectedValue({ name: 'NotAllowedError' })
      await service.start('c-1', handlers)
      expect(service.state()).toBe('error')
      expect(service.error()).toBe(VOICE_ERRORS.denied)
      expect(media.createRecorder).not.toHaveBeenCalled()
    })

    it('maps a missing microphone', async () => {
      media.getUserMedia.mockRejectedValue({ name: 'NotFoundError' })
      await service.start('c-1', handlers)
      expect(service.error()).toBe(VOICE_ERRORS.noMicrophone)
    })

    it('a new start clears the previous error', async () => {
      media.getUserMedia.mockRejectedValueOnce({ name: 'NotAllowedError' })
      await service.start('c-1', handlers)
      expect(service.error()).not.toBeNull()

      await service.start('c-1', handlers)

      expect(service.error()).toBeNull()
      expect(service.state()).toBe('recording')
    })

    it('releases the microphone when the recorder cannot be created', async () => {
      media.createRecorder.mockImplementation(() => {
        throw { name: 'NotSupportedError' }
      })
      await service.start('c-1', handlers)
      expect(mic.track.stop).toHaveBeenCalled()
      expect(service.state()).toBe('error')
    })

    it('a recorder error stops everything and shows an error', async () => {
      await service.start('c-1', handlers)
      recorder.onerror?.()
      expect(mic.track.stop).toHaveBeenCalled()
      expect(service.state()).toBe('error')
      expect(service.error()).toBe(VOICE_ERRORS.interrupted)
    })
  })

  describe('permission prompt race', () => {
    it('releases the microphone granted after a cancel', async () => {
      let grant!: (stream: MediaStream) => void
      media.getUserMedia.mockReturnValue(new Promise<MediaStream>((resolve) => (grant = resolve)))
      const started = service.start('c-1', handlers)
      expect(service.state()).toBe('starting')

      service.cancel()
      grant(mic.stream)
      await started

      expect(mic.track.stop).toHaveBeenCalled()
      expect(media.createRecorder).not.toHaveBeenCalled()
      expect(service.state()).toBe('idle')
    })
  })

  describe('sending', () => {
    it('stops the recorder, posts the audio with its duration as a FORM field and releases the microphone', async () => {
      await service.start('case-9', handlers)
      jest.advanceTimersByTime(4_000)

      const sent = await service.stopAndSend()

      expect(sent).toBe(true)
      expect(recorder.stop).toHaveBeenCalled()
      expect(mic.track.stop).toHaveBeenCalled()
      // (caseId, audio, answerToEventId [query], durationMs [query], answerToEventId2 [form], durationMs2 [form])
      expect(api.postVoiceMessage).toHaveBeenCalledTimes(1)
      const args = api.postVoiceMessage.mock.calls[0]!
      expect(args[0]).toBe('case-9')
      expect(args[1]).toBeInstanceOf(File)
      expect(args[2]).toBeUndefined()
      expect(args[3]).toBeUndefined()
      expect(args[4]).toBeUndefined() // no answerToEventId
      expect(args[5]).toBeGreaterThanOrEqual(4_000) // durationMs2, measured client side
      expect(service.state()).toBe('idle')
    })

    it('tells the host before and after the post', async () => {
      await service.start('c-1', handlers)
      await service.stopAndSend()
      expect(handlers.onSending).toHaveBeenCalledTimes(1)
      expect(handlers.onSent).toHaveBeenCalledTimes(1)
      expect(handlers.onSendFailed).not.toHaveBeenCalled()
    })

    it('shows the sending state while the post is in flight', async () => {
      let respond!: (v: unknown) => void
      api.postVoiceMessage.mockReturnValue(
        new (jest.requireActual('rxjs').Observable)(
          (subscriber: { next: (v: unknown) => void; complete: () => void }) => {
            respond = (v) => {
              subscriber.next(v)
              subscriber.complete()
            }
          }
        )
      )
      await service.start('c-1', handlers)

      const sending = service.stopAndSend()
      await flush()

      expect(service.state()).toBe('sending')
      expect(service.isActive()).toBe(true)
      expect(service.isBusy()).toBe(true)
      respond({ transcript: 't', path: 'p' })
      await sending
      expect(service.state()).toBe('idle')
    })

    it('does nothing when nothing is being recorded', async () => {
      expect(await service.stopAndSend()).toBe(false)
      expect(api.postVoiceMessage).not.toHaveBeenCalled()
    })

    it('an empty recording is an error and posts nothing', async () => {
      await service.start('c-1', handlers)
      recorder.data = new Blob([])

      const sent = await service.stopAndSend()

      expect(sent).toBe(false)
      expect(api.postVoiceMessage).not.toHaveBeenCalled()
      expect(service.error()).toBe(VOICE_ERRORS.empty)
      expect(handlers.onSendFailed).toHaveBeenCalled()
      expect(mic.track.stop).toHaveBeenCalled()
    })

    it.each([
      [409, VOICE_ERRORS.unavailable],
      [413, VOICE_ERRORS.tooLarge],
      [422, VOICE_ERRORS.noSpeech],
      [502, VOICE_ERRORS.transcription],
      [0, VOICE_ERRORS.network],
      [500, VOICE_ERRORS.sendFailed],
    ])('maps an HTTP %s to a message and leaves the composer unchanged', async (status, message) => {
      api.postVoiceMessage.mockReturnValue(throwError(() => ({ status })))
      await service.start('c-1', handlers)

      const sent = await service.stopAndSend()

      expect(sent).toBe(false)
      expect(service.state()).toBe('error')
      expect(service.error()).toBe(message)
      expect(handlers.onSent).not.toHaveBeenCalled()
      expect(handlers.onSendFailed).toHaveBeenCalledTimes(1)
      expect(mic.track.stop).toHaveBeenCalled()
    })

    it('a case switch while uploading does not touch the new case state', async () => {
      let respond!: (v: unknown) => void
      api.postVoiceMessage.mockReturnValue(
        new (jest.requireActual('rxjs').Observable)(
          (subscriber: { next: (v: unknown) => void; complete: () => void }) => {
            respond = (v) => {
              subscriber.next(v)
              subscriber.complete()
            }
          }
        )
      )
      await service.start('c-1', handlers)
      const sending = service.stopAndSend()
      await flush()

      service.reset() // reinitialise() on a case switch
      respond({ transcript: 't', path: 'p' })
      await sending

      expect(handlers.onSent).not.toHaveBeenCalled()
      expect(service.state()).toBe('idle')
    })
  })

  describe('target resolved at send time', () => {
    it('does not resolve the target while recording, only once the recording is sent', async () => {
      const resolver = jest.fn().mockResolvedValue('case-new')
      await service.start(resolver, handlers)
      jest.advanceTimersByTime(2_000)
      expect(resolver).not.toHaveBeenCalled()

      const sent = await service.stopAndSend()

      expect(sent).toBe(true)
      expect(resolver).toHaveBeenCalledTimes(1)
      expect(api.postVoiceMessage).toHaveBeenCalledTimes(1)
      expect(api.postVoiceMessage.mock.calls[0]![0]).toBe('case-new')
      expect(handlers.onSent).toHaveBeenCalledTimes(1)
    })

    it('resolves after the microphone is released, and the host sees the sending state first', async () => {
      const order: string[] = []
      handlers.onSending.mockImplementation(() => order.push('sending'))
      mic.track.stop.mockImplementation(() => order.push('mic-released'))
      const resolver = jest.fn().mockImplementation(async () => {
        order.push('resolve')
        return 'case-new'
      })
      await service.start(resolver, handlers)

      await service.stopAndSend()

      expect(order).toEqual(['sending', 'mic-released', 'resolve'])
    })

    it('a resolver that fails is an error: nothing is posted, the microphone is released, the host is told', async () => {
      const resolver = jest.fn().mockRejectedValue(new Error('Could not create the case.'))
      await service.start(resolver, handlers)

      const sent = await service.stopAndSend()

      expect(sent).toBe(false)
      expect(api.postVoiceMessage).not.toHaveBeenCalled()
      expect(service.state()).toBe('error')
      expect(service.error()).toBe('Could not create the case.')
      expect(mic.track.stop).toHaveBeenCalled()
      expect(handlers.onSendFailed).toHaveBeenCalledTimes(1)
      expect(handlers.onSent).not.toHaveBeenCalled()
    })

    it('falls back to the generic message when the resolver fails without an error message', async () => {
      await service.start(() => Promise.reject({ status: 500 }), handlers)

      await service.stopAndSend()

      expect(service.error()).toBe(VOICE_ERRORS.sendFailed)
      expect(api.postVoiceMessage).not.toHaveBeenCalled()
    })

    it('does not resolve the target of a recording that is cancelled', async () => {
      const resolver = jest.fn().mockResolvedValue('case-new')
      await service.start(resolver, handlers)

      service.cancel()

      expect(resolver).not.toHaveBeenCalled()
      expect(api.postVoiceMessage).not.toHaveBeenCalled()
      expect(mic.track.stop).toHaveBeenCalled()
    })

    it('does not resolve the target of an empty recording', async () => {
      const resolver = jest.fn().mockResolvedValue('case-new')
      await service.start(resolver, handlers)
      recorder.data = new Blob([])

      await service.stopAndSend()

      expect(resolver).not.toHaveBeenCalled()
      expect(service.error()).toBe(VOICE_ERRORS.empty)
    })

    it('posts nothing when the recorder is reset while the target is being resolved', async () => {
      let resolve!: (caseId: string) => void
      await service.start(() => new Promise<string>((r) => (resolve = r)), handlers)
      const sending = service.stopAndSend()
      await flush()

      service.reset()
      resolve('case-new')
      const sent = await sending

      expect(sent).toBe(false)
      expect(api.postVoiceMessage).not.toHaveBeenCalled()
      expect(handlers.onSent).not.toHaveBeenCalled()
      expect(service.state()).toBe('idle')
    })

    it('sends by itself at the length limit through a resolved target too', async () => {
      const resolver = jest.fn().mockResolvedValue('case-new')
      await service.start(resolver, handlers)

      jest.advanceTimersByTime(MAX_RECORDING_MS + 1_000)
      await flush()

      expect(resolver).toHaveBeenCalledTimes(1)
      expect(api.postVoiceMessage.mock.calls[0]![0]).toBe('case-new')
    })
  })

  describe('cancel and teardown', () => {
    it('cancel discards the recording, releases the microphone and posts nothing', async () => {
      await service.start('c-1', handlers)

      service.cancel()

      expect(mic.track.stop).toHaveBeenCalled()
      expect(recorder.stop).toHaveBeenCalled()
      expect(api.postVoiceMessage).not.toHaveBeenCalled()
      expect(service.state()).toBe('idle')
      expect(service.elapsedMs()).toBe(0)
    })

    it('destroying the host releases the microphone', async () => {
      await service.start('c-1', handlers)
      service.ngOnDestroy()
      expect(mic.track.stop).toHaveBeenCalled()
      expect(service.state()).toBe('idle')
    })

    it('stops counting after a cancel', async () => {
      await service.start('c-1', handlers)
      service.cancel()
      jest.advanceTimersByTime(5_000)
      expect(service.elapsedMs()).toBe(0)
    })
  })

  describe('maximum duration', () => {
    it('sends by itself when the length limit is reached', async () => {
      await service.start('c-1', handlers)

      jest.advanceTimersByTime(MAX_RECORDING_MS + 1_000)
      await flush()

      expect(api.postVoiceMessage).toHaveBeenCalledTimes(1)
      expect(handlers.onSent).toHaveBeenCalledTimes(1)
      expect(mic.track.stop).toHaveBeenCalled()
      expect(service.state()).toBe('idle')
    })

    it('does not send before the limit', async () => {
      await service.start('c-1', handlers)
      jest.advanceTimersByTime(MAX_RECORDING_MS - 5_000)
      await flush()
      expect(api.postVoiceMessage).not.toHaveBeenCalled()
      expect(service.state()).toBe('recording')
    })

    it('sends by itself when the recording nears the server size limit', async () => {
      await service.start('c-1', handlers)
      recorder.ondataavailable?.({ data: new Blob([new Uint8Array(25 * 1024 * 1024)]) })

      jest.advanceTimersByTime(500)
      await flush()

      expect(api.postVoiceMessage).toHaveBeenCalledTimes(1)
    })
  })
})
