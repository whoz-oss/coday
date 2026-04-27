import { ConfigServiceRegistry } from '@coday/service'

/**
 * Transcribe audio using OpenAI Whisper.
 * Resolves the OpenAI API key from the OPENAI_API_KEY environment variable first,
 * then falls back to the user's configured OpenAI provider.
 *
 * @param audioBuffer Raw audio data
 * @param mimeType MIME type of the audio (e.g. 'audio/webm')
 * @param username Username for config lookup
 * @param configRegistry Registry to resolve user config
 * @param language Optional ISO 639-1 language code (e.g. 'en')
 * @returns Transcribed text
 */
export async function transcribeWithWhisper(
  audioBuffer: Buffer,
  mimeType: string,
  username: string,
  configRegistry: ConfigServiceRegistry,
  language?: string
): Promise<string> {
  const userService = configRegistry.getUserService(username)
  const aiConfigs = userService.config.ai || []
  const openaiConfig = aiConfigs.find((c) => c.name.toLowerCase() === 'openai')
  const apiKey = process.env['OPENAI_API_KEY'] || openaiConfig?.apiKey
  const baseURL = openaiConfig?.url

  if (!apiKey) {
    throw new Error('OpenAI provider not configured. Whisper transcription requires an OpenAI API key.')
  }

  const OpenAI = (await import('openai')).default
  const openai = new OpenAI({ apiKey, ...(baseURL ? { baseURL } : {}) })

  const { toFile } = await import('openai')
  const ext = mimeType.split('/')[1]?.replace('x-', '') || 'webm'
  const file = await toFile(audioBuffer, `recording.${ext}`, { type: mimeType })

  const transcription = await openai.audio.transcriptions.create({
    model: 'whisper-1',
    file,
    ...(language ? { language } : {}),
  })

  return transcription.text
}
