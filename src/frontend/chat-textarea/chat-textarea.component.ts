import {CodayEvent, InviteEvent} from "../../shared/coday-events.js"
import {postEvent} from "../utils/post-message.js"
import {CodayEventHandler} from "../utils/coday-event-handler.js"

export class ChatTextareaComponent implements CodayEventHandler {
  private chatForm: HTMLFormElement
  private chatTextarea: HTMLTextAreaElement
  private chatLabel: HTMLLabelElement
  private expandToggle: HTMLButtonElement
  private inviteEvent: InviteEvent | undefined
  
  constructor() {
    this.chatForm = document.getElementById("chat-form") as HTMLFormElement
    this.chatTextarea = document.getElementById("chat-input") as HTMLTextAreaElement
    this.chatLabel = document.getElementById("chat-label") as HTMLLabelElement
    
    this.chatForm.onsubmit = async (event) => {
      event.preventDefault()
      await this.submit()
    }
    
    this.expandToggle = document.getElementById("expand-toggle") as HTMLButtonElement
    this.expandToggle.onclick = () => {
      const expanded = this.chatTextarea.classList.toggle("expanded-textarea")
      this.expandToggle.textContent = expanded ? "Collapse" : "Expand"
    }
    
    this.chatTextarea.addEventListener("keydown", async (e) => {
      if (e.metaKey && e.key === "Enter") {
        e.preventDefault()
        await this.submit()
      }
    })
  }
  
  handle(event: CodayEvent): void {
    if (event instanceof InviteEvent) {
      this.inviteEvent = event
      this.chatLabel.innerHTML = this.inviteEvent.invite
      this.chatForm.style.display = "block"
      if (this.inviteEvent.defaultValue) {
        this.chatTextarea.value = this.inviteEvent.defaultValue
      }
    }
  }
  
  private async submit(): Promise<void> {
    const answer = this.inviteEvent?.buildAnswer(this.chatTextarea.value)
    if (!answer) {
      return
    }
    try {
      this.chatForm.style.display = "none"
      const response = await postEvent(answer)
      if (response.ok) {
        this.chatTextarea.value = ""
      } else {
        this.chatForm.style.display = "block"
        console.error("Failed to send message.")
      }
    } catch (error) {
      console.error("Error occurred while sending message:", error)
    }
  }
}