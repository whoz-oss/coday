import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core'
import { VoiceMessageControllerService } from '@whoz-oss/agentos-api-client'
import { firstValueFrom } from 'rxjs'
import { VOICE_MEDIA_PORT } from './voice-media.port'
import {
  mapMediaError,
  mapPostError,
  MAX_RECORDING_BYTES,
  MAX_RECORDING_MS,
  pickRecordingMimeType,
  recordingExtension,
  VOICE_ERRORS,
} from './voice-recorder.utils'

/** The states of a recording, in the order of its life. Compare to these, never to the bare strings. */
export const VOICE_RECORDER_STATE = {
  IDLE: 'idle',
  STARTING: 'starting',
  RECORDING: 'recording',
  SENDING: 'sending',
  ERROR: 'error',
} as const

export type VoiceRecorderState = (typeof VOICE_RECORDER_STATE)[keyof typeof VOICE_RECORDER_STATE]

/**
 * The case a recording is posted to: its id, or a function resolving it when the recording is sent. The function
 * serves a composer whose case does not exist yet while recording (the first-message composer creates it on send).
 */
export type VoiceRecorderTarget = string | (() => Promise<string>)

/** What the host does around the posting of a recording (the case goes running like on a normal send). */
export interface VoiceRecorderHandlers {
  /** The recording was stopped and is being uploaded and transcribed. */
  onSending?: () => void
  /** The message is posted: the agent takes over. */
  onSent: () => void
  /** Nothing was posted (error): undo what [onSending] did. */
  onSendFailed?: () => void
}

/** MediaRecorder hands over data in chunks of this length, so the size can be watched while recording. */
const TIMESLICE_MS = 1000
const TICK_MS = 250

function stopTracks(stream: MediaStream | null): void {
  stream?.getTracks().forEach((track) => track.stop())
}

/**
 * VoiceRecorderService: records a voice message from the microphone and posts it to a case.
 * Provided by the composer component (NOT root): its lifecycle follows its host, and the
 * microphone is released when the host is destroyed.
 *
 * One recording at a time: idle -> starting (waiting for the microphone permission) -> recording
 * -> sending -> idle, or -> error from anywhere (the message is in [error], the next [start]
 * clears it). Sending is immediate, there is no review step.
 *
 * The microphone is released on every way out: send, cancel, error, [reset] and destroy.
 * A [generation] counter makes a stale async step (permission granted after a cancel, response of
 * a case that was left) a no-op instead of a write on the wrong state.
 *
 * When the target is a resolver, it runs once the recording is stopped, the microphone released and the
 * recording known to be non-empty, so nothing is created for a recording that is not posted. A resolver that
 * fails is an error like a failed post: nothing is posted, [VoiceRecorderHandlers.onSendFailed] runs, and the
 * message shown is the one of the thrown Error (a resolver knows better than an HTTP status mapping what failed),
 * or the generic send failure for anything else.
 */
@Injectable()
export class VoiceRecorderService implements OnDestroy {
  private readonly media = inject(VOICE_MEDIA_PORT)
  private readonly api = inject(VoiceMessageControllerService)

  readonly state = signal<VoiceRecorderState>(VOICE_RECORDER_STATE.IDLE)
  readonly elapsedMs = signal(0)
  readonly error = signal<string | null>(null)
  /** The recording bar is shown. */
  readonly isActive = computed(
    () => this.state() === VOICE_RECORDER_STATE.RECORDING || this.state() === VOICE_RECORDER_STATE.SENDING
  )
  /** The microphone is on and the recording not yet sent. */
  readonly isRecording = computed(() => this.state() === VOICE_RECORDER_STATE.RECORDING)
  /** Something is under way: the composer must not start another recording. */
  readonly isBusy = computed(
    () => this.state() !== VOICE_RECORDER_STATE.IDLE && this.state() !== VOICE_RECORDER_STATE.ERROR
  )

  private generation = 0
  private stream: MediaStream | null = null
  private recorder: MediaRecorder | null = null
  private chunks: Blob[] = []
  private bytes = 0
  private mimeType = ''
  private startedAt = 0
  private timer: ReturnType<typeof setInterval> | null = null
  private target: VoiceRecorderTarget = ''
  private handlers: VoiceRecorderHandlers = { onSent: () => undefined }
  /** Unblocks a [finishRecording] still waiting for the recorder's `stop` when [reset] cuts in. */
  private abortStop: (() => void) | null = null

