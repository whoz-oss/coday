import {
  AnswerEvent,
  CodayEvent,
  ErrorEvent,
  TextEvent,
  ThinkingEvent,
  ToolRequestEvent,
  ToolResponseEvent,
} from '@coday/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'
import { getPreference } from '../utils/preferences'
import { VoiceSynthesisComponent } from '../voice-synthesis/voice-synthesis.component'

const PARAGRAPH_MIN_LENGTH = 80
const MAX_PARAGRAPHS = 3

export class ChatHistoryComponent implements CodayEventHandler {
  private chatHistory: HTMLDivElement
  private readonly thinkingDots: HTMLDivElement
  private readonly stopButton: HTMLButtonElement
  private history = new Map<string, CodayEvent>()
  private thinkingTimeout: any
  private readonly onStopCallback: () => void

  constructor(
    onStopCallback: () => void,
    private readonly voiceSynthesis: VoiceSynthesisComponent
  ) {
    this.chatHistory = document.getElementById('chat-history') as HTMLDivElement
    this.thinkingDots = document.getElementById('thinking-dots') as HTMLDivElement
    this.stopButton = this.thinkingDots.querySelector('.stop-button') as HTMLButtonElement
    this.onStopCallback = onStopCallback

    // Bind stop button click event
    if (this.stopButton) {
      this.stopButton.addEventListener('click', () => this.onStopCallback())
    }
  }

  handle(event: CodayEvent): void {
    this.history.set(event.timestamp, event)
    if (event instanceof TextEvent) {
      if (event.speaker) {
        // Stop any current speech when new response arrives
        this.voiceSynthesis.stopSpeech()
        this.addText(event.text, event.speaker)
      } else {
        this.addTechnical(event.text)
      }
    }
    if (event instanceof AnswerEvent) {
      this.addAnswer(event.answer, event.invite)
    }
    if (event instanceof ErrorEvent) {
      const errorMessage = JSON.stringify(event.error)
      this.addError(errorMessage)
    }
    if (event instanceof ThinkingEvent) {
      this.setThinking(true)
    }
    if (event instanceof ToolRequestEvent) {
      this.addToolRequest(event)
    }
    if (event instanceof ToolResponseEvent) {
      this.addToolResponse(event)
    }
  }

  private setThinking(value: boolean): void {
    if (!this.thinkingDots) {
      return
    }
    clearTimeout(this.thinkingTimeout)
    if (value) {
      this.thinkingTimeout = setTimeout(() => {
        this.thinkingDots.classList.toggle('visible', false)
      }, ThinkingEvent.debounce + 1000)
    } else {
      this.scrollToBottom()
    }
    this.thinkingDots.classList.toggle('visible', value)
  }

  private scrollToBottom(): void {
    if (this.chatHistory) {
      this.chatHistory.scrollTo(0, this.chatHistory.scrollHeight)
    }
  }

  addTechnical(text: string): void {
    const newEntry = this.createMessageElement(text, undefined)
    newEntry.classList.add('technical')
    this.appendMessageElement(newEntry)
  }

  addText(text: string, speaker: string | undefined): void {
    const newEntry = this.createMessageElement(text, speaker)
    newEntry.classList.add('text', 'left')
    newEntry.addEventListener('click', () => {
      this.voiceSynthesis.stopSpeech()
    })

    // Add copy button for agent responses
    const copyButtonContainer = document.createElement('div')
    copyButtonContainer.classList.add('copy-button-container')

    const copyButton = document.createElement('button')
    copyButton.classList.add('copy-button')
    copyButton.title = 'Copy raw response'
    copyButton.textContent = '📋' // Clipboard icon
    copyButton.addEventListener('click', (event) => {
      event.stopPropagation() // Prevent event bubbling
      this.copyToClipboard(text)

      // Update this specific button
      const clickedButton = event.currentTarget as HTMLButtonElement
      if (clickedButton) {
        // Clear any existing active buttons
        document.querySelectorAll('.copy-button.active').forEach((btn) => {
          btn.classList.remove('active')
          btn.textContent = '📋'
        })

        clickedButton.classList.add('active')
        clickedButton.textContent = '✓' // Checkmark to indicate success

        // Reset button after 2 seconds
        setTimeout(() => {
          clickedButton.classList.remove('active')
          clickedButton.textContent = '📋'
        }, 2000)
      }
    })

    copyButtonContainer.appendChild(copyButton)
    newEntry.appendChild(copyButtonContainer)

    this.appendMessageElement(newEntry)

    // Announce agent responses if enabled
    if (speaker) {
      if (this.isVoiceAnnounceEnabled()) {
        this.announceText(text)
      }
    }
  }

