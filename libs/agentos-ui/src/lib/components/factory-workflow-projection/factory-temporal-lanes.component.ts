import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { WorkflowProjectionV2, WorkflowTimingDto } from '../../services/factory-workflow-projection.model'
import { BLUEPRINT_LANES, BlueprintNode, buildBlueprintLayout } from './factory-temporal-lanes.models'

@Component({
  selector: 'agentos-factory-temporal-lanes',
  templateUrl: './factory-temporal-lanes.component.html',
  styleUrl: './factory-temporal-lanes.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryTemporalLanesComponent {
  readonly projection = input.required<WorkflowProjectionV2>()
  readonly timing = input<WorkflowTimingDto>()
  readonly selectedStepId = input<string | null>(null)
  readonly stepSelected = output<string>()
  protected readonly layout = computed(() => buildBlueprintLayout(this.projection(), this.timing()))
  protected readonly lanes = computed(() =>
    BLUEPRINT_LANES.map((kind) => ({
      kind,
      label: kind.charAt(0).toUpperCase() + kind.slice(1),
      nodes: this.layout().nodes.filter((node) => node.actorKind === kind),
    }))
  )
  protected selectStep(stepId: string): void {
    this.stepSelected.emit(stepId)
  }
  protected statusLabel(status: string): string {
    return status.replace(/_/g, ' ')
  }
  protected nodeLabel(node: BlueprintNode): string {
    const dependencies = node.dependencies.length ? `Depends on ${node.dependencies.join(', ')}` : 'No dependencies'
    const successors = node.successors.length ? `Next: ${node.successors.join(', ')}` : 'No successors'
    return `${node.name}. ${node.actorName ?? node.actorKind}. Status ${this.statusLabel(node.status)}. ${dependencies}. ${successors}.`
  }
  protected nodeLeft(node: BlueprintNode): string {
    return `${Math.max(0, Math.min(100 - node.width, node.x - node.width / 2))}%`
  }
  protected nodeWidth(node: BlueprintNode): string {
    return `${node.width}%`
  }
}
