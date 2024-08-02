export abstract class CodayEvent {
  timestamp: Date
  parentKey: any
  static type: string
  
  protected constructor(readonly type: string, event: Partial<CodayEvent>) {
    this.timestamp = event.timestamp ?? new Date()
    this.parentKey = event.parentKey
  }
}

export abstract class QuestionEvent extends CodayEvent {
  buildAnswer(answer: string): AnswerEvent {
    return new AnswerEvent({answer, parentKey: this.timestamp})
  }
}

export class InviteEvent extends QuestionEvent {
  invite: string
  static type = "invite"
  
  constructor(event: Partial<InviteEvent>) {
    super(InviteEvent.type, event)
    this.invite = event.invite!!
  }
}

export class AnswerEvent extends CodayEvent {
  answer: string
  static type = "answer"
  
  constructor(event: Partial<AnswerEvent>) {
    super(AnswerEvent.type, event)
    this.answer = event.answer!!
  }
}

export class TextEvent extends CodayEvent {
  speaker: string | undefined
  text: string
  static type = "text"
  
  constructor(event: Partial<TextEvent>) {
    super(TextEvent.type, event)
    this.speaker = event.speaker
    this.text = event.text!!
  }
}

export class WarnEvent extends CodayEvent {
  warning: string
  static type = "warn"
  
  constructor(event: Partial<WarnEvent>) {
    super(WarnEvent.type, event)
    this.warning = event.warning!!
  }
}

export class ErrorEvent extends CodayEvent {
  error: unknown
  static type = "error"
  
  constructor(event: Partial<ErrorEvent>) {
    super(ErrorEvent.type, event)
    this.error = event.error!!
  }
}

export class ChoiceEvent extends QuestionEvent {
  options: string[]
  question: string
  invite: string | undefined
  static type = "choice"
  
  constructor(event: Partial<ChoiceEvent>) {
    super(ChoiceEvent.type, event)
    this.options = event.options!!
    this.question = event.question!!
    this.invite = event.invite
  }
}

export class ToolRequestEvent extends CodayEvent {
  toolRequestId: string
  name: string
  args: string
  static type = "tool_request"
  
  constructor(event: Partial<ToolRequestEvent>) {
    super(ToolRequestEvent.type, event)
    this.toolRequestId = event.toolRequestId ?? this.timestamp.toISOString()
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
    super(ToolResponseEvent.type, event)
    this.toolRequestId = event.toolRequestId !!
    this.output = event.output!!
  }
}