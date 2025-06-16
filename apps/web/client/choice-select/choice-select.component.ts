import { ChoiceEvent, CodayEvent } from '@coday/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'
import { VoiceSynthesisComponent } from '../voice-synthesis/voice-synthesis.component'
import { getPreference } from '../utils/preferences'

const MINIMUM_SPEECH_LENGTH = 60

export class ChoiceSelectComponent implements CodayEventHandler {
  private choiceForm: HTMLFormElement
  private choiceSelect: HTMLSelectElement
  private choiceLabel: HTMLLabelElement
  private choiceEvent: ChoiceEvent | undefined

  constructor(
    private postEvent: (event: CodayEvent) => Promise<Response>,
    private voiceSynthesis: VoiceSynthesisComponent
  ) {
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

    // Listen for voice preference changes
    window.addEventListener('voiceModeChanged', (event: any) => {
      console.log('[CHOICE-SELECT] Voice mode changed to:', event.detail)
    })

    window.addEventListener('voiceAnnounceEnabledChanged', (event: any) => {
      console.log('[CHOICE-SELECT] Voice announce enabled changed to:', event.detail)
    })
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
      this.setupLabelClickHandler()

      // Annonce selon le mode et la longueur
      const audioEnabled = getPreference<boolean>('voiceAnnounceEnabled', false) || false
      if (audioEnabled) {
        const fullText = (this.choiceEvent?.optionalQuestion || '') + ' ' + (this.choiceEvent?.invite || '')
        const plainText = this.voiceSynthesis.extractPlainText(fullText)
        this.announceLabel(plainText)
      }
    }
    updateLabel().catch(console.error)
    this.choiceSelect.innerHTML = this.choiceEvent?.options
      .map((option) => `<option value="${option}">${option}</option>`)
      .join('')
    this.choiceForm.style.display = 'block'
    this.choiceSelect.focus()
  }

  private setupLabelClickHandler(): void {
    // Ajouter un event listener pour arrêter la synthèse vocale au clic
    this.choiceLabel.addEventListener('click', () => {
      this.voiceSynthesis.stopSpeech()
    })

    // Ajouter un style pour indiquer que le label est cliquable
    this.choiceLabel.style.cursor = 'pointer'
    this.choiceLabel.title = 'Click to stop speech'
  }

  private announceLabel(text: string): void {
    const mode = (getPreference<string>('voiceMode', 'speech') as 'speech' | 'notification') || 'speech'

    if (mode === 'notification' || text.length <= MINIMUM_SPEECH_LENGTH) {
      this.voiceSynthesis.ding()
    } else {
      this.voiceSynthesis.speak(text)
    }
  }
}
