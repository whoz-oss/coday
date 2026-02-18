import { OpenaiClient } from './openai.client'
import { CodayLogger } from '@coday/model'
import { Interactor } from '@coday/model'
import { AiProviderConfig } from '@coday/model'

export class GoogleClient extends OpenaiClient {
  override models = [
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
      name: 'gemini-3-flash-preview',
      alias: 'SMALL',
      contextWindow: 1048576,
      temperature: 0.8,
      maxOutputTokens: 65536,
      price: {
        inputMTokens: 0.5,
        cacheRead: 0.05,
        outputMTokens: 3.0,
      },
    },
    {
      name: 'gemini-2.5-pro',
      contextWindow: 1048576,
      temperature: 0.8,
      maxOutputTokens: 65536,
      price: {
        inputMTokens: 1.25,
        cacheRead: 0.125,
        outputMTokens: 10.0,
      },
    },
    {
      name: 'gemini-2.5-flash',
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
