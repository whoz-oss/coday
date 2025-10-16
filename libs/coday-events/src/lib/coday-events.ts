export type TextContent = {
  type: 'text'
  content: string
}

export type ImageContent = {
  type: 'image'
  content: string
  mimeType: 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp'
  source?: string
  width?: number
  height?: number
}

export type MessageContent = TextContent | ImageContent

/**
 * Helper function to truncate text for display
 * @param text Text to truncate
 * @param maxLength Maximum length before truncation
 * @returns Truncated text with ellipsis if needed
 */
export function truncateText(text: string, maxLength: number = 80): string {
  // If it's already a short string, return as is
  if (text.length <= maxLength) return text

  // Try to parse as JSON to handle objects and arrays better
  try {
    // If it starts with { or [, assume it's JSON
    if (text.trim().startsWith('{') || text.trim().startsWith('[')) {
      const obj = JSON.parse(text)
      // For simple display, just stringify with minimal formatting
      const simplified = JSON.stringify(obj)
      if (simplified.length <= maxLength) return simplified
      return simplified.substring(0, maxLength) + `...(${simplified.length} chars)`
    }
  } catch (e) {
    // Not valid JSON or other error, fall back to simple truncation
  }

  // Default: simple truncation
  return text.substring(0, maxLength) + '...'
}

export abstract class CodayEvent {
  timestamp: string
  parentKey: string | undefined
  static type: string
  length: number

  constructor(
    event: Partial<CodayEvent>,
    readonly type: string
  ) {
    // If timestamp is not provided, generate one with a random suffix to avoid collisions
    if (!event.timestamp) {
      const randomSuffix = Math.random().toString(36).substring(2, 7) // 5 random chars
      this.timestamp = `${new Date().toISOString()}-${randomSuffix}`
    } else {
      this.timestamp = event.timestamp
    }
    this.parentKey = event.parentKey
    this.length = 0
  }
}

export abstract class QuestionEvent extends CodayEvent {
  invite: string

  constructor(event: Partial<QuestionEvent>, type: string) {
    super(event, type)
    this.invite = event.invite!
  }

  buildAnswer(answer: string): AnswerEvent {
    return new AnswerEvent({ answer, parentKey: this.timestamp, invite: this.invite })
  }
}

export class HeartBeatEvent extends CodayEvent {
  static override type = 'heartbeat'

  constructor(event: Partial<HeartBeatEvent>) {
    super(event, HeartBeatEvent.type)
  }
}

export class InviteEvent extends QuestionEvent {
  defaultValue: string | undefined
  static override type = 'invite'

  constructor(event: Partial<InviteEvent>) {
    super(event, InviteEvent.type)
    this.defaultValue = event.defaultValue
  }
}

export const InviteEventDefault = 'InviteEventDefault'

export class AnswerEvent extends CodayEvent {
  answer: string
  invite: string | undefined
  static override type = 'answer'

  constructor(event: Partial<AnswerEvent>) {
    super(event, AnswerEvent.type)
    this.answer = event.answer ?? 'No answer'
    this.invite = event.invite
  }
}

export class TextEvent extends CodayEvent {
  speaker: string | undefined
  text: string
  static override type = 'text'

  constructor(event: Partial<TextEvent>) {
    super(event, TextEvent.type)
    this.speaker = event.speaker
    this.text = event.text!!
  }
}

export class WarnEvent extends CodayEvent {
  warning: string
  static override type = 'warn'

  constructor(event: Partial<WarnEvent>) {
    super(event, WarnEvent.type)
    this.warning = event.warning!!
  }
}

export class ErrorEvent extends CodayEvent {
  error: unknown
  static override type = 'error'

  constructor(event: Partial<ErrorEvent>) {
    super(event, ErrorEvent.type)
    this.error = event.error!!
  }
}

export class ChoiceEvent extends QuestionEvent {
  options: string[]
  optionalQuestion: string | undefined
  static override type = 'choice'

  constructor(event: Partial<ChoiceEvent>) {
    super(event, ChoiceEvent.type)
    this.options = event.options!!
    this.optionalQuestion = event.optionalQuestion
  }
}

export class ToolRequestEvent extends CodayEvent {
  toolRequestId: string
  name: string
  args: string
  static override type = 'tool_request'

  constructor(event: Partial<ToolRequestEvent>) {
    super(event, ToolRequestEvent.type)
    // Use the timestamp (which now has a random suffix) as the toolRequestId if not provided
    this.toolRequestId = event.toolRequestId ?? this.timestamp
    this.name = event.name!!
    this.args = event.args!!
    this.length = this.args.length + this.name.length + this.toolRequestId.length + 20
  }

  buildResponse(output: string | MessageContent): ToolResponseEvent {
    return new ToolResponseEvent({ output, toolRequestId: this.toolRequestId, parentKey: this.timestamp })
  }

