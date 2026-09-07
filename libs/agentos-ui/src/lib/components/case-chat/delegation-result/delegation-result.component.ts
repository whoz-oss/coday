import { NgTemplateOutlet } from '@angular/common'
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { Router } from '@angular/router'
import DOMPurify from 'dompurify'
import { marked, Renderer } from 'marked'

interface DelegationResult {
  agentName: string
  model?: string
  state: 'success' | 'pending' | 'error'
  subCaseId?: string
  resultHtml?: SafeHtml
  pendingQuestionHtml?: SafeHtml
  options?: string[]
  errorHtml?: SafeHtml
  errorType?: string
}

@Component({
  selector: 'agentos-delegation-result',
  imports: [NgTemplateOutlet],
  templateUrl: './delegation-result.component.html',
  styleUrl: './delegation-result.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DelegationResultComponent {
  private readonly domSanitizer = inject(DomSanitizer)
  private readonly router = inject(Router)
  private readonly markdownRenderer = new Renderer()

  /** Runtime ToolResponseEvent.output: usually a MessageContent-like object, but accepts a string defensively. */
  readonly rawOutput = input<unknown>(null)
  readonly rawPayload = input<string | null>(null)
  readonly namespaceId = input.required<string>()
  readonly showTechnical = input(false)
  /** Raw fallback preserves the parent tool call's collapsed-by-default behavior. */
  readonly expanded = input(false)

  /** The actual JSON array string, unwrapped from the runtime MessageContent-shaped output. */
  protected readonly outputText = computed(() => this.unwrapOutput(this.rawOutput()))

  /** Null means parsing did not recognize the complete backend response schema. */
  protected readonly results = computed(() => this.parse(this.outputText()))

  private unwrapOutput(output: unknown): string | null {
    if (typeof output === 'string') return output
    if (!output || typeof output !== 'object') return null
    const content = (output as Record<string, unknown>)['content']
    return typeof content === 'string' ? content : null
  }

  private parse(rawOutput: string | null): DelegationResult[] | null {
    if (!rawOutput) return null
    try {
      const parsed: unknown = JSON.parse(rawOutput)
      if (!Array.isArray(parsed) || parsed.length === 0) return null
      const results = parsed.map((entry) => this.parseEntry(entry))
      return results.every((result): result is DelegationResult => result !== null) ? results : null
    } catch {
      return null
    }
  }

  private parseEntry(entry: unknown): DelegationResult | null {
    if (!entry || typeof entry !== 'object') return null
    const value = entry as Record<string, unknown>
    const agentName = typeof value['agentName'] === 'string' ? value['agentName'] : null
    const subCaseId = typeof value['subCaseId'] === 'string' ? value['subCaseId'] : undefined
    if (!agentName || typeof value['success'] !== 'boolean') return null

    const result = typeof value['result'] === 'string' ? value['result'] : null
    const pendingQuestion = typeof value['pendingQuestion'] === 'string' ? value['pendingQuestion'] : null
    const error = typeof value['error'] === 'string' ? value['error'] : null
    const options =
      Array.isArray(value['options']) && value['options'].every((option) => typeof option === 'string')
        ? (value['options'] as string[])
        : undefined
    const errorType = typeof value['errorType'] === 'string' ? value['errorType'] : undefined
    const model =
      typeof value['llmModel'] === 'string'
        ? value['llmModel']
        : typeof value['model'] === 'string'
          ? value['model']
          : undefined

    if (pendingQuestion !== null && value['success'] === true) {
      return {
        agentName,
        model,
        subCaseId,
        state: 'pending',
        pendingQuestionHtml: this.renderMarkdown(pendingQuestion),
        options,
      }
    }
    if (result !== null && value['success'] === true) {
      return { agentName, model, subCaseId, state: 'success', resultHtml: this.renderMarkdown(result) }
    }
    if (error !== null && value['success'] === false) {
      return { agentName, model, subCaseId, state: 'error', errorHtml: this.renderMarkdown(error), errorType }
    }
    return null
  }

  protected openSubCase(subCaseId: string): void {
    void this.router.navigate(['/agentos/home'], { queryParams: { ns: this.namespaceId(), case: subCaseId } })
  }

  protected rawOutputText(): string | null {
    return this.outputText() ?? (this.rawOutput() === null ? null : JSON.stringify(this.rawOutput()))
  }

  private renderMarkdown(text: string): SafeHtml {
    const rawHtml = marked.parse(text, {
      renderer: this.markdownRenderer,
      breaks: true,
      gfm: true,
      async: false,
    }) as string
    const clean = DOMPurify.sanitize(rawHtml, {
      ADD_TAGS: ['span'],
      ADD_ATTR: ['aria-hidden', 'aria-label', 'target', 'rel'],
      ALLOWED_URI_REGEXP: /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i,
    })
    return this.domSanitizer.bypassSecurityTrustHtml(clean)
  }
}