  addAnswer(answer: string, speaker: string | undefined): void {
    const newEntry = this.createMessageElement(answer, speaker)
    newEntry.classList.add('text', 'right')

    // Add copy button for user messages (similar to agent messages)
    const copyButtonContainer = document.createElement('div')
    copyButtonContainer.classList.add('copy-button-container')

    const copyButton = document.createElement('button')
    copyButton.classList.add('copy-button')
    copyButton.title = 'Copy raw message'
    copyButton.textContent = '📋' // Clipboard icon
    copyButton.addEventListener('click', (event) => {
      event.stopPropagation() // Prevent event bubbling
      this.copyToClipboard(answer)

      // Update this specific button
      const clickedButton = event.currentTarget as HTMLButtonElement
      if (clickedButton) {
        // Clear any existing active buttons
        document.querySelectorAll('.copy-button.active').forEach((btn) => {
          btn.classList.remove('active')
          btn.textContent = '📋'
        })

        clickedButton.classList.add('active')
        clickedButton.textContent = '✓' // Checkmark to indicate success

        // Reset button after 2 seconds
        setTimeout(() => {
          clickedButton.classList.remove('active')
          clickedButton.textContent = '📋'
        }, 2000)
      }
    })

    copyButtonContainer.appendChild(copyButton)
    newEntry.appendChild(copyButtonContainer)

    this.appendMessageElement(newEntry)
  }

