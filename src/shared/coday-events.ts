export abstract class CodayEvent {
  timestamp: string
  parentKey: string | undefined
  static type: string
  
  constructor(event: Partial<CodayEvent>, readonly type: string) {
    this.timestamp = event.timestamp ?? new Date().toISOString()
    this.parentKey = event.parentKey
  }
}

export abstract class QuestionEvent extends CodayEvent {
  invite: string
  
  constructor(event: Partial<QuestionEvent>, type: string) {
    super(event, type)
    this.invite = event.invite!
  }
  
  buildAnswer(answer: string): AnswerEvent {
    return new AnswerEvent({answer, parentKey: this.timestamp})
  }
}

export class HeartBeatEvent extends CodayEvent {
  static type = "heartbeat"
  
  constructor(event: Partial<HeartBeatEvent>) {
    super(event, HeartBeatEvent.type)
  }
}

export class InviteEvent extends QuestionEvent {
  defaultValue: string | undefined
  static type = "invite"
  
  constructor(event: Partial<InviteEvent>) {
    super(event, InviteEvent.type)
    this.defaultValue = event.defaultValue
  }
}

export class AnswerEvent extends CodayEvent {
  answer: string
  static type = "answer"
  
  constructor(event: Partial<AnswerEvent>) {
    super(event, AnswerEvent.type)
    this.answer = event.answer!!
  }
}

export class TextEvent extends CodayEvent {
  speaker: string | undefined
  text: string
  static type = "text"
  
  constructor(event: Partial<TextEvent>) {
    super(event, TextEvent.type)
    this.speaker = event.speaker
    this.text = event.text!!
  }
}

export class WarnEvent extends CodayEvent {
  warning: string
  static type = "warn"
  
  constructor(event: Partial<WarnEvent>) {
    super(event, WarnEvent.type)
    this.warning = event.warning!!
  }
}

export class ErrorEvent extends CodayEvent {
  error: unknown
  static type = "error"
  
  constructor(event: Partial<ErrorEvent>) {
    super(event, ErrorEvent.type)
    this.error = event.error!!
  }
}

export class ChoiceEvent extends QuestionEvent {
  options: string[]
  optionalQuestion: string | undefined
  static type = "choice"
  
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
  static type = "tool_request"
  
  constructor(event: Partial<ToolRequestEvent>) {
    super(event, ToolRequestEvent.type)
    this.toolRequestId = event.toolRequestId ?? this.timestamp ?? new Date().toISOString()
    this.name = event.name!!
    this.args = event.args!!
  }
  
  buildResponse(output: string): ToolResponseEvent {
    return new ToolResponseEvent({output, toolRequestId: this.toolRequestId})
  }
}

export class ToolResponseEvent extends CodayEvent {
  toolRequestId: string
  output: string
  static type = "tool_response"
  
  constructor(event: Partial<ToolResponseEvent>) {
    super(event, ToolResponseEvent.type)
    this.toolRequestId = event.toolRequestId !!
    this.output = event.output!!
  }
}

export class ProjectSelectedEvent extends CodayEvent {
  projectName: string
  static type = "project_selected"
  
  constructor(event: Partial<ProjectSelectedEvent>) {
    super(event, ProjectSelectedEvent.type)
    this.projectName = event.projectName!
  }
}

// Exposing a map of event types to their corresponding classes
const eventTypeToClassMap: { [key: string]: typeof CodayEvent } = {
  [HeartBeatEvent.type]: HeartBeatEvent,
  [InviteEvent.type]: InviteEvent,
  [AnswerEvent.type]: AnswerEvent,
  [TextEvent.type]: TextEvent,
  [WarnEvent.type]: WarnEvent,
  [ErrorEvent.type]: ErrorEvent,
  [ChoiceEvent.type]: ChoiceEvent,
  [ToolRequestEvent.type]: ToolRequestEvent,
  [ToolResponseEvent.type]: ToolResponseEvent,
  [ProjectSelectedEvent.type]: ProjectSelectedEvent
}

export function buildCodayEvent(data: any): CodayEvent | undefined {
  const Clazz = eventTypeToClassMap[data.type]
  // @ts-ignore
  return Clazz ? new Clazz(data) : undefined
}
