import { CodayEvent, InviteEvent } from '@coday/shared/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'

export class ChatTextareaComponent implements CodayEventHandler {
  private chatForm: HTMLFormElement
  private chatTextarea: HTMLTextAreaElement
  private chatLabel: HTMLLabelElement
  private expandToggle: HTMLButtonElement
  private inviteEvent: InviteEvent | undefined

  constructor(private postEvent: (event: CodayEvent) => Promise<Response>) {
    this.chatForm = document.getElementById('chat-form') as HTMLFormElement
    this.chatTextarea = document.getElementById('chat-input') as HTMLTextAreaElement
    this.chatLabel = document.getElementById('chat-label') as HTMLLabelElement

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
      if (e.metaKey && e.key === 'Enter') {
        e.preventDefault()
        await this.submit()
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
