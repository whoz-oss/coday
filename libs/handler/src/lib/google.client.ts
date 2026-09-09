import { OpenaiClient } from './openai.client'
import { CodayLogger } from '@coday/model'
import { Interactor } from '@coday/model'
import { AiProviderConfig } from '@coday/model'

export class GoogleClient extends OpenaiClient {
  override models = [
    {
      name: 'gemini-3.1-pro-preview',
      contextWindow: 1000000,
      alias: 'BIGGEST',
      temperature: 0.8,
      maxOutputTokens: 65536,
      price: {
        inputMTokens: 2.0,
        cacheRead: 0.2,
        outputMTokens: 12.0,
      },
    },
    {
      name: 'gemini-3.6-flash',
      alias: 'BIG',
      contextWindow: 1000000,
      temperature: 0.8,
      maxOutputTokens: 65536,
      price: {
        inputMTokens: 1.5,
        cacheRead: 0.15,
        outputMTokens: 7.5,
      },
    },
    {
      name: 'gemini-3.5-flash-lite',
      alias: 'SMALL',
      contextWindow: 1000000,
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
