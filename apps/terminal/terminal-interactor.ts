import { select } from '@inquirer/prompts'
import chalk from 'chalk'
import { Interactor } from '@coday/model/interactor'
import {
  AnswerEvent,
  ChoiceEvent,
  ErrorEvent,
  InviteEvent,
  TextEvent,
  ThinkingEvent,
  WarnEvent,
} from '@coday/shared/coday-events'
import * as readline from 'node:readline'

export class TerminalInteractor extends Interactor {
  private interactionInProgress = false
  private promptHistory: string[] = []
  private historyIndex = 0
  private rl: readline.Interface | null = null

  constructor() {
    super()
    this.events.subscribe((event) => {
      if (event instanceof TextEvent) {
        this.displayFormattedText(event.text, event.speaker)
      }
      if (event instanceof WarnEvent) {
        console.warn(`${event.warning}\n`)
      }
      if (event instanceof AnswerEvent && !this.interactionInProgress) {
        this.displayFormattedText(event.answer, event.invite)
      }
      if (event instanceof ErrorEvent) {
        const errorMessage = event.error instanceof Error ? event.error.message : String(event.error)
        console.error(chalk.red(`\n❌ Error: ${errorMessage}\n`))
      }
      if (event instanceof InviteEvent) {
        this.handleInviteEvent(event)
      }
      if (event instanceof ChoiceEvent) {
        this.handleChoiceEvent(event)
      }
      if (event instanceof ThinkingEvent) {
        console.log('.')
      }
    })
  }

  private displayFormattedText(text: string, speaker?: string): void {
    const formattedText = speaker ? `\n${chalk.black.bgWhite(speaker)} : ${text}\n` : text
    console.log(formattedText)
  }

  /**
   * Setup readline interface with history support
   */
  private setupReadlineInterface(prompt: string, defaultValue?: string): Promise<string> {
    return new Promise((resolve) => {
      // Create a new readline interface
      this.rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout,
      })
      
      // Set up history navigation
      this.historyIndex = this.promptHistory.length
      let tempInput = defaultValue || ''
      
      // Enable raw mode for better handling of keypress events
      if (process.stdin.isTTY) {
        process.stdin.setRawMode(true)
      }
      
      // Make sure we can process keypresses
      readline.emitKeypressEvents(process.stdin)
      
      // Process key events for history navigation
      const keypressHandler = (str: string, key: any) => {
        if (!key) return
        
        // Handle Up Arrow - History navigation
        if (key.name === 'up' && this.promptHistory.length > 0) {
          // Save current input on first navigation
          if (this.historyIndex === this.promptHistory.length) {
            tempInput = this.rl?.line || ''
          }
          
          // Go back in history if possible
          if (this.historyIndex > 0) {
            this.historyIndex--
            
            // Clear current line and write historical item
            this.rl?.write('', { ctrl: true, name: 'u' })
            this.rl?.write(this.promptHistory[this.historyIndex])
          }
        }
        
        // Handle Down Arrow - History navigation
        if (key.name === 'down' && this.historyIndex < this.promptHistory.length) {
          this.historyIndex++
          
          // Clear current line
          this.rl?.write('', { ctrl: true, name: 'u' })
          
          if (this.historyIndex === this.promptHistory.length) {
            // At the end, restore the original input
            this.rl?.write(tempInput)
          } else {
            // Show the next history item
            this.rl?.write(this.promptHistory[this.historyIndex])
          }
        }
      }
      
      // Register our keypress handler
      process.stdin.on('keypress', keypressHandler)
      
      // Show the prompt and wait for input
      this.rl.question(prompt, (answer) => {
        // Save to history if not empty and not a duplicate
        if (answer && answer.trim() !== '') {
          if (this.promptHistory.length === 0 || this.promptHistory[this.promptHistory.length - 1] !== answer) {
            this.promptHistory.push(answer)
          }
        }
        
        // Clean up resources
        if (process.stdin.isTTY) {
          process.stdin.setRawMode(false)
        }
        process.stdin.removeListener('keypress', keypressHandler)
        
        if (this.rl) {
          this.rl.close()
          this.rl = null
        }
        
        // Return the user's answer
        resolve(answer)
      })
      
      // Set initial value if provided
      if (defaultValue) {
        this.rl.write(defaultValue)
      }
    })
  }

  handleInviteEvent(event: InviteEvent): void {
    this.interactionInProgress = true

    const prompt = `\n${chalk.black.bgWhite(event.invite)} : `

    this.setupReadlineInterface(prompt, event.defaultValue)
      .then((answer: string) => {
        this.sendEvent(event.buildAnswer(answer))
      })
      .finally(() => {
        this.interactionInProgress = false
      })
  }

  handleChoiceEvent(event: ChoiceEvent): void {
    const { options, invite, optionalQuestion } = event
    this.interactionInProgress = true
    select({
      message: optionalQuestion ? `${optionalQuestion} :\n${invite}` : invite,
      choices: options.map((option) => ({
        name: option,
        value: option,
      })),
    })
      .then((answer: string) => {
        this.sendEvent(event.buildAnswer(answer))
      })
      .finally(() => (this.interactionInProgress = false))
  }

  /**
   * Clean up resources when the terminal is closing
   */
  cleanup(): void {
    if (this.rl) {
      this.rl.close()
    }

    // Reset terminal settings
    if (process.stdin.isTTY) {
      process.stdin.setRawMode(false)
    }
  }
}
