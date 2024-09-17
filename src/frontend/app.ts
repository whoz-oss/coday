import {ChatTextareaComponent} from "./chat-textarea/chat-textarea.component.js"
import {ChoiceSelectComponent} from "./choice-select/choice-select.component.js"
import {ChatHistoryComponent} from "./chat-history/chat-history.component.js"
import {buildCodayEvent, CodayEvent, ErrorEvent} from "../shared/coday-events.js"
import {CodayEventHandler} from "./utils/coday-event-handler.js"

const chatHistory = new ChatHistoryComponent()
const chatInputComponent = new ChatTextareaComponent()
const choiceInputComponent = new ChoiceSelectComponent()

const components: CodayEventHandler[] = [chatInputComponent, choiceInputComponent, chatHistory]

function handle(event: CodayEvent | undefined): void {
  if (event) {
    components.forEach(c => c.handle(event))
  }
}

const eventSource = new EventSource("/events")

eventSource.onmessage = (event) => {
  try {
    const data = JSON.parse(event.data)
    const codayEvent = buildCodayEvent(data)
    handle(codayEvent)
  } catch (error: any) {
    console.error("Could not parse event", event)
  }
}

eventSource.onerror = () => {
  handle(new ErrorEvent({error: "Connection lost"}))
}



