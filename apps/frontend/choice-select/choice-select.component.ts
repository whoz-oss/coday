import { ChoiceEvent, CodayEvent } from '@coday/shared/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'

export class ChoiceSelectComponent implements CodayEventHandler {
  private choiceForm: HTMLFormElement
  private choiceSelect: HTMLSelectElement
  private choiceLabel: HTMLLabelElement
  private choiceEvent: ChoiceEvent | undefined

  constructor(private postEvent: (event: CodayEvent) => Promise<Response>) {
    this.choiceForm = document.getElementById('choice-form') as HTMLFormElement
    this.choiceSelect = document.getElementById('choice-select') as HTMLSelectElement
    this.choiceLabel = document.getElementById('choices-label') as HTMLLabelElement

    this.choiceForm.onsubmit = async (event) => {
      event.preventDefault()
      const answer = this.choiceEvent?.buildAnswer(this.choiceSelect.value)
      if (!answer) {
        return
      }
      try {
        this.choiceForm.style.display = 'none'
        const response = await this.postEvent(answer)
        if (!response.ok) {
          this.choiceForm.style.display = 'block'
          this.choiceSelect.focus()
          console.error('Failed to send message.')
        }
      } catch (error) {
        console.error('Error occurred while sending message:', error)
      }
    }
  }

  handle(choiceEvent: CodayEvent): void {
    if (!(choiceEvent instanceof ChoiceEvent)) {
      return
    }
    this.choiceEvent = choiceEvent
    // Parse markdown for the choice label
    const labelContent = `${this.choiceEvent?.optionalQuestion} ${this.choiceEvent?.invite}`
    const parsed = marked.parse(labelContent)
    if (parsed instanceof Promise) {
      parsed.then(html => this.choiceLabel.innerHTML = html)
    } else {
      this.choiceLabel.innerHTML = parsed
    }
    this.choiceSelect.innerHTML = this.choiceEvent?.options
      .map((option) => `<option value="${option}">${option}</option>`)
      .join('')
    this.choiceForm.style.display = 'block'
    this.choiceSelect.focus()
  }
}
