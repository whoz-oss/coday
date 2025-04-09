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
      
      // Initialize history - not used by our class variable anymore
      // History is managed in the ttyWrite method
      
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
      
      // We need to override readline's default handling of up/down arrows
      // to provide the right behavior for multiline inputs
      if (this.rl && (this.rl as any)._ttyWrite) {
        const originalTtyWrite = (this.rl as any)._ttyWrite;
        
        // Override to handle both Alt+Enter and arrow navigation
        (this.rl as any)._ttyWrite = function(s: string, key: any) {
          // Handle Alt+Enter for multiline input
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
          
          // Get current line and cursor position
          const line = this.line || '';
          const cursor = this.cursor || 0;
          
          // Helper functions to check cursor position and navigate vertically
          const countLines = (text: string) => (text.match(/\n/g) || []).length + 1;
          
          // Calculate current line number and total lines
          const textBeforeCursor = line.substring(0, cursor);
          const currentLineNum = countLines(textBeforeCursor);
          const totalLines = countLines(line);
          
          // Find position of cursor in current line
          const lastNewlineBeforeCursor = textBeforeCursor.lastIndexOf('\n');
          const positionInCurrentLine = lastNewlineBeforeCursor === -1 ? 
                                       cursor : 
                                       cursor - lastNewlineBeforeCursor - 1;
          
          // Handle Up Arrow key
          if (key && key.name === 'up') {
            if (currentLineNum === 1) {
              // If on first line, use history if available
              if (tempHistoryIndex < promptHistory.length) {
                // First time going into history mode
                if (tempHistoryIndex === 0) {
                  tempInput = line;
                }
                tempHistoryIndex++;
                
                // Get historical item from end of array
                const historyIndex = promptHistory.length - tempHistoryIndex;
                this.line = promptHistory[historyIndex];
                this.cursor = this.line.length;
                this._refreshLine();
              }
            } else {
              // Find the previous newline to calculate the position
              let searchPos = lastNewlineBeforeCursor - 1;
              let prevNewlinePos = textBeforeCursor.lastIndexOf('\n', searchPos);
              
              // If previous line exists, calculate its length
              if (prevNewlinePos === -1) {
                // First line
                const prevLineLength = lastNewlineBeforeCursor;
                let newCursor = Math.min(positionInCurrentLine, prevLineLength);
                this.cursor = newCursor;
              } else {
                // Other lines
                const prevLineLength = lastNewlineBeforeCursor - prevNewlinePos - 1;
                let newCursor = prevNewlinePos + 1 + Math.min(positionInCurrentLine, prevLineLength);
                this.cursor = newCursor;
              }
              
              this._refreshLine();
            }
            return;
          }
          
          // Handle Down Arrow key
          if (key && key.name === 'down') {
            if (currentLineNum === totalLines) {
              // If on last line, navigate history forward
              if (tempHistoryIndex > 0) {
                tempHistoryIndex--;
                
                if (tempHistoryIndex === 0) {
                  this.line = tempInput;
                  this.cursor = this.line.length;
                } else {
                  // Get history item 
                  const historyIndex = promptHistory.length - tempHistoryIndex;
                  this.line = promptHistory[historyIndex];
                  this.cursor = this.line.length;
                }
                this._refreshLine();
              }
            } else {
              // Find the next newline position
              const nextNewlinePos = line.indexOf('\n', cursor);
              
              if (nextNewlinePos !== -1) {
                // Get position after the newline
                const posAfterNewline = nextNewlinePos + 1;
                
                // Find end of next line 
                const endOfNextLine = line.indexOf('\n', posAfterNewline);
                
                // Calculate length of next line
                const nextLineLength = endOfNextLine === -1 ? 
                                      line.length - posAfterNewline : 
                                      endOfNextLine - posAfterNewline;
                
                // Set cursor position in the next line
                const newCursor = posAfterNewline + Math.min(positionInCurrentLine, nextLineLength);
                this.cursor = newCursor;
                this._refreshLine();
              }
            }
            return;
          }
          
          // For all other keys, pass through to original handler
          return originalTtyWrite.call(this, s, key);
        };
        
        // Setup access to history
        const promptHistory = this.promptHistory;
        let tempHistoryIndex = 0;
        let tempInput = '';
      }
      
      // Our separate keypress handler for other functions
      const keypressHandler = (str: string, key: any) => {
        if (!key) return
        
        // Skip if we're in the process of submitting
        if (isSubmitting) return
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
