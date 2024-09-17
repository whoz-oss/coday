import {ChoiceEvent, CodayEvent} from "../../shared/coday-events.js"
import {postEvent} from "../utils/post-message.js"
import {CodayEventHandler} from "../utils/coday-event-handler.js"

export class ChoiceSelectComponent implements CodayEventHandler {
  private choiceForm: HTMLFormElement
  private choiceSelect: HTMLSelectElement
  private choiceLabel: HTMLLabelElement
  private choiceEvent: ChoiceEvent | undefined
  
  constructor() {
    this.choiceForm = document.getElementById("choice-form") as HTMLFormElement
    this.choiceSelect = document.getElementById("choice-select") as HTMLSelectElement
    this.choiceLabel = document.getElementById("choices-label") as HTMLLabelElement
    
    this.choiceForm.onsubmit = async (event) => {
      event.preventDefault()
      const answer = this.choiceEvent?.buildAnswer(this.choiceSelect.value)
      if (!answer) {
        return
      }
      try {
        this.choiceForm.style.display = "none"
        const response = await postEvent(answer)
        if (!response.ok) {
          this.choiceForm.style.display = "block"
          console.error("Failed to send message.")
        }
      } catch (error) {
        console.error("Error occurred while sending message:", error)
      }
    }
  }
  
  handle(choiceEvent: CodayEvent): void {
    if (!(choiceEvent instanceof ChoiceEvent)) {
      return
    }
    console.log("got choice", choiceEvent)
    this.choiceEvent = choiceEvent
    this.choiceLabel.innerHTML = `${this.choiceEvent?.optionalQuestion} ${this.choiceEvent?.invite}`
    this.choiceSelect.innerHTML = this.choiceEvent?.options.map(option => `<option value="${option}">${option}</option>`).join("")
    this.choiceForm.style.display = "block"
  }
}