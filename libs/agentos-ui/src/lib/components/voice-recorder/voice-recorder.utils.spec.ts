import {
  formatElapsed,
  mapMediaError,
  mapPostError,
  pickRecordingMimeType,
  recordingExtension,
  VOICE_ERRORS,
} from './voice-recorder.utils'

describe('pickRecordingMimeType', () => {
  it('prefers webm/opus, then webm, then ogg/opus, then mp4', () => {
    expect(pickRecordingMimeType(() => true)).toBe('audio/webm;codecs=opus')
    expect(pickRecordingMimeType((m) => m !== 'audio/webm;codecs=opus')).toBe('audio/webm')
    expect(pickRecordingMimeType((m) => m === 'audio/ogg;codecs=opus' || m === 'audio/mp4')).toBe(
      'audio/ogg;codecs=opus'
    )
  })

  it('falls back to mp4, which is all Safari records', () => {
    expect(pickRecordingMimeType((m) => m === 'audio/mp4')).toBe('audio/mp4')
  })

  it('returns undefined when the browser records none of them', () => {
    expect(pickRecordingMimeType(() => false)).toBeUndefined()
  })
})

describe('recordingExtension', () => {
  it('matches the extension to the recorded format, parameters ignored', () => {
    expect(recordingExtension('audio/webm;codecs=opus')).toBe('webm')
    expect(recordingExtension('audio/ogg;codecs=opus')).toBe('ogg')
    expect(recordingExtension('audio/mp4')).toBe('m4a')
  })
})

describe('formatElapsed', () => {
  it('formats mm:ss', () => {
    expect(formatElapsed(0)).toBe('00:00')
    expect(formatElapsed(59_999)).toBe('00:59')
    expect(formatElapsed(61_000)).toBe('01:01')
    expect(formatElapsed(600_000)).toBe('10:00')
  })

  it('never goes negative', () => {
    expect(formatElapsed(-5)).toBe('00:00')
  })
})

describe('mapMediaError', () => {
  it.each([
    ['NotAllowedError', VOICE_ERRORS.denied],
    ['NotFoundError', VOICE_ERRORS.noMicrophone],
    ['NotReadableError', VOICE_ERRORS.microphoneBusy],
    ['NotSupportedError', VOICE_ERRORS.insecure],
    ['SomethingElse', VOICE_ERRORS.startFailed],
  ])('%s', (name, message) => {
    expect(mapMediaError({ name })).toBe(message)
  })

  it('copes with a non-error value', () => {
    expect(mapMediaError(null)).toBe(VOICE_ERRORS.startFailed)
  })
})

describe('mapPostError', () => {
  it.each([
    [0, VOICE_ERRORS.network],
    [400, VOICE_ERRORS.rejected],
    [403, VOICE_ERRORS.forbidden],
    [409, VOICE_ERRORS.unavailable],
    [413, VOICE_ERRORS.tooLarge],
    [422, VOICE_ERRORS.noSpeech],
    [502, VOICE_ERRORS.transcription],
    [500, VOICE_ERRORS.sendFailed],
  ])('status %s', (status, message) => {
    expect(mapPostError({ status })).toBe(message)
  })
})
