import { ChatTextareaComponent } from './chat-textarea/chat-textarea.component'
import { ChoiceSelectComponent } from './choice-select/choice-select.component'
import { ChatHistoryComponent } from './chat-history/chat-history.component'

import { buildCodayEvent, CodayEvent, ErrorEvent } from '@coday/coday-events'
import { CodayEventHandler } from './utils/coday-event-handler'
import { HeaderComponent } from './header/header.component'
import { getPreference, setPreference } from './utils/preferences'

// Debug logging function
function debugLog(context: string, ...args: any[]) {
  //console.log(`[DEBUG ${context}]`, ...args)
}

// Add global test function
declare global {
  interface Window {
    triggerTestDisconnect: () => void
  }
}

/**
 * This is a nice temporary workaround for session re-connect
 * Target behavior would be to have frontend routes /project/{projectId} and /project/{projectId}/thread/{threadId} to manage state properly, but backend is not ready yet.
 */
function getOrCreateClientId(): string {
  // Check URL parameters first
  const params = new URLSearchParams(window.location.search)
  let clientId = params.get('clientId')

  if (!clientId) {
    // Generate new if not found
    clientId = Math.random().toString(36).substring(2, 15)

    // Update URL without page reload
    const newUrl = new URL(window.location.href)
    newUrl.searchParams.set('clientId', clientId)
    window.history.pushState({}, '', newUrl)
  }

  return clientId
}

const clientId = getOrCreateClientId()
debugLog('INIT', `Session started with clientId: ${clientId}`)

