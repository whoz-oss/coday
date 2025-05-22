/* eslint-disable @nx/enforce-module-boundaries */
import { ChoiceEvent, CodayEvent } from '@coday/coday-events'
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
    // Parse markdown for both optional question and invite separately
    const questionText = this.choiceEvent?.optionalQuestion ? marked.parse(this.choiceEvent.optionalQuestion) : ''
    const inviteText = marked.parse(this.choiceEvent?.invite || '')

    // Handle both potential promises
    const updateLabel = async () => {
      const [questionHtml, inviteHtml] = await Promise.all([
        questionText instanceof Promise ? questionText : Promise.resolve(questionText),
        inviteText instanceof Promise ? inviteText : Promise.resolve(inviteText),
      ])
      this.choiceLabel.innerHTML = questionHtml + ' ' + inviteHtml
    }
    updateLabel().catch(console.error)
    this.choiceSelect.innerHTML = this.choiceEvent?.options
      .map((option) => `<option value="${option}">${option}</option>`)
      .join('')
    this.choiceForm.style.display = 'block'
    this.choiceSelect.focus()
  }
}
