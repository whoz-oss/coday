/**
 * Pure helpers of the voice message feature (no Angular, no browser API): recording format
 * negotiation, the elapsed-time label and the user-facing error messages.
 */

/** Formats tried in order: the first one the browser can record wins (Safari only does mp4). */
export const RECORDING_MIME_CANDIDATES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4']

/** A recording is sent by itself once it reaches this length. */
export const MAX_RECORDING_MS = 10 * 60 * 1000

/** ...or this size, which keeps it under the server's 25 MB limit whatever the codec bit rate. */
export const MAX_RECORDING_BYTES = 24 * 1024 * 1024

export const VOICE_ERRORS = {
  unsupported: 'Voice recording is not supported by this browser.',
  insecure: 'The microphone is not available here: voice recording needs a secure (HTTPS) connection.',
  denied: 'Microphone access was denied. Allow it in your browser settings to record a voice message.',
  noMicrophone: 'No microphone was found.',
  microphoneBusy: 'The microphone is used by another application.',
  startFailed: 'The recording could not be started.',
  interrupted: 'The recording was interrupted.',
  empty: 'Nothing was recorded.',
  unavailable: 'Voice messages are not available on this instance.',
  tooLarge: 'The recording is too large (25 MB maximum).',
  rejected: 'The recording was empty or in an unsupported format.',
  noSpeech: 'No speech was detected in the recording.',
  transcription: 'The recording could not be transcribed. Try again.',
  forbidden: 'You cannot send messages in this case.',
  network: 'Network error: the voice message was not sent.',
  sendFailed: 'The voice message could not be sent.',
} as const

/** The first candidate [isSupported] accepts, or undefined when the browser can record none of them. */
export function pickRecordingMimeType(isSupported: (mimeType: string) => boolean): string | undefined {
  return RECORDING_MIME_CANDIDATES.find((candidate) => isSupported(candidate))
}

/** File extension matching a recording MIME type, so the uploaded file is named like what it holds. */
export function recordingExtension(mimeType: string): string {
  switch (mimeType.split(';')[0]?.trim().toLowerCase()) {
    case 'audio/ogg':
      return 'ogg'
    case 'audio/mp4':
      return 'm4a'
    default:
      return 'webm'
  }
}

/** `mm:ss`, with minutes growing past 99 rather than wrapping. */
export function formatElapsed(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

/** Message for a failure of getUserMedia / MediaRecorder, by DOMException name. */
export function mapMediaError(err: unknown): string {
  switch ((err as { name?: string } | null)?.name) {
    case 'NotAllowedError':
    case 'SecurityError':
      return VOICE_ERRORS.denied
    case 'NotFoundError':
    case 'OverconstrainedError':
      return VOICE_ERRORS.noMicrophone
    case 'NotReadableError':
    case 'AbortError':
      return VOICE_ERRORS.microphoneBusy
    case 'NotSupportedError':
      return VOICE_ERRORS.insecure
    default:
      return VOICE_ERRORS.startFailed
  }
}

/** Message for a failed POST /voice-messages, by HTTP status (0 = the request never got an answer). */
export function mapPostError(err: unknown): string {
  switch ((err as { status?: number } | null)?.status) {
    case 0:
      return VOICE_ERRORS.network
    case 400:
      return VOICE_ERRORS.rejected
    case 403:
      return VOICE_ERRORS.forbidden
    case 409:
      return VOICE_ERRORS.unavailable
    case 413:
      return VOICE_ERRORS.tooLarge
    case 422:
      return VOICE_ERRORS.noSpeech
    case 502:
      return VOICE_ERRORS.transcription
    default:
      return VOICE_ERRORS.sendFailed
  }
}
