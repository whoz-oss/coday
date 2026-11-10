import { InjectionToken } from '@angular/core'

/**
 * The browser APIs the voice recorder needs, behind one seam so the recorder logic can be tested
 * without MediaRecorder or a microphone (neither exists in jsdom).
 */
export interface VoiceMediaPort {
  isTypeSupported(mimeType: string): boolean
  /** Asks for the microphone: resolves with its stream, rejects with a DOMException. */
  getUserMedia(): Promise<MediaStream>
  createRecorder(stream: MediaStream, mimeType: string): MediaRecorder
}

const browserVoiceMedia: VoiceMediaPort = {
  isTypeSupported: (mimeType) => typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(mimeType),
  getUserMedia: () =>
    navigator.mediaDevices?.getUserMedia
      ? navigator.mediaDevices.getUserMedia({ audio: true })
      : // mediaDevices is undefined outside a secure context (plain http on a non-localhost host).
        Promise.reject(new DOMException('No media devices', 'NotSupportedError')),
  createRecorder: (stream, mimeType) => new MediaRecorder(stream, { mimeType }),
}

export const VOICE_MEDIA_PORT = new InjectionToken<VoiceMediaPort>('VOICE_MEDIA_PORT', {
  providedIn: 'root',
  factory: () => browserVoiceMedia,
})
