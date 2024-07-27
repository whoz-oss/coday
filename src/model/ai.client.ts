import {CommandContext} from "../model"

export interface AiClient {
  addMessage(message: string): Promise<void>
  
  answer(
    name: string,
    command: string,
    context: CommandContext,
  ): Promise<string>
}