import { OpenaiClient } from './openai.client'
import { AiProviderConfig, Interactor } from '../model'
import { CodayLogger } from '../service/coday-logger'

export class GoogleClient extends OpenaiClient {
  models = [
    {
      name: 'gemini-2.5-pro',
      contextWindow: 1000000,
      alias: 'BIG',
      price: {
        inputMTokens: 0.175,
        cacheRead: 0.0875,
        outputMTokens: 0.525,
      },
    },
    {
      name: 'gemini-2.5-flash',
      alias: 'SMALL',
      contextWindow: 1000000,
      price: {
        inputMTokens: 0.1,
        cacheRead: 0.025,
        outputMTokens: 0.4,
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
