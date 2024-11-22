import {AnswerEvent, CodayEvent, TextEvent, ThinkingEvent} from "shared/coday-events"
import {CodayEventHandler} from "../utils/coday-event-handler"

export class ChatHistoryComponent implements CodayEventHandler {
  private chatHistory: HTMLDivElement
  private readonly thinkingDots: HTMLDivElement
  private readonly stopButton: HTMLButtonElement
  private history = new Map<string, CodayEvent>()
  private thinkingTimeout: any
  private readonly onStopCallback: () => void
  
  constructor(onStopCallback: () => void) {
    this.chatHistory = document.getElementById("chat-history") as HTMLDivElement
    this.thinkingDots = document.getElementById("thinking-dots") as HTMLDivElement
    this.stopButton = this.thinkingDots.querySelector(".stop-button") as HTMLButtonElement
    this.onStopCallback = onStopCallback
    
    // Bind stop button click event
    if (this.stopButton) {
      this.stopButton.addEventListener("click", () => this.onStopCallback())
    }
  }
  
  handle(event: CodayEvent): void {
    this.history.set(event.timestamp, event)
    if (event instanceof TextEvent) {
      if (event.speaker) {
        this.addText(event.text, event.speaker)
      } else {
        this.addTechnical(event.text)
      }
    }
    if (event instanceof AnswerEvent) {
      this.addAnswer(event.answer, event.invite)
    }
    if (event instanceof ErrorEvent) {
      this.addError(event.error)
    }
    if (event instanceof ThinkingEvent) {
      this.setThinking(true)
    }
  }
  
  private setThinking(value: boolean): void {
    if (!this.thinkingDots) {
      return
    }
    clearTimeout(this.thinkingTimeout)
    if (value) {
      this.thinkingTimeout = setTimeout(() => {
        this.thinkingDots.classList.toggle("visible", false)
      }, ThinkingEvent.debounce + 1000)
      
    }
    this.thinkingDots.classList.toggle("visible", value)
  }
  
  addTechnical(text: string): void {
    const newEntry = this.createMessageElement(text, undefined)
    newEntry.classList.add("technical", "right")
    this.appendMessageElement(newEntry)
  }
  
  addText(text: string, speaker: string | undefined): void {
    const newEntry = this.createMessageElement(text, speaker)
    newEntry.classList.add("text", "right")
    this.appendMessageElement(newEntry)
    
  }
  
  addAnswer(answer: string, speaker: string | undefined): void {
    const newEntry = this.createMessageElement(answer, speaker)
    newEntry.classList.add("text", "left")
    this.appendMessageElement(newEntry)
  }
  
  private createMessageElement(content: string, speaker: string | undefined): HTMLDivElement {
    const newEntry = document.createElement("div")
    newEntry.classList.add("message")
    if (speaker) {
      const speakerElement = document.createElement("div")
      speakerElement.classList.add("speaker")
      speakerElement.textContent = speaker
      newEntry.appendChild(speakerElement)
      const parsed = marked.parse(content)
      parsed instanceof Promise
        ? parsed.then(html => newEntry.appendChild(this.buildTextElement(html)))
        : newEntry.appendChild(this.buildTextElement(parsed))
    } else {
      newEntry.innerHTML = content
    }
    return newEntry
  }
  
  private appendMessageElement(element: HTMLDivElement): void {
    this.chatHistory?.appendChild(element)
    setTimeout(() => { // delay for scrolling once the next question has arrived
      this.chatHistory?.scrollTo(0, this.chatHistory.scrollHeight)
    }, 300)
  }
  
  private buildTextElement(innerHTML: string): HTMLDivElement {
    const textElement = document.createElement("div")
    textElement.innerHTML = innerHTML
    return textElement
  }
  
  
  addError(error: string): void {
    this.setThinking(false)
    const errorEntry = document.createElement("div")
    errorEntry.textContent = error
    errorEntry.style.color = "red"
    this.chatHistory?.appendChild(errorEntry)
  }
}

