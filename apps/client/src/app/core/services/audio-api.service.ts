import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { ProjectStateService } from './project-state.service'
import { ThreadStateService } from './thread-state.service'

/**
 * Service for audio transcription and voice message API calls.
 */
@Injectable({
  providedIn: 'root',
})
export class AudioApiService {
  private readonly http = inject(HttpClient)
  private readonly projectState = inject(ProjectStateService)
  private readonly threadState = inject(ThreadStateService)

  /**
   * Transcribe audio using OpenAI Whisper via the project-level endpoint.
   * No thread required — only an OpenAI API key configured server-side.
   *
   * @param audio Base64-encoded audio data
   * @param mimeType Audio MIME type (e.g. 'audio/webm')
   * @param language Optional ISO 639-1 language code (e.g. 'en')
   */
  transcribeAudio(audio: string, mimeType: string, language?: string): Observable<{ success: boolean; text: string }> {
    const projectName = this.projectState.getSelectedProjectIdOrThrow()

    return this.http.post<{ success: boolean; text: string }>(`/api/projects/${projectName}/transcribe`, {
      audio,
      mimeType,
      ...(language ? { language } : {}),
    })
  }

  /**
   * Send a voice message to the active thread.
   * The audio is transcribed with Whisper server-side and stored as an AudioContent message.
   * Requires an active thread and a running Coday instance.
   *
   * @param audio Base64-encoded audio data
   * @param mimeType Audio MIME type (e.g. 'audio/webm')
   * @param language Optional ISO 639-1 language code (e.g. 'en')
   */
  sendVoiceMessage(
    audio: string,
    mimeType: string,
    language?: string
  ): Observable<{ success: boolean; transcription: string }> {
    const projectName = this.projectState.getSelectedProjectIdOrThrow()
    const threadId = this.threadState.getSelectedThreadIdOrThrow()

    return this.http.post<{ success: boolean; transcription: string }>(
      `/api/projects/${projectName}/threads/${threadId}/voice-message`,
      { audio, mimeType, ...(language ? { language } : {}) }
    )
  }
}
