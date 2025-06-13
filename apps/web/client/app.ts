import { ChatTextareaComponent } from './chat-textarea/chat-textarea.component'
import { ChoiceSelectComponent } from './choice-select/choice-select.component'
import { ChatHistoryComponent } from './chat-history/chat-history.component'
import { buildCodayEvent, CodayEvent, ErrorEvent } from '@coday/coday-events'
import { CodayEventHandler } from './utils/coday-event-handler'
import { HeaderComponent } from './header/header.component'
import { getPreference, setPreference } from './utils/preferences'
import { voiceSynthesis } from './preferences'

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

const chatHistory = new ChatHistoryComponent(handleStop, voiceSynthesis)
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

// Set initial toggle state based on stored preference
const useEnterToSend = getPreference('useEnterToSend', false)
enterToSendToggle.checked = useEnterToSend !== undefined ? useEnterToSend : false

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
