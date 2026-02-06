import { Component, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { WINDOW } from '../../core/tokens/window'

@Component({
  selector: 'app-thinking-loader',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './thinking-loader.component.html',
  styleUrl: './thinking-loader.component.scss',
})
export class ThinkingLoaderComponent implements OnInit, OnDestroy {
  private readonly window = inject(WINDOW)

  // Same thinking phrases as in chat-textarea
  private thinkingPhrases: string[] = ['Processing request...', 'Thinking...', 'Working on it...']
  currentThinkingPhrase: string = 'Processing request...'
  private thinkingPhraseIndex: number = 0
  private thinkingInterval: number | null = null

  ngOnInit(): void {
    this.startThinkingAnimation()
  }

  ngOnDestroy(): void {
    this.stopThinkingAnimation()
  }

  /**
   * Start the thinking phrase animation
   */
  private startThinkingAnimation(): void {
    this.thinkingPhraseIndex = 0
    this.currentThinkingPhrase = this.thinkingPhrases[0] || 'Thinking...'

    this.thinkingInterval = this.window.setInterval(() => {
      // Move to next phrase only if not at the last one
      if (this.thinkingPhraseIndex < this.thinkingPhrases.length - 1) {
        this.thinkingPhraseIndex++
        this.currentThinkingPhrase = this.thinkingPhrases[this.thinkingPhraseIndex] || 'Thinking...'
      }
      // If we're at the last phrase, stay there (no change)
    }, 2000) as number // Change phrase every 2 seconds
  }

  /**
   * Stop the thinking phrase animation
   */
  private stopThinkingAnimation(): void {
    if (this.thinkingInterval) {
      clearInterval(this.thinkingInterval)
      this.thinkingInterval = null
    }
    this.thinkingPhraseIndex = 0
    this.currentThinkingPhrase = 'Thinking...'
  }
}
