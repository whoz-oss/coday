import {ChatTextareaComponent} from "./chat-textarea/chat-textarea.component.js"
import {ChoiceSelectComponent} from "./choice-select/choice-select.component.js"
import {ChatHistoryComponent} from "./chat-history/chat-history.component.js"
import {buildCodayEvent, CodayEvent, ErrorEvent} from "../shared/coday-events.js"
import {CodayEventHandler} from "./utils/coday-event-handler.js"
import {HeaderComponent} from "./header/header.component.js"

function generateClientId() {
  // Generate or retrieve a unique client ID
  return Math.random().toString(36).substring(2, 15)
}

const clientId = generateClientId()

function postEvent(event: CodayEvent): Promise<Response> {
  return fetch(`/api/message?clientId=${clientId}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(event),
  })
}

const chatHistory = new ChatHistoryComponent()
const chatInputComponent = new ChatTextareaComponent(postEvent)
const choiceInputComponent = new ChoiceSelectComponent(postEvent)

const components: CodayEventHandler[] = [chatInputComponent, choiceInputComponent, chatHistory, new HeaderComponent()]
const eventSource = new EventSource(`/events?clientId=${clientId}`)

eventSource.onmessage = (event) => {
  try {
    const data = JSON.parse(event.data)
    const codayEvent = buildCodayEvent(data)
    if (codayEvent) {
      components.forEach(c => c.handle(codayEvent))
    }
  } catch (error: any) {
    console.error("Could not parse event", event)
  }
}

eventSource.onerror = () => {
  if (new ErrorEvent({error: "Connection lost"})) {
    components.forEach(c => c.handle(new ErrorEvent({error: "Connection lost"})))
  }
}



