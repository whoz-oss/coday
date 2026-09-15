import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core'
import {
  IntegrationConfigToolPreview,
  IntegrationConfigToolPreviewTool,
  IntegrationConfigToolPreviewToolConfirmationModeEnum,
} from '@whoz-oss/agentos-api-client'

/** Pretty-prints a JSON schema string; a schema that is not valid JSON is shown verbatim. */
export function formatInputSchema(schema: string): string {
  try {
    return JSON.stringify(JSON.parse(schema), null, 2)
  } catch {
    return schema
  }
}

/**
 * IntegrationToolPreviewComponent — presentational panel for the result of
 * `POST /api/integration-configs/{id}/preview-tools`: the plugin's namespace line, the failure
 * reason (if any) and the tools with a collapsible input schema each.
 */
@Component({
  selector: 'agentos-integration-tool-preview',
  templateUrl: './integration-tool-preview.component.html',
  styleUrl: './integration-tool-preview.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationToolPreviewComponent {
  readonly preview = input.required<IntegrationConfigToolPreview>()

  /** Names of the tools whose input schema is currently shown. */
  private readonly expandedTools = signal<ReadonlySet<string>>(new Set())

  protected readonly tools = computed<IntegrationConfigToolPreviewTool[]>(() => this.preview().tools ?? [])

  protected readonly isEmpty = computed(() => this.tools().length === 0 && !this.preview().error)

  /** Formatted schemas, computed once per preview rather than on every change detection. */
  private readonly formattedSchemas = computed(
    () => new Map(this.tools().map((tool) => [tool.name, formatInputSchema(tool.inputSchema)]))
  )

  protected isExpanded(toolName: string): boolean {
    return this.expandedTools().has(toolName)
  }

  protected toggleSchema(toolName: string): void {
    this.expandedTools.update((current) => {
      const next = new Set(current)
      if (!next.delete(toolName)) next.add(toolName)
      return next
    })
  }

  protected schemaOf(toolName: string): string {
    return this.formattedSchemas().get(toolName) ?? ''
  }

  protected requiresConfirmation(tool: IntegrationConfigToolPreviewTool): boolean {
    return tool.confirmationMode !== IntegrationConfigToolPreviewToolConfirmationModeEnum.NONE
  }
}
