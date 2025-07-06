import {
  AnswerEvent,
  CodayEvent,
  ErrorEvent,
  MessageEvent,
  TextEvent,
  ThinkingEvent,
  ToolRequestEvent,
  ToolResponseEvent,
  WarnEvent,
} from '@coday/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'
import { getPreference } from '../utils/preferences'
import { VoiceSynthesisComponent } from '../voice-synthesis/voice-synthesis.component'

const PARAGRAPH_MIN_LENGTH = 80
const MAX_PARAGRAPHS = 3
const MESSAGE_FRESHNESS_THRESHOLD = 5 * 60 * 1000 //  in millis

export class ChatHistoryComponent implements CodayEventHandler {
  private chatHistory: HTMLDivElement
  private readonly thinkingDots: HTMLDivElement
  private readonly stopButton: HTMLButtonElement
  private history = new Map<string, CodayEvent>()
  private thinkingTimeout: any
  private readonly onStopCallback: () => void
  private readFullText: boolean = false
  private currentPlayingButton: HTMLButtonElement | null = null

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

    // Load initial preference
    this.readFullText = getPreference<boolean>('voiceReadFullText', false) || false

    // Listen for preference changes
    window.addEventListener('voiceReadFullTextChanged', (event: any) => {
      this.readFullText = event.detail
    })

