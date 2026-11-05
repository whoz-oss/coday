import { inject, Injectable, signal } from '@angular/core'
import { VoiceMessageControllerService } from '@whoz-oss/agentos-api-client'

/**
 * Whether this AgentOS instance can transcribe voice messages (a platform WHISPER model is configured).
 *
 * Asked once per app session and kept: the answer is an instance-level configuration, not something
 * a user changes while using the app. Anything but an explicit `available: true`, a failed call
 * included, counts as unavailable, so the mic button never shows by default.
 */
@Injectable({ providedIn: 'root' })
export class VoiceCapabilityStateService {
  private readonly api = inject(VoiceMessageControllerService)
  private requested = false
  private readonly _available = signal(false)

  readonly available = this._available.asReadonly()

  /** Fetches the capability the first time it is called; later calls do nothing. */
  load(): void {
    if (this.requested) return
    this.requested = true
    this.api.capabilityVoiceMessage().subscribe({
      next: (response) => this._available.set(response.available === true),
      error: () => this._available.set(false),
    })
  }
}
