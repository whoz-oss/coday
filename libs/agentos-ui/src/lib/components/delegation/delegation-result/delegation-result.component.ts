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
  private readonly markdown = inject(MarkdownRendererService)
  protected readonly html = computed(() =>
    this.markdown.render(this.result().result ?? this.result().error ?? 'No result returned.')
  )
}