    // Vérification périodique pour s'assurer que l'état reste cohérent
    setInterval(() => {
      this.checkStateConsistency()
    }, 1000)
  }

  handle(event: CodayEvent): void {
    this.history.set(event.timestamp, event)
    if (event instanceof MessageEvent) {
      // Handle rich content messages
      if (event.role === 'user') {
        this.addUserMessage(event)
      } else {
        // Stop any current speech when new response arrives and reset buttons
        this.voiceSynthesis.stopSpeech()
        this.resetAllPlayButtons()
        this.addAssistantMessage(event)
      }
    } else if (event instanceof TextEvent) {
      if (event.speaker) {
        // Stop any current speech when new response arrives and reset buttons
        this.voiceSynthesis.stopSpeech()
        this.resetAllPlayButtons()
        this.addText(event.text, event.speaker, event.timestamp)
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
    if (event instanceof WarnEvent) {
      const warnMessage = JSON.stringify(event.warning)
      this.addError(warnMessage, 'Warning')
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

  addText(text: string, speaker: string | undefined, messageTimestamp?: string): void {
    const newEntry = this.createMessageElement(text, speaker)
    newEntry.classList.add('text', 'left')
    newEntry.addEventListener('click', () => {
      this.voiceSynthesis.stopSpeech()
    })

    // Add button container for agent responses
    const buttonContainer = document.createElement('div')
    buttonContainer.classList.add('message-button-container')

    // Create play button
    const playButton = this.createPlayButton(text)
    buttonContainer.appendChild(playButton)

    // Create copy button
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

    buttonContainer.appendChild(copyButton)
    newEntry.appendChild(buttonContainer)

    this.appendMessageElement(newEntry)

    // Announce agent responses if enabled (and message is recent enough)
    const audioEnabled = getPreference<boolean>('voiceAnnounceEnabled', false) || false
    if (speaker && audioEnabled && this.isMessageRecentEnoughForAnnouncement(messageTimestamp)) {
      this.announceText(text)
    }
  }

  addAnswer(answer: string, speaker: string | undefined): void {
    const newEntry = this.createMessageElement(answer, speaker)
    newEntry.classList.add('text', 'right')

    // Add button container for user messages
    const buttonContainer = document.createElement('div')
    buttonContainer.classList.add('message-button-container')

    // Create play button
    const playButton = this.createPlayButton(answer)
    buttonContainer.appendChild(playButton)

    // Create copy button
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

    buttonContainer.appendChild(copyButton)
    newEntry.appendChild(buttonContainer)

    this.appendMessageElement(newEntry)
  }

  private copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).catch((err) => console.error('Failed to copy text: ', err))
  }

  private createPlayButton(text: string): HTMLButtonElement {
    const playButton = document.createElement('button')
    playButton.classList.add('play-button')
    playButton.title = 'Play message'
    playButton.textContent = '▶️'

    playButton.addEventListener('click', (event) => {
      event.stopPropagation()
      this.togglePlayback(text, playButton)
    })

    return playButton
  }

  private togglePlayback(text: string, button: HTMLButtonElement): void {
    if (this.voiceSynthesis.isSpeaking() && this.currentPlayingButton === button) {
      // Stop current message
      console.log('[CHAT] Stopping current playback')
      this.voiceSynthesis.stopSpeech()
      this.resetPlayButton(button)
      this.currentPlayingButton = null
    } else {
      // Stop any other playing message et reset previous button
      console.log('[CHAT] Starting new playback, stopping previous if any')
      this.voiceSynthesis.stopSpeech()

      // Reset tous les boutons pour être sûr
      this.resetAllPlayButtons()

      // Start new message avec callback
      const plainText = this.voiceSynthesis.extractPlainText(text)

      // Créer une callback spécifique à ce bouton
      const onEndCallback = () => {
        console.log('[CHAT] Playback ended, resetting button')
        // Vérifier que ce bouton est toujours le bouton actif
        if (this.currentPlayingButton === button) {
          this.resetPlayButton(button)
          this.currentPlayingButton = null
        }
      }

      this.voiceSynthesis.speak(plainText, onEndCallback)

      // Mettre à jour l'état APRES avoir lancé la synthèse
      this.currentPlayingButton = button
      button.textContent = '⏸️'
      button.title = 'Stop playback'
      console.log('[CHAT] Button set to pause state')
    }
  }

  private resetPlayButton(button: HTMLButtonElement): void {
    button.textContent = '▶️'
    button.title = 'Play message'
  }

  private resetAllPlayButtons(): void {
    // Reset tous les boutons play dans le chat
    const allPlayButtons = document.querySelectorAll('.play-button')
    allPlayButtons.forEach((btn) => {
      const button = btn as HTMLButtonElement
      button.textContent = '▶️'
      button.title = 'Play message'
    })
    this.currentPlayingButton = null
  }

  private checkStateConsistency(): void {
    if (!this.voiceSynthesis.isSpeaking() && this.currentPlayingButton) {
      console.log('[CHAT] State inconsistency detected: no speech but button still active, fixing...')
      this.resetAllPlayButtons()
    }
  }

  private addUserMessage(event: MessageEvent): void {
    const newEntry = this.createRichMessageElement(event)
    newEntry.classList.add('text', 'right')

    // Add button container
    const buttonContainer = document.createElement('div')
    buttonContainer.classList.add('message-button-container')

    // Create play button for text content
    const textContent = event.getTextContent()
    if (textContent) {
      const playButton = this.createPlayButton(textContent)
      buttonContainer.appendChild(playButton)
    }

    // Create copy button
    const copyButton = document.createElement('button')
    copyButton.classList.add('copy-button')
    copyButton.title = 'Copy raw message'
    copyButton.textContent = '📋'
    copyButton.addEventListener('click', (event) => {
      event.stopPropagation()
      this.copyToClipboard(textContent)

      const clickedButton = event.currentTarget as HTMLButtonElement
      if (clickedButton) {
        document.querySelectorAll('.copy-button.active').forEach((btn) => {
          btn.classList.remove('active')
          btn.textContent = '📋'
        })

        clickedButton.classList.add('active')
        clickedButton.textContent = '✓'

        setTimeout(() => {
          clickedButton.classList.remove('active')
          clickedButton.textContent = '📋'
        }, 2000)
      }
    })

    buttonContainer.appendChild(copyButton)
    newEntry.appendChild(buttonContainer)

    this.appendMessageElement(newEntry)
  }

  private addAssistantMessage(event: MessageEvent): void {
    const newEntry = this.createRichMessageElement(event)
    newEntry.classList.add('text', 'left')
    newEntry.addEventListener('click', () => {
      this.voiceSynthesis.stopSpeech()
    })

    // Add button container
    const buttonContainer = document.createElement('div')
    buttonContainer.classList.add('message-button-container')

    // Create play button for text content
    const textContent = event.getTextContent()
    if (textContent) {
      const playButton = this.createPlayButton(textContent)
      buttonContainer.appendChild(playButton)
    }

    // Create copy button
    const copyButton = document.createElement('button')
    copyButton.classList.add('copy-button')
    copyButton.title = 'Copy raw response'
    copyButton.textContent = '📋'
    copyButton.addEventListener('click', (event) => {
      event.stopPropagation()
      this.copyToClipboard(textContent)

      const clickedButton = event.currentTarget as HTMLButtonElement
      if (clickedButton) {
        document.querySelectorAll('.copy-button.active').forEach((btn) => {
          btn.classList.remove('active')
          btn.textContent = '📋'
        })

        clickedButton.classList.add('active')
        clickedButton.textContent = '✓'

        setTimeout(() => {
          clickedButton.classList.remove('active')
          clickedButton.textContent = '📋'
        }, 2000)
      }
    })

    buttonContainer.appendChild(copyButton)
    newEntry.appendChild(buttonContainer)

    this.appendMessageElement(newEntry)

    // Announce if enabled and recent
    const audioEnabled = getPreference<boolean>('voiceAnnounceEnabled', false) || false
    if (audioEnabled && this.isMessageRecentEnoughForAnnouncement(event.timestamp)) {
      this.announceText(textContent)
    }
  }

  private createRichMessageElement(event: MessageEvent): HTMLDivElement {
    const newEntry = document.createElement('div')
    newEntry.classList.add('message')

    // Add speaker
    const speakerElement = document.createElement('div')
    speakerElement.classList.add('speaker')
    speakerElement.textContent = event.name
    newEntry.appendChild(speakerElement)

    // Create content container
    const contentContainer = document.createElement('div')
    contentContainer.classList.add('message-content')

    if (typeof event.content === 'string') {
      // Simple string content - parse as markdown
      const parsed = marked.parse(event.content)
      if (parsed instanceof Promise) {
        parsed.then((html) => {
          contentContainer.innerHTML = html
        })
      } else {
        contentContainer.innerHTML = parsed
      }
    } else {
      // Rich content - handle each part
      event.content.forEach((content) => {
        if (content.type === 'text') {
          const textDiv = document.createElement('div')
          textDiv.classList.add('text-content')
          const parsed = marked.parse(content.content)
          if (parsed instanceof Promise) {
            parsed.then((html) => {
              textDiv.innerHTML = html
            })
          } else {
            textDiv.innerHTML = parsed
          }
          contentContainer.appendChild(textDiv)
        } else if (content.type === 'image') {
          const imageContainer = document.createElement('div')
          imageContainer.classList.add('image-content')

          const img = document.createElement('img')
          img.src = `data:${content.mimeType};base64,${content.content}`
          img.alt = content.source || 'Image'
          img.classList.add('chat-image')

          // Add max dimensions to prevent huge images
          img.style.maxWidth = '100%'
          img.style.maxHeight = '500px'
          img.style.objectFit = 'contain'

          // Add click to open in new tab
          img.addEventListener('click', (e) => {
            e.stopPropagation()
            window.open(img.src, '_blank')
          })

          imageContainer.appendChild(img)

          // Add source info if available
          if (content.source) {
            const sourceDiv = document.createElement('div')
            sourceDiv.classList.add('image-source')
            sourceDiv.textContent = `Source: ${content.source}`
            sourceDiv.style.fontSize = '0.8em'
            sourceDiv.style.color = 'var(--color-text-secondary)'
            sourceDiv.style.marginTop = '4px'
            imageContainer.appendChild(sourceDiv)
          }

          contentContainer.appendChild(imageContainer)
        }
      })
    }

    newEntry.appendChild(contentContainer)
    return newEntry
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

  addError(error: string, level: 'Error' | 'Warning' = 'Error'): void {
    this.setThinking(false)
    const errorEntry = document.createElement('div')

    // Create an error icon
    const errorIcon = document.createElement('span')
    errorIcon.textContent = level === 'Error' ? '❌' : '⚠️'
    errorEntry.appendChild(errorIcon)

    // Create the error text
    const errorText = document.createElement('span')
    errorText.textContent = `${level}: ${error}`
    errorEntry.appendChild(errorText)

    // Add styling
    errorEntry.style.color =
      level === 'Error'
        ? '#e74c3c' // Red color
        : '#e7ab3c' // Amber color
    errorEntry.style.background =
      level === 'Error'
        ? '#ffeeee' // Light red background
        : '#fffaee' // Light yellow-ish background
    errorEntry.style.padding = '10px'
    errorEntry.style.margin = '10px 0'
    errorEntry.style.borderRadius = '4px'
    errorEntry.style.border = `1px solid ${level === 'Error' ? '#e74c3c' : '#e7ab3c'}`

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

  private isMessageRecentEnoughForAnnouncement(messageTimestamp?: string): boolean {
    if (!messageTimestamp) {
      return true // If no timestamp, assume it's recent
    }

    try {
      // Extract ISO date part before the random suffix
      // Format: "2024-01-15T10:30:45.123Z-abc12" -> "2024-01-15T10:30:45.123Z"
      const isoDatePart = messageTimestamp.split('-').slice(0, -1).join('-')

      const messageTime = new Date(isoDatePart).getTime()
      const now = Date.now()
      const timeDiff = now - messageTime

      return timeDiff <= MESSAGE_FRESHNESS_THRESHOLD
    } catch (error) {
      return true // If parsing fails, assume it's recent
    }
  }

  private announceText(text: string): void {
    const mode = (getPreference<string>('voiceMode', 'speech') as 'speech' | 'notification') || 'speech'

    if (mode === 'notification') {
      this.voiceSynthesis.ding()
      return
    }

    let plainText = this.voiceSynthesis.extractPlainText(text)

    // Check if we should read full text or just the beginning
    let textToSpeak: string

    if (this.readFullText) {
      // Read the entire text
      textToSpeak = plainText
    } else {
      // Read only the first few paragraphs
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
      textToSpeak = speech.text
    }

    if (!textToSpeak.trim()) {
      console.log('[VOICE] No text to speak after processing!')
      return
    }

    // Use the voice synthesis component
    this.voiceSynthesis.speak(textToSpeak)
  }
}