  /**
   * Asks for the microphone and starts recording for [target]. The [handlers] run around the posting,
   * whether the user sent the recording or the length limit did.
   */
  async start(target: VoiceRecorderTarget, handlers: VoiceRecorderHandlers): Promise<void> {
    if (this.isBusy()) return
    this.error.set(null)
    const mimeType = pickRecordingMimeType((candidate) => this.media.isTypeSupported(candidate))
    if (!mimeType) {
      this.fail(VOICE_ERRORS.unsupported)
      return
    }
    const generation = ++this.generation
    this.state.set(VOICE_RECORDER_STATE.STARTING)

    let stream: MediaStream
    try {
      stream = await this.media.getUserMedia()
    } catch (err) {
      if (generation === this.generation) this.fail(mapMediaError(err))
      return
    }
    // Cancelled or reset while the permission prompt was open: the microphone must not stay on.
    if (generation !== this.generation) {
      stopTracks(stream)
      return
    }

    try {
      const recorder = this.media.createRecorder(stream, mimeType)
      this.chunks = []
      this.bytes = 0
      recorder.ondataavailable = (event) => {
        if (event.data?.size) {
          this.chunks.push(event.data)
          this.bytes += event.data.size
        }
      }
      recorder.onerror = () => {
        if (generation !== this.generation) return
        this.generation++
        this.fail(VOICE_ERRORS.interrupted)
      }
      recorder.start(TIMESLICE_MS)
      this.recorder = recorder
    } catch (err) {
      stopTracks(stream)
      this.fail(mapMediaError(err))
      return
    }

    this.stream = stream
    this.mimeType = mimeType
    this.target = target
    this.handlers = handlers
    this.startedAt = Date.now()
    this.elapsedMs.set(0)
    this.state.set(VOICE_RECORDER_STATE.RECORDING)
    this.timer = setInterval(() => this.tick(), TICK_MS)
  }

  /** Stops the recording and posts it. Resolves true when the server accepted it. */
  async stopAndSend(): Promise<boolean> {
    if (!this.isRecording() || !this.recorder) return false
    const generation = this.generation
    const target = this.target
    const handlers = this.handlers
    this.clearTimer()
    const durationMs = Math.round(Date.now() - this.startedAt)
    this.elapsedMs.set(durationMs)
    this.state.set(VOICE_RECORDER_STATE.SENDING)
    handlers.onSending?.()

    const blob = await this.finishRecording()
    if (generation !== this.generation) return false
    if (blob.size === 0) {
      this.fail(VOICE_ERRORS.empty)
      handlers.onSendFailed?.()
      return false
    }

    // A File, not a bare Blob: the multipart part then carries a filename matching its format.
    const file = new File([blob], `voice.${recordingExtension(this.mimeType)}`, { type: this.mimeType })
    let caseId: string
    try {
      caseId = typeof target === 'string' ? target : await target()
    } catch (err) {
      if (generation === this.generation) {
        this.fail(err instanceof Error && err.message ? err.message : VOICE_ERRORS.sendFailed)
        handlers.onSendFailed?.()
      }
      return false
    }
    // Cancelled or left while the case was being resolved: nothing is posted.
    if (generation !== this.generation) return false
    try {
      // Positional arguments of the generated client: the query-string twins of the two form fields come
      // first (left undefined), then the form fields Spring actually reads: answerToEventId and durationMs.
      await firstValueFrom(this.api.postVoiceMessage(caseId, file, undefined, undefined, undefined, durationMs))
    } catch (err) {
      if (generation === this.generation) {
        this.fail(mapPostError(err))
        handlers.onSendFailed?.()
      }
      return false
    }
    // The case was left while the recording was uploading: it was posted to its case, nothing to show here.
    if (generation !== this.generation) return true
    this.state.set(VOICE_RECORDER_STATE.IDLE)
    this.elapsedMs.set(0)
    handlers.onSent()
    return true
  }

  /** Discards the recording, if any, and releases the microphone. */
  cancel(): void {
    this.reset()
  }

  /** Back to idle with nothing left: cancel, case switch and destroy all end here. */
  reset(): void {
    this.generation++
    this.clearTimer()
    const recorder = this.recorder
    if (recorder) {
      recorder.ondataavailable = null
      recorder.onstop = null
      recorder.onerror = null
      try {
        if (recorder.state !== 'inactive') recorder.stop()
      } catch {
        // Already stopped: nothing to undo.
      }
    }
    this.releaseMedia()
    this.abortStop?.()
    this.chunks = []
    this.bytes = 0
    this.state.set(VOICE_RECORDER_STATE.IDLE)
    this.elapsedMs.set(0)
    this.error.set(null)
  }

  ngOnDestroy(): void {
    this.reset()
  }

  private tick(): void {
    const elapsed = Date.now() - this.startedAt
    this.elapsedMs.set(elapsed)
    if (elapsed >= MAX_RECORDING_MS || this.bytes >= MAX_RECORDING_BYTES) {
      void this.stopAndSend()
    }
  }

  /** Stops the recorder and resolves with everything it recorded, the microphone released. */
  private finishRecording(): Promise<Blob> {
    const recorder = this.recorder
    const mimeType = this.mimeType
    return new Promise((resolve) => {
      const done = () => {
        this.abortStop = null
        this.releaseMedia()
        resolve(new Blob(this.chunks, { type: mimeType }))
      }
      if (!recorder || recorder.state === 'inactive') {
        done()
        return
      }
      // The last chunk arrives just before `stop`.
      recorder.onstop = done
      this.abortStop = () => {
        this.abortStop = null
        resolve(new Blob([], { type: mimeType }))
      }
      recorder.stop()
    })
  }

  private releaseMedia(): void {
    stopTracks(this.stream)
    this.stream = null
    if (this.recorder) {
      this.recorder.ondataavailable = null
      this.recorder.onstop = null
      this.recorder.onerror = null
    }
    this.recorder = null
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearInterval(this.timer)
      this.timer = null
    }
  }

  private fail(message: string): void {
    this.clearTimer()
    this.releaseMedia()
    this.chunks = []
    this.bytes = 0
    this.elapsedMs.set(0)
    this.state.set(VOICE_RECORDER_STATE.ERROR)
    this.error.set(message)
  }
}