  private copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).catch((err) => console.error('Failed to copy text: ', err))
  }

  private createMessageElement(content: string, speaker: string | undefined): HTMLDivElement {
    const newEntry = document.createElement('div')
    newEntry.classList.add('message')
    if (speaker) {
      const speakerElement = document.createElement('div')
      speakerElement.classList.add('speaker')
      speakerElement.textContent = speaker
      newEntry.appendChild(speakerElement)
      const parsed = marked.parse(content)
      parsed instanceof Promise
        ? parsed.then((html) => newEntry.appendChild(this.buildTextElement(html)))
        : newEntry.appendChild(this.buildTextElement(parsed))
    } else {
      const parsed = marked.parse(content)
      parsed instanceof Promise ? parsed.then((html) => (newEntry.innerHTML = html)) : (newEntry.innerHTML = parsed)
    }
    return newEntry
  }

  private appendMessageElement(element: HTMLDivElement): void {
    this.chatHistory?.appendChild(element)
  }

  private buildTextElement(innerHTML: string): HTMLDivElement {
    const textElement = document.createElement('div')
    textElement.innerHTML = innerHTML
    return textElement
  }

  addError(error: string): void {
    this.setThinking(false)
    const errorEntry = document.createElement('div')
    errorEntry.classList.add('error-message')

    // Create an error icon
    const errorIcon = document.createElement('span')
    errorIcon.textContent = '\u274c ' // Red X symbol
    errorIcon.classList.add('error-icon')
    errorEntry.appendChild(errorIcon)

    // Create the error text
    const errorText = document.createElement('span')
    errorText.textContent = `Error: ${error}`
    errorEntry.appendChild(errorText)

    // Add styling
    errorEntry.style.color = '#e74c3c' // Red color
    errorEntry.style.background = '#ffeeee' // Light red background
    errorEntry.style.padding = '10px'
    errorEntry.style.margin = '10px 0'
    errorEntry.style.borderRadius = '4px'
    errorEntry.style.border = '1px solid #e74c3c'

    this.chatHistory?.appendChild(errorEntry)
    this.scrollToBottom()
  }

  private getClientId(): string {
    // Extract clientId from URL
    const params = new URLSearchParams(window.location.search)
    return params.get('clientId') || ''
  }

  private createViewFullLink(eventId: string): HTMLAnchorElement | null {
    const clientId = this.getClientId()
    if (!clientId) return null

    const link = document.createElement('a')
    link.href = `/api/event/${eventId}?clientId=${clientId}`
    link.textContent = 'view'
    link.target = '_blank'
    link.style.marginLeft = '5px'
    link.style.fontSize = '0.9em'
    link.style.color = 'var(--color-link)'

    return link
  }

  addToolRequest(event: ToolRequestEvent): void {
    // Create a message element with the tool request
    const element = document.createElement('div')
    element.classList.add('message', 'technical')

    // Create the container span
    const container = document.createElement('span')

    // Add the message text as a text node
    const messageText = event.toSingleLineString()
    container.appendChild(document.createTextNode(messageText))

    // Add link at the end of the message
    const link = this.createViewFullLink(event.timestamp)
    if (link) {
      container.appendChild(link)
    }

    element.appendChild(container)
    this.appendMessageElement(element)
  }

  addToolResponse(event: ToolResponseEvent): void {
    // Create a message element with the tool response
    const element = document.createElement('div')
    element.classList.add('message', 'technical')

    // Create the container span
    const container = document.createElement('span')

    // Add the message text as a text node
    const messageText = event.toSingleLineString()
    container.appendChild(document.createTextNode(messageText))

    // Add link at the end of the message
    const link = this.createViewFullLink(event.timestamp)
    if (link) {
      container.appendChild(link)
    }

    element.appendChild(container)
    this.appendMessageElement(element)
  }

  private isVoiceAnnounceEnabled(): boolean {
    const isEnabled = getPreference<boolean>('voiceAnnounceEnabled', false) || false
    console.log('[VOICE] isVoiceAnnounceEnabled from preferences:', isEnabled)
    return isEnabled
  }

  private extractPlainText(markdown: string): string {
    // Remove basic markdown formatting
    return markdown
      .replace(/\*\*(.*?)\*\*/g, '$1') // Bold
      .replace(/\*(.*?)\*/g, '$1') // Italic
      .replace(/`(.*?)`/g, '$1') // Code
      .replace(/#{1,6}\s*(.*)/g, '$1') // Headers
      .replace(/\[(.*?)\]\(.*?\)/g, '$1') // Links
      .trim()
  }

  private getVoiceMode(): 'speech' | 'notification' {
    const mode = (getPreference<string>('voiceMode', 'speech') as 'speech' | 'notification') || 'speech'
    console.log('[VOICE] getVoiceMode from preferences:', mode)
    return mode
  }

  private playNotificationSound(): void {
    // Create a simple notification sound using Web Audio API or HTML5 Audio
    try {
      // Try to create a simple beep sound
      const audioContext = new (window.AudioContext || (window as any).webkitAudioContext)()
      const oscillator = audioContext.createOscillator()
      const gainNode = audioContext.createGain()

      oscillator.connect(gainNode)
      gainNode.connect(audioContext.destination)

      oscillator.frequency.setValueAtTime(800, audioContext.currentTime) // 800Hz tone
      gainNode.gain.setValueAtTime(0.1, audioContext.currentTime) // Low volume
      gainNode.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 0.1)

      oscillator.start(audioContext.currentTime)
      oscillator.stop(audioContext.currentTime + 0.1)
    } catch (error) {
      // Fallback: try to use a simple beep
      console.log('\u0007') // Bell character (might work in some terminals)
    }
  }

  private announceText(text: string): void {
    const mode = this.getVoiceMode()

    if (mode === 'notification') {
      this.playNotificationSound()
      return
    }

    let plainText = this.extractPlainText(text)

    const speech = plainText.split('\n').reduce(
      (acc, value) => {
        if (acc.paragraphs >= MAX_PARAGRAPHS) {
          return acc
        } else {
          const paragraphIncrement = value.length > PARAGRAPH_MIN_LENGTH ? 1 : 0
          return {
            paragraphs: acc.paragraphs + paragraphIncrement,
            text: acc.text + '\n' + value,
          }
        }
      },
      { paragraphs: 0, text: '' }
    )

    if (!speech.text.trim()) {
      console.log('[VOICE] No text to speak after processing!')
      return
    }

    // Use the voice synthesis component
    this.voiceSynthesis.speak(speech.text)
  }
}
