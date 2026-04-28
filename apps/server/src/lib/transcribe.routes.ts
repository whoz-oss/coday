import express from 'express'
import { debugLog } from './log'
import { getParamAsString } from './route-helpers'
import { MAX_FILE_SIZE } from '@coday/model'
import { ConfigServiceRegistry } from '@coday/service'
import { transcribeWithWhisper } from './whisper'

const ALLOWED_AUDIO_TYPES = [
  'audio/webm',
  'audio/mp4',
  'audio/mpeg',
  'audio/mp3',
  'audio/wav',
  'audio/m4a',
  'audio/ogg',
]

/**
 * Register the project-level audio transcription route.
 * This endpoint does NOT require an active thread — it only needs an OpenAI API key
 * from the user's configuration or the OPENAI_API_KEY environment variable.
 *
 * POST /api/projects/:projectName/transcribe
 */
export function registerTranscribeRoutes(
  app: express.Application,
  configRegistry: ConfigServiceRegistry,
  getUsernameFn: (req: express.Request) => string
): void {
  app.post('/api/projects/:projectName/transcribe', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const { audio, mimeType, language } = req.body

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!audio || !mimeType) {
        res.status(400).json({ error: 'Missing required fields: audio, mimeType' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Authentication required' })
        return
      }

      const buffer = Buffer.from(audio, 'base64')

      if (buffer.length > MAX_FILE_SIZE) {
        res.status(400).json({
          error: `Audio too large: ${(buffer.length / 1024 / 1024).toFixed(2)} MB exceeds maximum of ${MAX_FILE_SIZE / 1024 / 1024} MB`,
        })
        return
      }

      if (!ALLOWED_AUDIO_TYPES.includes(mimeType)) {
        res.status(400).json({ error: `Unsupported audio type: ${mimeType}` })
        return
      }

      debugLog(
        'TRANSCRIBE',
        `project: ${projectName}, user: ${username}, mimeType: ${mimeType}, language: ${language || 'auto'}`
      )

      const text = await transcribeWithWhisper(buffer, mimeType, username, configRegistry, language)

      res.status(200).json({ success: true, text })
    } catch (error) {
      console.error('Error transcribing audio:', error)
      const errorMessage = error instanceof Error ? error.message : 'Transcription failed'
      if (errorMessage.includes('OpenAI provider not configured')) {
        res.status(400).json({ error: errorMessage })
      } else {
        res.status(500).json({ error: errorMessage })
      }
    }
  })
}
