// IMPORTANT: keep an ".js" on import for frontend mess without bundlers to hide it.
import {
  AnswerEvent,
  ChoiceEvent,
  HeartBeatEvent,
  InviteEvent,
  QuestionEvent,
  TextEvent
} from "../shared/coday-events.js"

const eventSource = new EventSource("/events")
const chatHistory = document.getElementById("chat-history") // Container for displaying heartbeat messages
const chatInput = document.getElementById("chat-input") as HTMLInputElement
const chatForm = document.getElementById("chat-form") as HTMLFormElement

let questionEvent: QuestionEvent | ChoiceEvent | null

chatForm.onsubmit = async (event) => {
  event.preventDefault()
  if (chatInput && chatInput.value.trim() !== "") {
    try {
      const answerEvent = questionEvent?.buildAnswer(chatInput.value.trim())
      const response = await fetch("/api/message", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(answerEvent),
      })
      if (response.ok) {
        chatInput.value = "" // Clear input
        questionEvent = null
      } else {
        console.error("Failed to send message.")
      }
    } catch (error) {
      console.error("Error occurred while sending message:", error)
    }
  }
}

function addText(text: string, speaker?: string): void {
  const newEntry = document.createElement("div")
  newEntry.textContent = speaker ? `${speaker}: ${text}` : text
  chatHistory?.appendChild(newEntry)
}

eventSource.onmessage = (event: MessageEvent<string>) => {
  const data = JSON.parse(event.data)
  console.log(data.type)
  
  if (data.type === HeartBeatEvent.type) {
    const e = new HeartBeatEvent(data)
    console.log("heartbeat:", e.timestamp)
  }
  if (data.type === ChoiceEvent.type) {
    const e = new ChoiceEvent(data)
    questionEvent = e
    addText(`choice events: ${(e.options.join("\n"))}`)
  }
  if (data.type === InviteEvent.type) {
    const e = new InviteEvent(data)
    questionEvent = e
    addText(e.invite)
  }
  if (data.type === TextEvent.type) {
    const e = new TextEvent(data)
    addText(e.text, e.speaker)
  }
  if (data.type === AnswerEvent.type) {
    const e = new AnswerEvent(data)
    addText(e.answer)
  }
}


eventSource.onerror = () => {
  const errorEntry = document.createElement("div")
  errorEntry.textContent = "Error: Connection lost."
  errorEntry.style.color = "red"
  chatHistory?.appendChild(errorEntry)
}

