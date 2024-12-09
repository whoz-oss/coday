export abstract class CodayEvent {
  timestamp: string
  parentKey: string | undefined
  static type: string
  length: number

  constructor(
    event: Partial<CodayEvent>,
    readonly type: string
  ) {
    this.timestamp = event.timestamp ?? new Date().toISOString()
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
  static type = 'heartbeat'

  constructor(event: Partial<HeartBeatEvent>) {
    super(event, HeartBeatEvent.type)
  }
}

export class InviteEvent extends QuestionEvent {
  defaultValue: string | undefined
  static type = 'invite'

  constructor(event: Partial<InviteEvent>) {
    super(event, InviteEvent.type)
    this.defaultValue = event.defaultValue
  }
}

export class AnswerEvent extends CodayEvent {
  answer: string
  invite?: string
  static type = 'answer'

  constructor(event: Partial<AnswerEvent>) {
    super(event, AnswerEvent.type)
    this.answer = event.answer ?? 'No answer'
    this.invite = event.invite
  }
}

export class TextEvent extends CodayEvent {
  speaker: string | undefined
  text: string
  static type = 'text'

  constructor(event: Partial<TextEvent>) {
    super(event, TextEvent.type)
    this.speaker = event.speaker
    this.text = event.text!!
  }
}

export class WarnEvent extends CodayEvent {
  warning: string
  static type = 'warn'

  constructor(event: Partial<WarnEvent>) {
    super(event, WarnEvent.type)
    this.warning = event.warning!!
  }
}

export class ErrorEvent extends CodayEvent {
  error: unknown
  static type = 'error'

  constructor(event: Partial<ErrorEvent>) {
    super(event, ErrorEvent.type)
    this.error = event.error!!
  }
}

export class ChoiceEvent extends QuestionEvent {
  options: string[]
  optionalQuestion: string | undefined
  static type = 'choice'

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
  static type = 'tool_request'

  constructor(event: Partial<ToolRequestEvent>) {
    super(event, ToolRequestEvent.type)
    this.toolRequestId = event.toolRequestId ?? this.timestamp ?? new Date().toISOString()
    this.name = event.name!!
    this.args = event.args!!
    this.length = this.args.length + this.name.length + this.toolRequestId.length + 20
  }

  buildResponse(output: string): ToolResponseEvent {
    return new ToolResponseEvent({ output, toolRequestId: this.toolRequestId })
  }
}

export class ToolResponseEvent extends CodayEvent {
  toolRequestId: string
  output: string
  static type = 'tool_response'

  constructor(event: Partial<ToolResponseEvent>) {
    super(event, ToolResponseEvent.type)
    this.toolRequestId = event.toolRequestId!!
    this.output = event.output!!
    this.length = this.output.length + this.toolRequestId.length + 20
  }
}

export class ProjectSelectedEvent extends CodayEvent {
  projectName: string
  static type = 'project_selected'

  constructor(event: Partial<ProjectSelectedEvent>) {
    super(event, ProjectSelectedEvent.type)
    this.projectName = event.projectName!
  }
}

export class ThinkingEvent extends CodayEvent {
  static type = 'thinking'
  static debounce = 5000

  constructor(event: Partial<ThinkingEvent>) {
    super(event, ThinkingEvent.type)
  }
}

export class MessageEvent extends CodayEvent {
  role: 'user' | 'assistant'
  name: string
  content: string
  static type = 'message'

  constructor(event: Partial<MessageEvent>) {
    super(event, MessageEvent.type)
    this.role = event.role!
    this.name = event.name!
    this.content = event.content!
    this.length = this.content.length + this.role.length + this.name.length + 20 // made up number for ", : and {}
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
