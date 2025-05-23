
import { CodayEvent, InviteEvent } from '@coday/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'
import { getPreference } from '../utils/preferences'

export class ChatTextareaComponent implements CodayEventHandler {
  private chatForm: HTMLFormElement
  private chatTextarea: HTMLTextAreaElement
  private chatLabel: HTMLLabelElement
  private submitButton: HTMLButtonElement
  private inviteEvent: InviteEvent | undefined
  private readonly os: 'mac' | 'non-mac'

  // Command history for arrow key navigation
  private promptHistory: string[] = []
  private historyIndex: number = -1
  private tempInput: string = ''

  /**
   * Detect operating system for keyboard shortcuts
   */
  private detectOS(): 'mac' | 'non-mac' {
    return navigator.platform.toLowerCase().includes('mac') ? 'mac' : 'non-mac'
  }

  /**
   * Update the send button label based on preferences and OS
   */
  private updateSendButtonLabel(): void {
    const useEnterToSend = getPreference<boolean>('useEnterToSend', false)

    if (!this.submitButton) return

    if (useEnterToSend) {
      this.submitButton.innerHTML = 'SEND <br/><br/>enter'
    } else {
      if (this.os === 'mac') {
        this.submitButton.innerHTML = 'SEND <br/><br/>⌘ + enter'
      } else {
        this.submitButton.innerHTML = 'SEND <br/><br/>Ctrl + enter'
      }
    }
  }

  constructor(private postEvent: (event: CodayEvent) => Promise<Response>) {
    // Detect OS once during initialization
    this.os = this.detectOS()

    this.chatForm = document.getElementById('chat-form') as HTMLFormElement
    this.chatTextarea = document.getElementById('chat-input') as HTMLTextAreaElement
    this.chatLabel = document.getElementById('chat-label') as HTMLLabelElement
    this.submitButton = document.getElementById('send-button') as HTMLButtonElement

    this.chatForm.onsubmit = async (event) => {
      event.preventDefault()
      await this.submit()
    }

    this.chatTextarea.addEventListener('keydown', async (e) => {
      // Disable history navigation if a default value is set
      if (this.inviteEvent?.defaultValue) return

      const useEnterToSend = getPreference<boolean>('useEnterToSend', false)

      // Handle Enter key for submission
      if (useEnterToSend) {
        // When Enter to Send is enabled, only handle plain Enter (without Shift)
        if (e.key === 'Enter' && !e.shiftKey && !e.metaKey && !e.ctrlKey) {
          e.preventDefault()
          await this.submit()
        }
      } else {
        // When Enter to Send is disabled, use OS-specific shortcuts
        if (e.key === 'Enter' && ((this.os === 'mac' && e.metaKey) || (this.os === 'non-mac' && e.ctrlKey))) {
          e.preventDefault()
          await this.submit()
        }
      }

      // Handle Up/Down arrow keys for history navigation
      if (e.key === 'ArrowUp') {
        // Only navigate history if cursor is at the first line
        if (this.isCursorAtFirstLine()) {
          e.preventDefault()
          this.navigateHistory('up')
        }
      } else if (e.key === 'ArrowDown') {
        // Only navigate history if cursor is at the last line
        if (this.isCursorAtLastLine()) {
          e.preventDefault()
          this.navigateHistory('down')
        }
      }
    })

    // Update the button label initially
    this.updateSendButtonLabel()

    // Listen for preference changes
    window.addEventListener('storage', (event) => {
      if (event.key === 'coday-preferences') {
        this.updateSendButtonLabel()
      }
    })
  }

  handle(event: CodayEvent): void {
    if (event instanceof InviteEvent) {
      this.inviteEvent = event
      // Parse markdown for the chat label
      const parsed = marked.parse(this.inviteEvent.invite)
      if (parsed instanceof Promise) {
        parsed.then((html) => (this.chatLabel.innerHTML = html))
      } else {
        this.chatLabel.innerHTML = parsed
      }
      this.chatForm.style.display = 'block'
      this.chatTextarea.focus()
      if (this.inviteEvent.defaultValue) {
        console.log(`handling defaultValue: ${this.inviteEvent.defaultValue}`)
        this.chatTextarea.value = this.inviteEvent.defaultValue
      }
    }
  }

  /**
   * Check if cursor is at the first line of text
   */
  private isCursorAtFirstLine(): boolean {
    const text = this.chatTextarea.value
    const cursorPos = this.chatTextarea.selectionStart

    // If cursor is at position 0, it's definitely at the first line
    if (cursorPos === 0) return true

    // Otherwise, check if there are any newline characters before the cursor
    const textBeforeCursor = text.substring(0, cursorPos)
    return textBeforeCursor.indexOf('\n') === -1
  }

  /**
   * Check if cursor is at the last line of text
   */
  private isCursorAtLastLine(): boolean {
    const text = this.chatTextarea.value
    const cursorPos = this.chatTextarea.selectionStart

    // If cursor is at the end, it's definitely at the last line
    if (cursorPos === text.length) return true

    // Otherwise, check if there are any newline characters after the cursor
    const textAfterCursor = text.substring(cursorPos)
    return textAfterCursor.indexOf('\n') === -1
  }

  /**
   * Navigate through command history using up/down arrows
   */
  private navigateHistory(direction: 'up' | 'down'): void {
    // If history is empty, do nothing
    if (this.promptHistory.length === 0) return

    // On first up arrow press, save current input
    if (direction === 'up' && this.historyIndex === -1) {
      this.tempInput = this.chatTextarea.value
    }

    if (direction === 'up' && this.historyIndex < this.promptHistory.length - 1) {
      // Move up in history
      this.historyIndex++
      this.chatTextarea.value = this.promptHistory[this.promptHistory.length - 1 - this.historyIndex]
      this.moveCursorToEnd()
    } else if (direction === 'down' && this.historyIndex > -1) {
      // Move down in history
      this.historyIndex--

      if (this.historyIndex === -1) {
        // We've reached the end of history, restore the temporary input
        this.chatTextarea.value = this.tempInput
      } else {
        this.chatTextarea.value = this.promptHistory[this.promptHistory.length - 1 - this.historyIndex]
      }
      this.moveCursorToEnd()
    }
  }

  /**
   * Move cursor to the end of the textarea
   */
  private moveCursorToEnd(): void {
    const length = this.chatTextarea.value.length
    this.chatTextarea.setSelectionRange(length, length)
  }

  private async submit(): Promise<void> {
    const inputValue = this.chatTextarea.value.trim()
    const answer = this.inviteEvent?.buildAnswer(inputValue)
    this.chatTextarea.value = ''

    if (!answer) {
      return
    }

    try {
      this.chatForm.style.display = 'none'
      const response = await this.postEvent(answer)

      if (response.ok) {
        // Add to history if not empty and not a duplicate of the last entry
        if (
          inputValue &&
          (this.promptHistory.length === 0 || this.promptHistory[this.promptHistory.length - 1] !== inputValue)
        ) {
          this.promptHistory.push(inputValue)
        }

        this.historyIndex = -1 // Reset history index
        this.tempInput = ''
      } else {
        this.chatForm.style.display = 'block'
        this.chatTextarea.focus()
        console.error('Failed to send message.')
      }
    } catch (error) {
      console.error('Error occurred while sending message:', error)
    }
  }
}
