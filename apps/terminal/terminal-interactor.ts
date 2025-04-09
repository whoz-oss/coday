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
      
      // Set up history navigation - start at the end of history
      this.historyIndex = this.promptHistory.length
      let tempInput = defaultValue || ''
      
      // Enable raw mode for better handling of keypress events
      if (process.stdin.isTTY) {
        process.stdin.setRawMode(true)
      }
      
      // Make sure we can process keypresses
      readline.emitKeypressEvents(process.stdin)
      
      let isSubmitting = false;

      // Override the default readline _ttyWrite method to capture Enter and Alt+Enter
      if (this.rl) {
        const originalTtyWrite = (this.rl as any)._ttyWrite;
        
        if (originalTtyWrite) {
          (this.rl as any)._ttyWrite = function(s: string, key: any) {
            // Intercept Alt+Enter to insert a newline instead of submitting
            if (key && key.name === 'return' && key.meta) {
              // Manually insert newline at current cursor position
              this.line = this.line.slice(0, this.cursor) + 
                            '\n' + 
                            this.line.slice(this.cursor);
              this.cursor++;
              
              // Refresh the line display
              this._refreshLine();
              return;
            }
            
            // Prevent submission when cursor is not at the end of multiline input
            if (key && key.name === 'return' && !key.meta && !key.ctrl && !key.shift) {
              // Check if we're in the middle of a multiline input
              const lineAfterCursor = this.line.slice(this.cursor);
              if (lineAfterCursor.includes('\n')) {
                // If there are more lines after cursor, move cursor to next line instead of submitting
                const nextNewlinePos = lineAfterCursor.indexOf('\n');
                this.cursor += nextNewlinePos + 1;
                this._refreshLine();
                return;
              }
            }
            
            // Otherwise call the original method for all other keys
            return originalTtyWrite.call(this, s, key);
          };
        }
      }
      
      // Process key events for history navigation
      const keypressHandler = (str: string, key: any) => {
        if (!key) return
        
        // Skip if we're in the process of submitting
        if (isSubmitting) return
                
        // Handle Up Arrow - History navigation
        if (key.name === 'up' && this.promptHistory.length > 0) {
          // Save current input on first navigation
          if (this.historyIndex === this.promptHistory.length) {
            tempInput = this.rl?.line || ''
          }
          
          // Go back in history if possible
          if (this.historyIndex > 0) {
            this.historyIndex--
            
            // For multiline content, we need to handle it differently
            const historyItem = this.promptHistory[this.historyIndex];
            
            if (this.rl) {
              // Use direct manipulation of readline interface to set the content
              // This works better for multiline content than rl.write()
              (this.rl as any).line = historyItem;
              (this.rl as any).cursor = historyItem.length;
              (this.rl as any)._refreshLine();
            }
          }
        }
        
        // Handle Down Arrow - History navigation
        if (key.name === 'down' && this.historyIndex < this.promptHistory.length) {
          this.historyIndex++
          
          if (this.rl) {
            if (this.historyIndex === this.promptHistory.length) {
              // At the end, restore the original input
              (this.rl as any).line = tempInput;
              (this.rl as any).cursor = tempInput.length;
            } else {
              // Show the next history item
              const historyItem = this.promptHistory[this.historyIndex];
              (this.rl as any).line = historyItem;
              (this.rl as any).cursor = historyItem.length;
            }
            
            // Refresh the display
            (this.rl as any)._refreshLine();
          }
        }
      }
      
      // Register our keypress handler
      process.stdin.on('keypress', keypressHandler)
      
      // Show the prompt and wait for input
      this.rl.question(prompt, (answer) => {
        // Mark as submitting to prevent further keypress handling
        isSubmitting = true;
        
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
          // Restore original _ttyWrite method if we modified it
          if ((this.rl as any)._ttyWrite && typeof (this.rl as any)._ttyWrite !== 'function') {
            (this.rl as any)._ttyWrite = undefined;
          }
          
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
