import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core'
import { MarkdownRendererService } from '../../../services/markdown-renderer.service'
import { DelegationResult } from '../models/delegation.models'
@Component({
  selector: 'agentos-delegation-result',
  templateUrl: './delegation-result.component.html',
  styleUrl: './delegation-result.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DelegationResultComponent {
  readonly result = input.required<DelegationResult>()
  /** Render the file references of the result as IDE links (the namespace has a project root to open them from). */
  readonly fileLinks = input(false)
  private readonly markdown = inject(MarkdownRendererService)
  protected readonly html = computed(() =>
    // Read-only preview: no copy button.
    this.markdown.render(this.result().result ?? this.result().error ?? 'No result returned.', {
      fileLinks: this.fileLinks(),
      copyButton: false,
    })
  )
}
