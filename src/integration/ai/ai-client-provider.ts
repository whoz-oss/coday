import {integrationService} from "../../service/integration.service"
import {OpenaiClient} from "../../handler/openai-client"
import {AiClient, Interactor} from "../../model"
import {GeminiClient} from "../../handler/gemini.client"

export function aiClientProvider(interactor: Interactor): AiClient | undefined {
  if (integrationService.hasIntegration("OPENAI")) {
    const apiKeyProvider = () => integrationService.getApiKey("OPENAI")
    return new OpenaiClient(interactor, apiKeyProvider)
  }
  if (integrationService.hasIntegration("GOOGLE_GEMINI")) {
    const apiKeyProvider = () => integrationService.getApiKey("GOOGLE_GEMINI")
    return new GeminiClient(interactor, apiKeyProvider)
  }
  if (integrationService.hasIntegration("ANTHROPIC_CLAUDE")) {
    const apiKeyProvider = () => integrationService.getApiKey("ANTHROPIC_CLAUDE")
    throw Error("Claude not implemented yet")
  }
  return
}