  /**
   * Renders the tool request as a single line string with truncation
   * @param maxLength Maximum length for the arguments before truncation
   * @returns A formatted string representation
   */
  toSingleLineString(maxLength: number = 50): string {
    const truncatedArgs = truncateText(this.args, maxLength)
    return `🔧 ${this.name}(${truncatedArgs})`
  }
}

export class ToolResponseEvent extends CodayEvent {
  toolRequestId: string
  output: string | MessageContent
  static override type = 'tool_response'

  constructor(event: Partial<ToolResponseEvent>) {
    super(event, ToolResponseEvent.type)
    this.toolRequestId = event.toolRequestId || this.timestamp || new Date().toISOString()

    this.output = event.output!!
    if (typeof this.output === 'string') {
      this.length = this.output.length + this.toolRequestId.length + 20
    } else {
      if (this.output.type === 'text') {
        this.length = this.output.content.length + this.toolRequestId.length + 20
      } else if (this.output.type === 'image') {
        const tokens = ((this.output.width ?? 0) * (this.output.height ?? 0)) / 750
        this.length = (tokens ? tokens * 3.5 : this.output.content.length) + this.toolRequestId.length + 20
      } else {
        this.length = this.toolRequestId.length + 20
      }
    }
  }

  /**
   * Get the text content as a string for backward compatibility
   */
  getTextOutput(): string {
    if (typeof this.output === 'string') {
      return this.output
    }
    if (this.output.type === 'text') {
      return this.output.content
    }
    if (this.output.type === 'image') {
      return `[Image: ${this.output.mimeType}]`
    }
    return ''
  }

  /**
   * Renders the tool response as a single line string with truncation
   * @param maxLength Maximum length for the output before truncation
   * @returns A formatted string representation
   */
  toSingleLineString(maxLength: number = 50): string {
    const textOutput = this.getTextOutput()
    const truncatedOutput = truncateText(textOutput, maxLength)
    const imageIndicator = typeof this.output !== 'string' && this.output.type === 'image' ? ' [image]' : ''
    return `⮑ ${truncatedOutput}${imageIndicator}`
  }
}

export class ProjectSelectedEvent extends CodayEvent {
  projectName: string
  static override type = 'project_selected'

  constructor(event: Partial<ProjectSelectedEvent>) {
    super(event, ProjectSelectedEvent.type)
    this.projectName = event.projectName!
  }
}

export class ThreadSelectedEvent extends CodayEvent {
  threadId: string
  threadName: string
  static override type = 'thread_selected'

  constructor(event: Partial<ThreadSelectedEvent>) {
    super(event, ThreadSelectedEvent.type)
    this.threadId = event.threadId!
    this.threadName = event.threadName!
  }
}

export class ThinkingEvent extends CodayEvent {
  static override type = 'thinking'
  static debounce = 5000

  constructor(event: Partial<ThinkingEvent>) {
    super(event, ThinkingEvent.type)
  }
}

export class MessageEvent extends CodayEvent {
  role: 'user' | 'assistant'
  name: string
  content: MessageContent[]
  static override type = 'message'

  constructor(event: Partial<MessageEvent>) {
    super(event, MessageEvent.type)
    this.role = event.role!
    this.name = event.name!
    this.content = event.content!

    this.length = this.content
      .map((content) => {
        if (content.type === 'text') {
          return content.content.length
        }
        if (content.type === 'image') {
          const tokens = ((content.width || 0) * (content.height || 0)) / 750
          return tokens ? tokens * 3.5 : content.content.length
        }
        return 0
      })
      .reduce((sum, length) => sum + length, 0)
  }

  getTextContent(): string {
    return this.content
      .filter((c) => c.type === 'text')
      .map((c) => c.content)
      .join('\n')
  }
}

// Exposing a map of event types to their corresponding classes
const eventTypeToClassMap: { [key: string]: typeof CodayEvent } = {
  [MessageEvent.type]: MessageEvent,
  [AnswerEvent.type]: AnswerEvent,
  [ChoiceEvent.type]: ChoiceEvent,
  [ErrorEvent.type]: ErrorEvent,
  [HeartBeatEvent.type]: HeartBeatEvent,
  [InviteEvent.type]: InviteEvent,
  [ProjectSelectedEvent.type]: ProjectSelectedEvent,
  [ThreadSelectedEvent.type]: ThreadSelectedEvent,
  [ToolRequestEvent.type]: ToolRequestEvent,
  [ToolResponseEvent.type]: ToolResponseEvent,
  [TextEvent.type]: TextEvent,
  [ThinkingEvent.type]: ThinkingEvent,
  [WarnEvent.type]: WarnEvent,
}

export function buildCodayEvent(data: any): CodayEvent | undefined {
  const Clazz = eventTypeToClassMap[data.type]
  // @ts-ignore //data is an unknown given object type making it impossible to type at this time.
  return Clazz ? new Clazz(data) : undefined
}