function postEvent(event: CodayEvent): Promise<Response> {
  debugLog('API', 'Posting event:', event)
  return fetch(`/api/message?clientId=${clientId}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(event),
  })
}

// Define stop callback
const handleStop = () => {
  debugLog('API', 'Stopping execution')
  fetch(`/api/stop?clientId=${clientId}`, { method: 'POST' }).catch((error) =>
    console.error('Error stopping execution:', error)
  )
}

const chatHistory = new ChatHistoryComponent(handleStop)
const chatInputComponent = new ChatTextareaComponent(postEvent)
const choiceInputComponent = new ChoiceSelectComponent(postEvent)

const components: CodayEventHandler[] = [chatInputComponent, choiceInputComponent, chatHistory, new HeaderComponent()]
let eventSource: EventSource | null = null
let reconnectAttempts = 0
const MAX_RECONNECT_ATTEMPTS = 3
const RECONNECT_DELAY = 2000 // 2 seconds

function setupEventSource() {
  debugLog('SSE', 'Setting up new EventSource')
  if (eventSource) {
    debugLog('SSE', 'Closing existing EventSource')
    eventSource.close()
  }

  eventSource = new EventSource(`/events?clientId=${clientId}`)

  eventSource.onmessage = (event) => {
    debugLog('SSE', 'Received message:', event.data)
    reconnectAttempts = 0 // Reset on successful message
    try {
      const data = JSON.parse(event.data)
      const codayEvent = buildCodayEvent(data)
      if (codayEvent) {
        debugLog('EVENT', 'Processing event:', codayEvent)
        components.forEach((c) => c.handle(codayEvent))
      }
    } catch (error: any) {
      console.error('Could not parse event', event)
    }
  }

  eventSource.onopen = () => {
    debugLog('SSE', 'Connection established')
    reconnectAttempts = 0 // Reset on successful connection
  }

  eventSource.onerror = (error) => {
    debugLog('SSE', 'EventSource error:', error)

    if (eventSource?.readyState === EventSource.CLOSED) {
      debugLog('SSE', 'Connection closed')
      if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
        debugLog('SSE', `Attempting reconnect ${reconnectAttempts + 1}/${MAX_RECONNECT_ATTEMPTS}`)
        components.forEach((c) =>
          c.handle(
            new ErrorEvent({
              error: new Error(
                `Connection lost. Attempting to reconnect (${reconnectAttempts + 1}/${MAX_RECONNECT_ATTEMPTS})...`
              ),
            })
          )
        )

        setTimeout(() => {
          reconnectAttempts++
          setupEventSource()
        }, RECONNECT_DELAY)
      } else {
        debugLog('SSE', 'Max reconnection attempts reached')
        components.forEach((c) =>
          c.handle(new ErrorEvent({ error: new Error('Connection lost permanently. Please refresh the page.') }))
        )
      }
    }
  }
}

// Simple options panel setup
const optionsButton = document.getElementById('options-button') as HTMLButtonElement
const optionsPanel = document.getElementById('options-panel') as HTMLDivElement
const enterToSendToggle = document.getElementById('enter-to-send-toggle') as HTMLInputElement
const themeLight = document.getElementById('theme-light') as HTMLInputElement
const themeDark = document.getElementById('theme-dark') as HTMLInputElement
const themeSystem = document.getElementById('theme-system') as HTMLInputElement
const voiceLanguageSelect = document.getElementById('voice-language-select') as HTMLSelectElement
const voiceAnnounceToggle = document.getElementById('voice-announce-toggle') as HTMLInputElement
const voiceModeSelect = document.getElementById('voice-mode-select') as HTMLSelectElement
const voiceOptions = document.getElementById('voice-options') as HTMLDivElement
const voiceTestButton = document.getElementById('voice-test-button') as HTMLButtonElement
const voiceSelectionContainer = document.getElementById('voice-selection-container') as HTMLDivElement
const voiceSelect = document.getElementById('voice-select') as HTMLSelectElement
const voiceTestSelected = document.getElementById('voice-test-selected') as HTMLButtonElement

// Set initial toggle state based on stored preference
const useEnterToSend = getPreference('useEnterToSend', false)
enterToSendToggle.checked = useEnterToSend !== undefined ? useEnterToSend : false

// Set initial voice language based on stored preference
const savedVoiceLanguage = getPreference<string>('voiceLanguage', 'en-US') || 'en-US'
voiceLanguageSelect.value = savedVoiceLanguage

// Set initial voice announce state based on stored preference
const voiceAnnounceEnabled = getPreference('voiceAnnounceEnabled', false)
voiceAnnounceToggle.checked = voiceAnnounceEnabled !== undefined ? voiceAnnounceEnabled : false

// Set initial voice mode based on stored preference
const savedVoiceMode = getPreference<string>('voiceMode', 'speech') || 'speech'
console.log('[VOICE] Loading saved voice mode preference:', savedVoiceMode)
voiceModeSelect.value = savedVoiceMode
console.log('[VOICE] Voice mode select set to:', voiceModeSelect.value)

// Show/hide voice options based on toggle state
function updateVoiceOptionsVisibility() {
  voiceOptions.style.display = voiceAnnounceToggle.checked ? 'block' : 'none'
  updateVoiceSelectionVisibility()
}

// Show/hide voice selection based on mode
function updateVoiceSelectionVisibility() {
  const showVoiceSelection = voiceAnnounceToggle.checked && voiceModeSelect.value === 'speech'
  voiceSelectionContainer.style.display = showVoiceSelection ? 'block' : 'none'
  
  if (showVoiceSelection) {
    populateVoiceSelect()
  }
}

updateVoiceOptionsVisibility()

// Apply theme based on preference or system setting
function applyTheme() {
  const savedTheme = getPreference<string>('theme', 'light')

  // Update radio buttons to match saved preference
  themeLight.checked = savedTheme === 'light'
  themeDark.checked = savedTheme === 'dark'
  themeSystem.checked = savedTheme === 'system'

  if (savedTheme === 'system') {
    // Check system preference
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    if (prefersDark) {
      document.documentElement.setAttribute('data-theme', 'dark')
    } else {
      document.documentElement.removeAttribute('data-theme')
    }
  } else if (savedTheme === 'dark') {
    document.documentElement.setAttribute('data-theme', 'dark')
  } else {
    // Light theme is default
    document.documentElement.removeAttribute('data-theme')
  }
}

// Apply theme on page load
applyTheme()

// Voice management variables
let voicesLoaded = false
let currentVoices: SpeechSynthesisVoice[] = []

// Initialize voice features
if ('speechSynthesis' in window) {
  // Load voices when available
  speechSynthesis.onvoiceschanged = () => {
    console.log('[VOICE] Voices loaded, updating voice selector')
    currentVoices = speechSynthesis.getVoices()
    voicesLoaded = true
    populateVoiceSelect()
  }
  
  // Try to load voices immediately (some browsers have them ready)
  if (speechSynthesis.getVoices().length > 0) {
    currentVoices = speechSynthesis.getVoices()
    voicesLoaded = true
  }
}

// Listen for system theme changes
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
  const savedTheme = getPreference<string>('theme', 'light')
  if (savedTheme === 'system') {
    if (e.matches) {
      document.documentElement.setAttribute('data-theme', 'dark')
    } else {
      document.documentElement.removeAttribute('data-theme')
    }
  }
})

// Toggle panel visibility when options button is clicked
optionsButton.addEventListener('click', (event) => {
  event.stopPropagation()
  const isVisible = optionsPanel.style.display === 'block'
  optionsPanel.style.display = isVisible ? 'none' : 'flex'
})

// Close panel when clicking elsewhere
document.addEventListener('click', (event) => {
  if (!optionsPanel.contains(event.target as Node) && event.target !== optionsButton) {
    optionsPanel.style.display = 'none'
  }
})

// Save preference when toggle changes
enterToSendToggle.addEventListener('change', () => {
  setPreference('useEnterToSend', enterToSendToggle.checked)
})

// Save voice language preference when changed
voiceLanguageSelect.addEventListener('change', () => {
  setPreference('voiceLanguage', voiceLanguageSelect.value)
  // Update voice selector for new language
  populateVoiceSelect()
  // Trigger custom event to notify chat component of language change
  window.dispatchEvent(new CustomEvent('voiceLanguageChanged', { detail: voiceLanguageSelect.value }))
})

// Save voice announce preference when changed
voiceAnnounceToggle.addEventListener('change', () => {
  setPreference('voiceAnnounceEnabled', voiceAnnounceToggle.checked)
  updateVoiceOptionsVisibility()

  // Test when enabling
  if (voiceAnnounceToggle.checked) {
    testVoiceAnnouncement()
  }
})

// Save voice mode preference when changed
voiceModeSelect.addEventListener('change', () => {
  console.log('[VOICE] Voice mode changed to:', voiceModeSelect.value)
  setPreference('voiceMode', voiceModeSelect.value)
  console.log('[VOICE] Voice mode preference saved')
  // Update voice selection visibility
  updateVoiceSelectionVisibility()
  // Test the new mode
  testVoiceAnnouncement()
})

// Voice selection change handler
voiceSelect.addEventListener('change', () => {
  const selectedValue = voiceSelect.value
  console.log('[VOICE] Voice selection changed to:', selectedValue)
  
  // Save the preference
  setPreference('selectedVoice', selectedValue)
  
  // Auto-test the new voice if something is selected
  if (selectedValue) {
    setTimeout(() => testSelectedVoice(), 100)
  }
})

// Test selected voice button
voiceTestSelected.addEventListener('click', () => {
  testSelectedVoice()
})

// Voice test button (now for debugging)
voiceTestButton.addEventListener('click', () => {
  console.log('[VOICE] Debug test button clicked')
  
  if ('speechSynthesis' in window) {
    const voices = speechSynthesis.getVoices()
    const currentLang = voiceLanguageSelect.value
    const langCode = currentLang.toLowerCase().split('-')[0]
    
    console.log('[VOICE] === VOICE DEBUG INFO ===')
    console.log('[VOICE] Current language:', currentLang)
    console.log('[VOICE] Language code:', langCode)
    console.log('[VOICE] Total voices available:', voices.length)
    
    const matchingVoices = voices.filter(v => v.lang.toLowerCase().startsWith(langCode))
    console.log('[VOICE] Voices for', langCode + ':', matchingVoices.length)
    
    matchingVoices.forEach((voice, index) => {
      const local = voice.localService ? 'LOCAL' : 'REMOTE'
      const def = voice.default ? 'DEFAULT' : ''
      console.log(`[VOICE] ${index + 1}. ${voice.name} (${voice.lang}) [${local}] ${def}`)
    })
    
    const selectedVoice = getSelectedVoice()
    console.log('[VOICE] Currently selected voice:', selectedVoice ? selectedVoice.name : 'auto-select')
    
    console.log('[VOICE] =========================')
  }
  
  // Run the normal test
  testVoiceAnnouncement()
})

// Populate voice selector based on selected language
function populateVoiceSelect() {
  if (!voicesLoaded || currentVoices.length === 0) {
    voiceSelect.innerHTML = '<option value="">Loading voices...</option>'
    return
  }
  
  const selectedLanguage = voiceLanguageSelect.value
  const langCode = selectedLanguage.toLowerCase().split('-')[0]
  
  console.log('[VOICE] Populating voices for language:', selectedLanguage, '(code:', langCode + ')')
  
  // Filter voices for the selected language
  const matchingVoices = currentVoices.filter(voice => 
    voice.lang.toLowerCase().startsWith(langCode)
  )
  
  console.log('[VOICE] Found', matchingVoices.length, 'voices for', langCode)
  
  // Clear and populate the select
  voiceSelect.innerHTML = ''
  
  if (matchingVoices.length === 0) {
    voiceSelect.innerHTML = '<option value="">No voices available for this language</option>'
    return
  }
  
  // Add default option
  voiceSelect.innerHTML = '<option value="">🔄 Auto-select best voice</option>'
  
  // Sort voices: local first, then by name
  matchingVoices.sort((a, b) => {
    // Local voices first
    if (a.localService && !b.localService) return -1
    if (!a.localService && b.localService) return 1
    // Then sort by name
    return a.name.localeCompare(b.name)
  })
  
  // Add each voice as an option
  matchingVoices.forEach(voice => {
    const option = document.createElement('option')
    option.value = voice.name + '|' + voice.lang // Store both name and lang
    
    const localMarker = voice.localService ? '🏠' : '☁️'
    const defaultMarker = voice.default ? ' ⭐' : ''
    option.textContent = `${localMarker} ${voice.name} (${voice.lang})${defaultMarker}`
    
    voiceSelect.appendChild(option)
  })
  
  // Restore saved voice preference if it exists
  const savedVoice = getPreference<string>('selectedVoice', '')
  if (savedVoice) {
    // Check if the saved voice is still available
    const savedOption = Array.from(voiceSelect.options).find(option => option.value === savedVoice)
    if (savedOption) {
      voiceSelect.value = savedVoice
      console.log('[VOICE] Restored saved voice preference:', savedVoice)
    }
  }
}

// Get the selected voice object
function getSelectedVoice(): SpeechSynthesisVoice | null {
  const selectedValue = voiceSelect.value
  if (!selectedValue) return null
  
  const [voiceName, voiceLang] = selectedValue.split('|')
  return currentVoices.find(voice => 
    voice.name === voiceName && voice.lang === voiceLang
  ) || null
}

// Test the selected voice
function testSelectedVoice() {
  if (!voicesLoaded) {
    console.warn('[VOICE] Voices not loaded yet')
    return
  }
  
  const selectedVoice = getSelectedVoice()
  const currentLang = voiceLanguageSelect.value
  const langCode = currentLang.toLowerCase().split('-')[0]
  
  // Prepare test text based on language
  const testTexts: Record<string, string> = {
    'fr': 'Bonjour, ceci est un test de la voix sélectionnée.',
    'en': 'Hello, this is a test of the selected voice.',
    'es': 'Hola, esta es una prueba de la voz seleccionada.',
    'de': 'Hallo, dies ist ein Test der ausgewählten Stimme.',
    'it': 'Ciao, questo è un test della voce selezionata.',
    'pt': 'Olá, este é um teste da voz selecionada.'
  }
  
  const testText = testTexts[langCode] || testTexts['en']
  
  console.log('[VOICE] Testing voice:', selectedVoice ? selectedVoice.name : 'auto-selected')
  
  const utterance = new SpeechSynthesisUtterance(testText)
  utterance.volume = 0.8
  utterance.rate = 1.0
  
  if (selectedVoice) {
    utterance.voice = selectedVoice
    utterance.lang = selectedVoice.lang
    console.log('[VOICE] Using specific voice:', selectedVoice.name, '(' + selectedVoice.lang + ')')
  } else {
    utterance.lang = currentLang
    console.log('[VOICE] Using auto-selected voice for:', currentLang)
  }
  
  const startTime = Date.now()
  
  utterance.onstart = () => {
    console.log('[VOICE] Voice test started')
    voiceTestSelected.textContent = '🔊 Playing...'
    voiceTestSelected.disabled = true
  }
  
  utterance.onend = () => {
    const duration = Date.now() - startTime
    console.log('[VOICE] Voice test completed in', duration, 'ms')
    voiceTestSelected.textContent = '🎤 Test Selected Voice'
    voiceTestSelected.disabled = false
    
    // Check if the test seems to have worked
    const expectedMinDuration = testText.length * 40
    if (duration < expectedMinDuration) {
      console.warn('[VOICE] Test was suspiciously short, may have failed silently')
    } else {
      console.log('[VOICE] Test completed successfully')
    }
  }
  
  utterance.onerror = (event) => {
    console.error('[VOICE] Voice test error:', event.error)
    voiceTestSelected.textContent = '❌ Error'
    setTimeout(() => {
      voiceTestSelected.textContent = '🎤 Test Selected Voice'
      voiceTestSelected.disabled = false
    }, 2000)
  }
  
  speechSynthesis.speak(utterance)
}

// Test function for voice announcement - SIMPLIFIED
function testVoiceAnnouncement() {
  const mode = voiceModeSelect.value
  console.log('[VOICE] SIMPLE test with mode:', mode)

  if (mode === 'notification') {
    console.log('[VOICE] Testing notification sound')
    try {
      const audioContext = new (window.AudioContext || (window as any).webkitAudioContext)()
      const oscillator = audioContext.createOscillator()
      const gainNode = audioContext.createGain()

      oscillator.connect(gainNode)
      gainNode.connect(audioContext.destination)

      oscillator.frequency.setValueAtTime(800, audioContext.currentTime)
      gainNode.gain.setValueAtTime(0.1, audioContext.currentTime)
      gainNode.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 0.1)

      oscillator.start(audioContext.currentTime)
      oscillator.stop(audioContext.currentTime + 0.1)
      console.log('[VOICE] Notification sound played')
    } catch (error) {
      console.error('[VOICE] Notification sound test failed:', error)
    }
  } else if (mode === 'speech' && 'speechSynthesis' in window) {
    console.log('[VOICE] Testing voice - using selected voice if available')
    testSelectedVoice()
  } else {
    console.log('[VOICE] Speech synthesis not available')
  }
}

// Handle theme selection
document.querySelectorAll('input[name="theme"]').forEach((input) => {
  input.addEventListener('change', (e) => {
    const target = e.target as HTMLInputElement
    if (target.checked) {
      setPreference('theme', target.value)
      applyTheme()
    }
  })
})

// Initial setup
setupEventSource()
