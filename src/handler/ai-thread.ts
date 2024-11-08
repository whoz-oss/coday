import {buildCodayEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent} from "../shared/coday-events"
import {ToolCall, ToolResponse} from "../integration/tool-call"

export type ThreadMessage = MessageEvent | ToolRequestEvent | ToolResponseEvent

const THREAD_MESSAGE_TYPES = [MessageEvent.type, ToolRequestEvent.type, ToolResponseEvent.type]

export class AiThread {
  id: string
  private _messages: ThreadMessage[]
  
  constructor(thread: { id: string, messages?: any[] }) {
    this.id = thread.id!
    // Filter on type first, then build events
    this._messages = (thread.messages ?? [])
      .filter(msg => THREAD_MESSAGE_TYPES.includes(msg.type))
      .map(msg => buildCodayEvent(msg))
      .filter((event): event is ThreadMessage => event !== undefined)
  }
  
  get messages(): ThreadMessage[] {
    return [...this._messages]
  }
  
  add(message: ThreadMessage): void {
    this._messages.push(message)
  }
  
  addUserMessage(username: string, content: string): void {
    this.add(new MessageEvent({
      role: "user",
      content,
      name: username
    }))
  }
  
  addAgentMessage(agentName: string, content: string): void {
    this.add(new MessageEvent({
      role: "assistant",
      content,
      name: agentName
    }))
  }
  
  
  addToolCalls(agentName: string, toolCalls: ToolCall[]): void {
    toolCalls.forEach(call => {
      this.add(new ToolRequestEvent({
        name: call.name,
        args: call.args,
        toolRequestId: call.id
      }))
    })
  }
  
  addToolResponses(username: string, responses: ToolResponse[]): void {
    responses.forEach(response => {
      this.add(new ToolResponseEvent({
        toolRequestId: response.id,
        output: response.response
      }))
    })
  }
}