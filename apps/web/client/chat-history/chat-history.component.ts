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

export class ChatHistoryComponent implements CodayEventHandler {
  private chatHistory: HTMLDivElement
  private readonly thinkingDots: HTMLDivElement
  private readonly stopButton: HTMLButtonElement
  private history = new Map<string, CodayEvent>()
  private thinkingTimeout: any
  private readonly onStopCallback: () => void
  private currentSpeech: SpeechSynthesisUtterance | null = null

  constructor(onStopCallback: () => void) {
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
        if (this.currentSpeech) {
          speechSynthesis.cancel()
          this.currentSpeech = null
        }
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
      console.log('[VOICE] Agent response detected, speaker:', speaker)
      const isEnabled = this.isVoiceAnnounceEnabled()
      console.log('[VOICE] Voice announce enabled:', isEnabled)
      if (isEnabled) {
        console.log('[VOICE] Calling announceText from addText')
        this.announceText(text)
      }
    } else {
      console.log('[VOICE] No speaker detected, skipping announce')
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

  private getVoiceLanguage(): string {
    const language = getPreference<string>('voiceLanguage', 'en-US') || 'en-US'
    console.log('[VOICE] getVoiceLanguage from preferences:', language)
    return language
  }

  private extractPlainText(markdown: string): string {
    // Remove basic markdown formatting
    return markdown
      .replace(/\*\*(.*?)\*\*/g, '$1') // Bold
      .replace(/\*(.*?)\*/g, '$1') // Italic
      .replace(/`(.*?)`/g, '$1') // Code
      .replace(/#{1,6}\s*(.*)/g, '$1') // Headers
      .replace(/\[(.*?)\]\(.*?\)/g, '$1') // Links
      .replace(/\n/g, ' ') // Line breaks
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
    console.log('[VOICE] announceText called with text length:', text.length)
    const mode = this.getVoiceMode()
    console.log('[VOICE] announceText - mode detected:', mode)

    if (mode === 'notification') {
      console.log('[VOICE] Playing notification sound')
      this.playNotificationSound()
      return
    }

    console.log('[VOICE] Proceeding with speech synthesis')

    // Speech synthesis mode
    if (!('speechSynthesis' in window)) {
      console.warn('Speech synthesis not available, falling back to notification')
      this.playNotificationSound()
      return
    }

    // Check if voices are loaded
    const voices = speechSynthesis.getVoices()
    if (voices.length === 0) {
      console.warn('[VOICE] No voices loaded yet, trying to trigger voiceschanged event')
      const currentLanguage = this.getVoiceLanguage()
      // Try to force voices to load
      speechSynthesis.onvoiceschanged = () => {
        console.log('[VOICE] Voices loaded, retrying speech')
        this.announceTextWithVoices(text, currentLanguage)
      }
      // Also try a small delay
      setTimeout(() => {
        if (speechSynthesis.getVoices().length > 0) {
          console.log('[VOICE] Voices loaded after delay, retrying speech')
          this.announceTextWithVoices(text, currentLanguage)
        }
      }, 100)
      return
    }

    const currentLanguage = this.getVoiceLanguage()
    this.announceTextWithVoices(text, currentLanguage)
  }

  private announceTextWithVoices(text: string, language: string): void {
    console.log('[VOICE] Starting voice announcement for language:', language)

    // Stop any current speech
    if (this.currentSpeech) {
      speechSynthesis.cancel()
      this.currentSpeech = null
    }

    // Extract plain text and limit to 200 characters
    let plainText = this.extractPlainText(text)
    if (plainText.length > 200) {
      plainText = plainText.substring(0, 200) + '...'
    }

    console.log('[VOICE] Plain text to speak:', JSON.stringify(plainText))
    console.log('[VOICE] Plain text length:', plainText.length)
    
    if (plainText.trim()) {
      this.speakWithVoiceSelection(plainText, language)
    } else {
      console.log('[VOICE] No text to speak after processing!')
    }
  }

  private speakWithVoiceSelection(text: string, language: string): void {
    console.log('[VOICE] Starting speech with language:', language)
    
    const utterance = new SpeechSynthesisUtterance(text)
    utterance.rate = 1.0
    utterance.volume = 1.0
    
    // Find the best voice for the requested language
    const selectedVoice = this.findBestVoice(language)
    
    if (selectedVoice) {
      utterance.voice = selectedVoice
      utterance.lang = selectedVoice.lang
      console.log('[VOICE] Using voice:', selectedVoice.name, 'for language:', selectedVoice.lang)
    } else {
      // Fallback: try the language without specific voice
      utterance.lang = language
      console.log('[VOICE] No specific voice found, using system default for:', language)
    }
    
    this.currentSpeech = utterance
    
    const startTime = Date.now()
    
    utterance.onstart = () => {
      console.log('[VOICE] Speech started with text:', JSON.stringify(utterance.text))
      console.log('[VOICE] Speech config:', {
        voice: utterance.voice?.name,
        lang: utterance.lang,
        rate: utterance.rate,
        volume: utterance.volume,
        textLength: utterance.text.length
      })
    }
    
    utterance.onend = () => {
      const duration = Date.now() - startTime
      console.log('[VOICE] Speech ended after', duration, 'ms')
      
      // Detect suspiciously short speech (likely failed silently)
      const expectedMinDuration = utterance.text.length * 50 // ~50ms per character minimum
      if (duration < expectedMinDuration && language !== 'en-US') {
        console.log('[VOICE] Speech ended too quickly (' + duration + 'ms for ' + utterance.text.length + ' chars), trying English fallback')
        this.currentSpeech = null
        setTimeout(() => this.speakWithVoiceSelection(text, 'en-US'), 100)
        return
      }
      
      this.currentSpeech = null
    }
    
    utterance.onerror = (event) => {
      console.error('[VOICE] Speech error:', event.error)
      this.currentSpeech = null
      
      // Auto-fallback to English if the requested language fails
      if (language !== 'en-US' && event.error !== 'interrupted') {
        console.log('[VOICE] Language failed, trying English fallback')
        setTimeout(() => this.speakWithVoiceSelection(text, 'en-US'), 100)
      }
    }
    
    speechSynthesis.speak(utterance)
  }
  
  private findBestVoice(language: string): SpeechSynthesisVoice | null {
    const voices = speechSynthesis.getVoices()
    const langLower = language.toLowerCase()
    const langCode = langLower.split('-')[0] // 'fr' from 'fr-FR'
    
    console.log('[VOICE] Looking for voice matching:', language)
    
    // Debug: Show available voices for this language
    const availableVoices = voices.filter(v => v.lang.toLowerCase().startsWith(langCode))
    console.log('[VOICE] Available voices for', langCode + ':')
    availableVoices.forEach(v => console.log('[VOICE] -', v.name, '(' + v.lang + ')', v.default ? '[DEFAULT]' : ''))
    
    // Priority 1: Known high-quality voices for French (macOS system voices)
    if (langCode === 'fr') {
      // Thomas and Marie are the best French voices on macOS
      const preferredNames = ['Thomas', 'Marie', 'Amélie', 'Audrey']
      for (const name of preferredNames) {
        const voice = voices.find(v => v.name === name && v.lang.toLowerCase().startsWith('fr'))
        if (voice) {
          console.log('[VOICE] Found preferred French voice:', voice.name, '(' + voice.lang + ')')
          return voice
        }
      }
      
      // If Google French is available and working, use it
      const googleFrench = voices.find(v => v.name === 'Google français')
      if (googleFrench) {
        console.log('[VOICE] Found Google French voice:', googleFrench.name, '(' + googleFrench.lang + ')')
        return googleFrench
      }
    }
    
    // Priority 2: Exact language match (case-insensitive)
    let voice = voices.find(v => v.lang.toLowerCase() === langLower)
    if (voice) {
      console.log('[VOICE] Found exact language match:', voice.name, 'with lang:', voice.lang)
      return voice
    }
    
    // Priority 3: Language code match (e.g., 'fr' matches 'fr-FR', 'fr-CA')
    // Prefer system voices over online voices for reliability
    const systemVoice = voices.find(v => 
      v.lang.toLowerCase().startsWith(langCode) && 
      !v.name.toLowerCase().includes('google') &&
      !v.name.toLowerCase().includes('chrome')
    )
    if (systemVoice) {
      console.log('[VOICE] Found system voice for language code:', systemVoice.name, 'with lang:', systemVoice.lang)
      return systemVoice
    }
    
    // Priority 4: Any voice matching the language code
    voice = voices.find(v => v.lang.toLowerCase().startsWith(langCode))
    if (voice) {
      console.log('[VOICE] Found any voice for language code:', voice.name, 'with lang:', voice.lang)
      return voice
    }
    
    // Priority 5: Default voice as last resort
    voice = voices.find(v => v.default)
    if (voice) {
      console.log('[VOICE] Using default voice as fallback:', voice.name)
      return voice
    }
    
    console.log('[VOICE] No suitable voice found')
    return null
  }
}
