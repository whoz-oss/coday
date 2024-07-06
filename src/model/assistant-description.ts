import {IntegrationName} from "./integration-name";

export type AssistantDescription = {
  /**
   * Should be the name of the assistant as declared in the platform.
   * Matching will be done on star of the name in lowercase but still, please put full name.
   */
  name: string
  
  /**
   * Short description of the assistant, especially useful for other assistants to be able to call them.
   */
  description: string
  
  /**
   * Should the assistant not exist, it will be created if the instructions here are supplied, with these and the default model, under the account tied to the apikey
   */
  systemInstructions?: string
  
  /**
   * TODO: use fields, not yet connected
   * Declare what apis the assistant will have access to **in this project** (meaning if not set in the project, will not be used even if listed here).
   */
  integrations?: IntegrationName[]
  
  /**
   * Define the model to use. Clients must have a default
   */
  model?: string
  
  /**
   * Taken from Openai temperature, define to avoid the client’s default value
   */
  temperature?: number
}