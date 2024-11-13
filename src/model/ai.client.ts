import {CommandContext} from "../model"
import {AiProvider} from "./agent-definition"

/**
 * Common interface abstraction over different AI provider APIs
 *
 * Should be completely stateless
 */
export interface AiClient {
  /**
   * Enum value indicating to users of the AiClient interface which provider is used
   * Serves as a key/identifier
   */
  aiProvider: AiProvider
  
  /**
   * Temporary marker for openai assistant feature
   * Should be removed in favor of full Assistant class relying on AiClient
   */
  multiAssistant: boolean
  
  /**
   * Adds a user-issued message to the Openai thread
   *
   * Usable only with the Openai assistant API.
   * Should be deprecated in favor of an in-house thread management
   *
   * @param message the user message to add to the thread
   * @param context
   */
  addMessage(
    message: string,
    context: CommandContext,
  ): Promise<void>;
  
  /**
   * Answer the command by querying the assistant by its name
   *
   * Should be deprecated in favor of a stateless signature like:
   *   answer(agent: Agent, thread: AiThread): Promise<void>
   *
   * @param name of the assistant called (openai-specific)
   * @param command prompt sent to the assistant
   * @param context
   */
  answer(
    name: string,
    command: string,
    context: CommandContext,
  ): Promise<string>;
  
  kill(): void
  
  /**
   * Forgets the thread data for a client reset
   *
   * Used only for Openai and Gemini.
   * Should be deprecated in favor of custom thread management
   */
  reset(): void
}
