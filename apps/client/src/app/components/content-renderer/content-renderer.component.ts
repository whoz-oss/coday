import { Component, Input, OnChanges, SimpleChanges } from '@angular/core'
import { CommonModule } from '@angular/common'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { marked } from 'marked'

import type { ContentFormat } from '../../core/services/content-viewer.service'

/**
 * ContentRendererComponent - Renders file content based on format
 *
 * Supports:
 * - Markdown: Rendered HTML with marked library
 * - JSON: Formatted with 2-space indentation
 * - YAML: Displayed as-is with monospace
 * - Text: Displayed as-is with monospace
 */
@Component({
  selector: 'app-content-renderer',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="content-renderer" [ngClass]="'format-' + format">
      @if (format === 'markdown') {
        <div class="markdown-content" [innerHTML]="renderedContent"></div>
      } @else {
        <pre class="code-content">{{ content }}</pre>
      }
    </div>
  `,
  styles: [
    `
      .content-renderer {
        width: 100%;
        height: 100%;
        overflow: auto;
        padding: 1rem;
      }

      .markdown-content {
        line-height: 1.6;
        color: var(--color-text);
      }

      .code-content {
        font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
        font-size: 0.9rem;
        line-height: 1.5;
        margin: 0;
        white-space: pre-wrap;
        word-wrap: break-word;
        color: var(--color-text);
      }

      /* Format-specific styling */
      .format-json .code-content {
        color: var(--color-code-text, #d63384);
      }

      .format-yaml .code-content {
        color: var(--color-text);
      }

      .format-text .code-content {
        color: var(--color-text);
      }
    `,
  ],
})
export class ContentRendererComponent implements OnChanges {
  @Input({ required: true }) content: string = ''
  @Input({ required: true }) format: ContentFormat = 'text'

  renderedContent: SafeHtml = ''

  constructor(private sanitizer: DomSanitizer) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['content'] || changes['format']) {
      this.renderContent()
    }
  }

  private renderContent(): void {
    if (this.format === 'markdown') {
      this.renderMarkdown()
    } else if (this.format === 'json') {
      this.formatJson()
    }
    // YAML and text are displayed as-is
  }

  private renderMarkdown(): void {
    try {
      const html = marked.parse(this.content) as string
      this.renderedContent = this.sanitizer.bypassSecurityTrustHtml(html)
    } catch (error) {
      console.error('[CONTENT_RENDERER] Error rendering markdown:', error)
      // Fallback to plain text
      this.content = this.content
    }
  }

  private formatJson(): void {
    try {
      const parsed = JSON.parse(this.content)
      this.content = JSON.stringify(parsed, null, 2)
    } catch (error) {
      console.error('[CONTENT_RENDERER] Error formatting JSON:', error)
      // Keep original content if parsing fails
    }
  }
}
