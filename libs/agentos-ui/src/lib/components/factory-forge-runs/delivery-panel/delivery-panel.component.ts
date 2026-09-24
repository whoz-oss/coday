import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core'
import { FactoryDeliverySnapshotDto, FactoryDeliveryStage } from '../../../services/factory-delivery.model'

@Component({
  selector: 'agentos-delivery-panel',
  templateUrl: './delivery-panel.component.html',
  styleUrl: './delivery-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeliveryPanelComponent {
  readonly delivery = input<FactoryDeliverySnapshotDto | null>(null)
  readonly loading = input(false)
  readonly error = input<string | null>(null)
  readonly stages: readonly FactoryDeliveryStage[] = [
    'implementation-ready',
    'artifact-ready',
    'release-approved',
    'deployed',
    'production-verified',
  ]
  readonly currentStageIndex = computed(() => {
    const current = this.delivery()?.stage
    return current ? this.stages.indexOf(current) : -1
  })
  readonly stageItems = computed(() => {
    const currentIndex = this.currentStageIndex()
    return this.stages.map((stage, index) => ({ stage, done: index < currentIndex, current: index === currentIndex }))
  })
  readonly operations = computed(() => this.delivery()?.deliveryOperations ?? [])
  readonly unresolvedIndeterminate = computed(() => this.delivery()?.unresolvedIndeterminate ?? [])
  readonly rollbackRequests = computed(() => this.delivery()?.rollbackRequests ?? [])

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

  timestamp(value: string | undefined): string {
    if (!value) return 'Not recorded'
    const parsed = new Date(value)
    return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString()
  }
}
