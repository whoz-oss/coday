import { OpenaiClient } from './openai.client'
import { AiProviderConfig, Interactor } from '../model'
import { CodayLogger } from '../service/coday-logger'

export class GoogleClient extends OpenaiClient {
  models = [
    {
      name: 'gemini-3-pro-preview',
      contextWindow: 1048576,
      alias: 'BIG',
      temperature: 0.8,
      maxOutputTokens: 65536,
      price: {
        inputMTokens: 2.0,
        cacheRead: 0.2,
        outputMTokens: 12.0,
      },
    },
    {
      name: 'gemini-2.5-flash',
      alias: 'SMALL',
      contextWindow: 1048576,
      temperature: 0.8,
      maxOutputTokens: 65536,
      price: {
        inputMTokens: 0.3,
        cacheRead: 0.03,
        outputMTokens: 2.5,
      },
    },
  ]

  constructor(interactor: Interactor, aiProviderConfig: AiProviderConfig, logger: CodayLogger) {
    const config = {
      ...aiProviderConfig,
      url: aiProviderConfig.url || 'https://generativelanguage.googleapis.com/v1beta/openai/',
    }
    super(interactor, config, logger)
  }
}
