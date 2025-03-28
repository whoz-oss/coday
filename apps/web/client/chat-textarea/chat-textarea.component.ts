import { CodayEvent, InviteEvent } from '@coday/shared/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'
import { getPreference } from '../utils/preferences'

export class ChatTextareaComponent implements CodayEventHandler {
  private chatForm: HTMLFormElement
  private chatTextarea: HTMLTextAreaElement
  private chatLabel: HTMLLabelElement
  private expandToggle: HTMLButtonElement
  private submitButton: HTMLButtonElement
  private inviteEvent: InviteEvent | undefined
  private readonly os: 'mac' | 'non-mac'

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
    this.submitButton = document.querySelector('.submit') as HTMLButtonElement

    this.chatForm.onsubmit = async (event) => {
      event.preventDefault()
      await this.submit()
    }

    this.expandToggle = document.getElementById('expand-toggle') as HTMLButtonElement
    this.expandToggle.onclick = () => {
      const expanded = this.chatTextarea.classList.toggle('expanded-textarea')
      this.expandToggle.textContent = expanded ? 'Collapse' : 'Expand'
    }

    this.chatTextarea.addEventListener('keydown', async (e) => {
      const useEnterToSend = getPreference<boolean>('useEnterToSend', false)
      
      if ((useEnterToSend && e.key === 'Enter' && !e.shiftKey) || 
          (!useEnterToSend && ((this.os === 'mac' && e.metaKey) || (this.os === 'non-mac' && e.ctrlKey)) && e.key === 'Enter')) {
        e.preventDefault()
        await this.submit()
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
        parsed.then(html => this.chatLabel.innerHTML = html)
      } else {
        this.chatLabel.innerHTML = parsed
      }
      this.chatForm.style.display = 'block'
      this.chatTextarea.focus()
      if (this.inviteEvent.defaultValue) {
        this.chatTextarea.value = this.inviteEvent.defaultValue
      }
    }
  }

  private async submit(): Promise<void> {
    const answer = this.inviteEvent?.buildAnswer(this.chatTextarea.value)
    if (!answer) {
      return
    }
    try {
      this.chatForm.style.display = 'none'
      const response = await this.postEvent(answer)
      if (response.ok) {
        this.chatTextarea.value = ''
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