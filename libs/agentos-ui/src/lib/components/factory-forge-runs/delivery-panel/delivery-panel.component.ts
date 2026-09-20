import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { FactoryDeliverySnapshotDto, FactoryDeliveryStage } from '../../../services/factory-delivery.model'

@Component({
  selector: 'agentos-delivery-panel',
  standalone: true,
  templateUrl: './delivery-panel.component.html',
  styleUrl: './delivery-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeliveryPanelComponent {
  readonly delivery = input<FactoryDeliverySnapshotDto | null>(null)
  readonly loading = input(false)
  readonly error = input<string | null>(null)
  readonly action = output<'checkpoint' | 'push' | 'pull-request' | FactoryDeliveryStage>()
  readonly stages: readonly FactoryDeliveryStage[] = [
    'implementation-ready',
    'artifact-ready',
    'release-approved',
    'deployed',
    'production-verified',
  ]
  /** Pre-computed stage index for use in template (avoids method calls in @for). */
  readonly currentStageIndex = computed(() => {
    const current = this.delivery()?.stage
    return current ? this.stages.indexOf(current) : -1
  })
  /** Enriched stage list with done/current flags for template rendering. */
  readonly stageItems = computed(() => {
    const currentIndex = this.currentStageIndex()
    return this.stages.map((stage, index) => ({
      stage,
      done: index < currentIndex,
      current: index === currentIndex,
    }))
  })
  readonly nextStage = computed<FactoryDeliveryStage | null>(() => {
    const index = this.currentStageIndex()
    return index >= 0 && index < this.stages.length - 1 ? this.stages[index + 1]! : null
  })
  readonly humanAction = computed(() => this.nextStage() === 'release-approved')
  trustedUrl(url: string): string | null {
    try {
      const parsed = new URL(url)
      return parsed.protocol === 'https:' && ['github.com', 'www.github.com'].includes(parsed.hostname)
        ? parsed.toString()
        : null
    } catch {
      return null
    }
  }
}
