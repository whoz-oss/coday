import { Migration } from './data-migration'

const defaultProviders = ['anthropic', 'openai', 'google']
export const aiPropertyToAi: Migration = {
  version: 1,
  description: 'Migrate aiProviders to ai property',
  migrate: (config: any) => {
    const { aiProviders, ...rest } = config

    // Initialize ai array if it doesn't exist
    const ai: any[] = []

    // Handle case where aiProviders is undefined
    if (aiProviders) {
      Object.keys(aiProviders).forEach((key) => {
        const conf: any = {
          name: key,
          apiKey: aiProviders[key]?.apiKey,
        }
        if (!defaultProviders.includes(key)) {
          conf.type = 'openai'
          conf.url = aiProviders[key]?.url
          if (aiProviders[key]?.model) {
            const model: any = {
              name: aiProviders[key].model,
              contextWindow: aiProviders[key]?.contextWindow ?? 64000,
            }
            conf.models = [model]
          }
        }
        ai.push(conf)
      })
    }

    return {
      ...rest,
      aiProviders, // keep aiProviders for multi-version compatibility for now
      ai,
    }
  },
}
